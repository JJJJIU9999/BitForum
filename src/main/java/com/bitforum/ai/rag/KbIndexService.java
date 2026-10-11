package com.bitforum.ai.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bitforum.ai.entity.AiKbChunk;
import com.bitforum.ai.entity.AiKbDocument;
import com.bitforum.ai.mapper.AiKbChunkMapper;
import com.bitforum.ai.mapper.AiKbDocumentMapper;
import com.bitforum.entity.Article;
import com.bitforum.mapper.ArticleMapper;
import com.bitforum.service.ArticleService;

/**
 * 知识库索引服务（M15）。
 *
 * <p>负责把已发布文章切块、向量化并写入 Redis 向量库，同时在 MySQL 记录映射与状态。
 *
 * <p><b>一致性策略</b>：向量写入（Redis）与元数据写入（MySQL）无法放进同一个事务，
 * 因此顺序固定为「先删旧 → 写向量 → 写记录 → 更新状态」，任一步失败就把文档标记为
 * {@code FAILED} 并记录原因，由下一次重建修复。检索侧只信 MySQL 中的
 * {@code INDEXED} 记录，因此半成品不会被当作可用知识。
 *
 * <p><b>幂等与去重</b>：文章内容指纹（{@code content_hash}）未变化且模型未更换时直接跳过，
 * 避免每次保存都重新嵌入；指纹变化时先按分块表里保存的 {@code vector_id} 精确删除旧向量再重建，
 * 不会残留孤儿向量。
 */
@Service
public class KbIndexService {

    private static final Logger log = LoggerFactory.getLogger(KbIndexService.class);

    /** 单篇文章索引的结果类型 */
    public enum IndexResult {
        /** 已写入或更新 */
        INDEXED,
        /** 内容与模型都未变化，跳过 */
        SKIPPED,
        /** 文章已不在知识库范围内（非已发布），旧数据已移除 */
        REMOVED,
        /** 索引失败，已记录原因 */
        FAILED
    }

    /** 单篇文章的索引结果 */
    public record IndexOutcome(IndexResult result, int chunkCount, String message) {
    }

    /** 全量重建的汇总结果 */
    public record RebuildResult(
            int publishedArticles, int indexed, int skipped, int removed, int failed, long elapsedMillis) {
    }

    /** 知识库统计（管理员接口与验收使用） */
    public record KbStats(
            long publishedArticles,
            long documents,
            long indexedDocuments,
            long pendingDocuments,
            long failedDocuments,
            long chunks,
            String embeddingModel) {
    }

    private final ArticleChunkingService chunkingService;
    private final VectorStore vectorStore;
    private final ArticleMapper articleMapper;
    private final AiKbDocumentMapper documentMapper;
    private final AiKbChunkMapper chunkMapper;

    /** 当前嵌入模型标识，写入文档用于识别换模型后需要重建的数据 */
    private final String embeddingModelName;

    public KbIndexService(
            ArticleChunkingService chunkingService,
            VectorStore vectorStore,
            ArticleMapper articleMapper,
            AiKbDocumentMapper documentMapper,
            AiKbChunkMapper chunkMapper,
            @Value("${bitforum.ai.rag.embedding-model-name:bge-base-zh-v1.5}") String embeddingModelName) {
        this.chunkingService = chunkingService;
        this.vectorStore = vectorStore;
        this.articleMapper = articleMapper;
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.embeddingModelName = embeddingModelName;
    }

    /**
     * 全量重建知识库：索引所有已发布文章，并清理不再属于知识库的旧记录。
     */
    public RebuildResult rebuildAll() {
        long startedAt = System.currentTimeMillis();

        List<Article> published = articleMapper.selectList(
                new LambdaQueryWrapper<Article>().eq(Article::getStatus, ArticleService.STATUS_PUBLISHED));

        int indexed = 0;
        int skipped = 0;
        int failed = 0;
        for (Article article : published) {
            IndexOutcome outcome = indexArticle(article);
            switch (outcome.result()) {
                case INDEXED -> indexed++;
                case SKIPPED -> skipped++;
                case FAILED -> failed++;
                case REMOVED -> {
                    // 已发布文章不会被判为 REMOVED，这里仅为分支完整性
                }
            }
        }

        int removed = removeStaleDocuments(published);
        long elapsed = System.currentTimeMillis() - startedAt;

        log.info(
                ">>> 知识库全量重建完成：已发布 {} 篇 → 索引 {}、跳过 {}、失败 {}、清理陈旧 {}，耗时 {} ms",
                published.size(), indexed, skipped, failed, removed, elapsed);

        return new RebuildResult(published.size(), indexed, skipped, removed, failed, elapsed);
    }

    /**
     * 索引单篇文章（增量入口，M15 第三步的异步队列会复用本方法）。
     *
     * <p>文章为已发布状态才入库；非已发布状态会移除既有索引数据，保证"下架即不可召回"。
     */
    public IndexOutcome indexArticle(Article article) {
        if (article == null || article.getId() == null) {
            return new IndexOutcome(IndexResult.SKIPPED, 0, "文章为空");
        }

        AiKbDocument existing = findDocument(article.getId());

        if (!ArticleService.STATUS_PUBLISHED.equals(article.getStatus())) {
            if (existing == null) {
                return new IndexOutcome(IndexResult.SKIPPED, 0, "文章非已发布状态且无索引记录");
            }
            removeArticle(article.getId());
            return new IndexOutcome(IndexResult.REMOVED, 0, "文章非已发布状态，已从知识库移除");
        }

        String contentHash = hash(article.getTitle() + "\n" + article.getContent());
        if (existing != null
                && contentHash.equals(existing.getContentHash())
                && AiKbDocument.INDEX_STATUS_INDEXED.equals(existing.getIndexStatus())
                && embeddingModelName.equals(existing.getEmbeddingModel())) {
            return new IndexOutcome(IndexResult.SKIPPED, existing.getChunkCount(), "内容与模型均未变化");
        }

        try {
            return doIndex(article, existing, contentHash);
        } catch (RuntimeException exception) {
            log.error("知识库索引失败：articleId={}, title={}", article.getId(), article.getTitle(), exception);
            markFailed(article, existing, exception);
            return new IndexOutcome(IndexResult.FAILED, 0, exception.getMessage());
        }
    }

    /**
     * 从知识库移除一篇文章（文章下架或删除时使用）。
     */
    public void removeArticle(Long articleId) {
        AiKbDocument document = findDocument(articleId);
        if (document == null) {
            return;
        }
        deleteVectors(document.getId());
        chunkMapper.delete(new LambdaQueryWrapper<AiKbChunk>().eq(AiKbChunk::getDocumentId, document.getId()));
        documentMapper.deleteById(document.getId());
        log.info("已从知识库移除文章：articleId={}，移除分块 {} 个", articleId, document.getChunkCount());
    }

    /** 知识库统计：管理员接口展示，也用于验收"统计数与已发布文章数一致"。 */
    public KbStats stats() {
        long publishedArticles = articleMapper.selectCount(
                new LambdaQueryWrapper<Article>().eq(Article::getStatus, ArticleService.STATUS_PUBLISHED));
        return new KbStats(
                publishedArticles,
                documentMapper.selectCount(null),
                countDocumentsByStatus(AiKbDocument.INDEX_STATUS_INDEXED),
                countDocumentsByStatus(AiKbDocument.INDEX_STATUS_PENDING),
                countDocumentsByStatus(AiKbDocument.INDEX_STATUS_FAILED),
                chunkMapper.selectCount(null),
                embeddingModelName);
    }

    // ==================== 内部实现 ====================

    private IndexOutcome doIndex(Article article, AiKbDocument existing, String contentHash) {
        // 1. 清理旧向量与旧分块：先删再写，避免新旧混在一起被召回
        if (existing != null) {
            deleteVectors(existing.getId());
            chunkMapper.delete(new LambdaQueryWrapper<AiKbChunk>().eq(AiKbChunk::getDocumentId, existing.getId()));
        }

        // 2. 分块
        List<ArticleChunkingService.Chunk> chunks =
                chunkingService.chunk(article.getTitle(), article.getContent());
        if (chunks.isEmpty()) {
            markFailed(article, existing, new IllegalStateException("文章正文为空，无法分块"));
            return new IndexOutcome(IndexResult.FAILED, 0, "文章正文为空，无法分块");
        }

        // 3. 先落 document 行，拿到 documentId 供分块表引用
        AiKbDocument document = existing != null ? existing : new AiKbDocument();
        document.setArticleId(article.getId());
        document.setTitle(article.getTitle());
        document.setCategoryId(article.getCategoryId());
        document.setAuthorId(article.getUserId());
        document.setStatus(article.getStatus());
        document.setPublishTime(resolvePublishTime(article));
        document.setContentHash(contentHash);
        document.setChunkCount(chunks.size());
        document.setIndexStatus(AiKbDocument.INDEX_STATUS_PENDING);
        document.setEmbeddingModel(embeddingModelName);
        document.setLastError(null);
        if (document.getId() == null) {
            documentMapper.insert(document);
        } else {
            documentMapper.updateById(document);
        }

        // 4. 写入向量库
        List<Document> vectorDocuments = new ArrayList<>(chunks.size());
        for (ArticleChunkingService.Chunk chunk : chunks) {
            vectorDocuments.add(new Document(
                    UUID.randomUUID().toString(), chunk.content(), vectorMetadata(article, chunk.index())));
        }
        vectorStore.add(vectorDocuments);

        // 5. 记录分块与向量 id 的对应关系
        for (int index = 0; index < chunks.size(); index++) {
            ArticleChunkingService.Chunk chunk = chunks.get(index);
            AiKbChunk row = new AiKbChunk();
            row.setDocumentId(document.getId());
            row.setArticleId(article.getId());
            row.setChunkIndex(chunk.index());
            row.setContent(chunk.content());
            row.setCharCount(chunk.charCount());
            row.setVectorId(vectorDocuments.get(index).getId());
            chunkMapper.insert(row);
        }

        // 6. 标记完成
        document.setIndexStatus(AiKbDocument.INDEX_STATUS_INDEXED);
        document.setIndexTime(LocalDateTime.now());
        documentMapper.updateById(document);

        log.info("知识库索引成功：articleId={}，分块 {} 个", article.getId(), chunks.size());
        return new IndexOutcome(IndexResult.INDEXED, chunks.size(), "索引成功");
    }

    /** 清理「数据库里还有、但已不在已发布集合中」的文档记录。 */
    private int removeStaleDocuments(List<Article> published) {
        List<Long> publishedIds = published.stream().map(Article::getId).toList();
        List<AiKbDocument> documents = documentMapper.selectList(null);

        int removed = 0;
        for (AiKbDocument document : documents) {
            if (!publishedIds.contains(document.getArticleId())) {
                removeArticle(document.getArticleId());
                removed++;
            }
        }
        return removed;
    }

    private void deleteVectors(Long documentId) {
        List<AiKbChunk> chunks = chunkMapper.selectList(
                new LambdaQueryWrapper<AiKbChunk>().eq(AiKbChunk::getDocumentId, documentId));
        List<String> vectorIds = chunks.stream()
                .map(AiKbChunk::getVectorId)
                .filter(StringUtils::hasText)
                .toList();
        if (!vectorIds.isEmpty()) {
            vectorStore.delete(vectorIds);
        }
    }

    private Map<String, Object> vectorMetadata(Article article, int chunkIndex) {
        return Map.of(
                "articleId", String.valueOf(article.getId()),
                "categoryId", article.getCategoryId() == null ? "0" : String.valueOf(article.getCategoryId()),
                "status", article.getStatus(),
                "publishTime", resolvePublishTime(article).atZone(ZoneId.systemDefault()).toEpochSecond(),
                "chunkIndex", chunkIndex);
    }

    private LocalDateTime resolvePublishTime(Article article) {
        return article.getCreateTime() != null ? article.getCreateTime() : LocalDateTime.now();
    }

    private AiKbDocument findDocument(Long articleId) {
        return documentMapper.selectOne(
                new LambdaQueryWrapper<AiKbDocument>().eq(AiKbDocument::getArticleId, articleId));
    }

    private long countDocumentsByStatus(String indexStatus) {
        return documentMapper.selectCount(
                new LambdaQueryWrapper<AiKbDocument>().eq(AiKbDocument::getIndexStatus, indexStatus));
    }

    /** 失败时尽力记录状态；记录本身再失败也不能影响调用方。 */
    private void markFailed(Article article, AiKbDocument existing, RuntimeException exception) {
        try {
            AiKbDocument document = existing != null ? existing : findDocument(article.getId());
            if (document == null) {
                document = new AiKbDocument();
                document.setArticleId(article.getId());
                document.setTitle(article.getTitle());
                document.setCategoryId(article.getCategoryId());
                document.setAuthorId(article.getUserId());
                document.setStatus(article.getStatus());
                document.setPublishTime(resolvePublishTime(article));
                document.setContentHash("");
                document.setChunkCount(0);
                document.setIndexStatus(AiKbDocument.INDEX_STATUS_FAILED);
                document.setEmbeddingModel(embeddingModelName);
                document.setLastError(truncate(exception.getMessage()));
                documentMapper.insert(document);
                return;
            }
            document.setIndexStatus(AiKbDocument.INDEX_STATUS_FAILED);
            document.setLastError(truncate(exception.getMessage()));
            documentMapper.updateById(document);
        } catch (RuntimeException nested) {
            log.error("记录索引失败状态时再次异常：articleId={}", article.getId(), nested);
        }
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 255 ? message : message.substring(0, 255);
    }

    private String hash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", exception);
        }
    }
}

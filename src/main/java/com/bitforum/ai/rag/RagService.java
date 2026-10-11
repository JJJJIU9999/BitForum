package com.bitforum.ai.rag;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bitforum.ai.entity.AiKbDocument;
import com.bitforum.ai.mapper.AiKbDocumentMapper;
import com.bitforum.entity.Article;
import com.bitforum.mapper.ArticleMapper;
import com.bitforum.service.ArticleService;

/**
 * 检索服务（M15）：把用户问题转成向量，从知识库召回相关文章片段。
 *
 * <p><b>四重约束</b>（缺一不可）：
 *
 * <ol>
 *   <li>检索请求带 {@code status == 'PUBLISHED'} 过滤，已下架文章即使还有残留向量也不会被召回；</li>
 *   <li>召回后按 {@code article} 表的**当前**状态做二次校验，
 *       即使索引尚未重建，已下架文章也不会被当作回答依据；</li>
 *   <li><b>S1</b>：再按 {@code ai_kb_document.index_status == 'INDEXED'} 批量校验一次 ——
 *       索引流程是"先写向量、后写 MySQL 状态"，后半段失败时向量仍在 Redis 里，
 *       这类 FAILED/PENDING 半成品不得进入回答上下文；</li>
 *   <li>{@code topK} 与单片段长度都有上限 —— 检索结果会进入模型上下文，
 *       不加限制会让 token 随文章库增长而失控（M14 已观察到单轮 8000+ token）。</li>
 * </ol>
 *
 * <p><b>查询前缀</b>：bge 系列官方建议给查询侧加检索指令前缀（passage 侧不加）。
 * 前缀由配置 {@code bitforum.ai.rag.query-prefix} 控制，与索引侧的向量计算方式保持一致。
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    /** 一段召回结果：引用来源、片段序号、片段正文（已按上限截断） */
    public record RetrievedChunk(Citation citation, int chunkIndex, String content) {
    }

    /** 一次检索的结果；{@code citations} 已按文章去重并保持召回顺序 */
    public record RetrievalResult(List<RetrievedChunk> chunks, List<Citation> citations, long elapsedMillis) {

        public boolean isEmpty() {
            return chunks.isEmpty();
        }
    }

    private final VectorStore vectorStore;
    private final ArticleMapper articleMapper;
    private final AiKbDocumentMapper documentMapper;

    private final int topK;
    private final double similarityThreshold;
    private final int maxChunkChars;
    private final String queryPrefix;

    public RagService(
            VectorStore vectorStore,
            ArticleMapper articleMapper,
            AiKbDocumentMapper documentMapper,
            @Value("${bitforum.ai.rag.top-k:5}") int topK,
            @Value("${bitforum.ai.rag.similarity-threshold:0.5}") double similarityThreshold,
            @Value("${bitforum.ai.rag.max-chunk-chars:300}") int maxChunkChars,
            @Value("${bitforum.ai.rag.query-prefix:}") String queryPrefix) {
        this.vectorStore = vectorStore;
        this.articleMapper = articleMapper;
        this.documentMapper = documentMapper;
        this.topK = topK;
        this.similarityThreshold = similarityThreshold;
        this.maxChunkChars = maxChunkChars;
        this.queryPrefix = queryPrefix == null ? "" : queryPrefix;
    }

    /**
     * 检索与问题相关的站内文章片段。
     *
     * <p>向量库不可用时会抛出运行时异常，由调用方决定降级策略（问答助手应退化为无检索回答，
     * 而不是整个对话失败）。
     */
    public RetrievalResult retrieve(String query) {
        long startedAt = System.currentTimeMillis();
        if (!StringUtils.hasText(query)) {
            return new RetrievalResult(List.of(), List.of(), 0);
        }

        List<Document> documents = vectorStore.similaritySearch(SearchRequest.builder()
                .query(queryPrefix + query)
                .topK(topK)
                .similarityThreshold(similarityThreshold)
                .filterExpression("status == '" + ArticleService.STATUS_PUBLISHED + "'")
                .build());

        if (documents == null || documents.isEmpty()) {
            return new RetrievalResult(List.of(), List.of(), System.currentTimeMillis() - startedAt);
        }

        Map<Long, Article> publishedArticles = loadPublishedArticles(documents);
        // S1：知识库的一致性策略是"先写向量（Redis）、再写分块与状态（MySQL）"，
        // 后半段失败时向量已经存在，而 ai_kb_document 会停在 FAILED/PENDING。
        // 这些半成品不能作为回答依据，因此这里**按 articleId 批量**确认索引状态 ——
        // 一次 IN 查询，不做逐条 N+1。
        Set<Long> indexedArticleIds = loadIndexedArticleIds(publishedArticles.keySet());
        List<RetrievedChunk> chunks = new ArrayList<>();
        Map<Long, Citation> citations = new LinkedHashMap<>();

        for (Document document : documents) {
            Long articleId = parseArticleId(document);
            if (articleId == null) {
                continue;
            }
            Article article = publishedArticles.get(articleId);
            // 二次校验（查 article 表的**当前**状态，而不是索引时的快照）：
            // 文章下架后如果索引还没重建，Redis 里可能仍留有它的向量，
            // 靠这道校验保证下架内容一定不会被召回。
            if (article == null || !ArticleService.STATUS_PUBLISHED.equals(article.getStatus())) {
                log.debug("召回结果已被二次校验丢弃：articleId={}", articleId);
                continue;
            }
            // 三次校验（S1）：文档必须已经索引完成，半成品索引不得进入回答上下文。
            if (!indexedArticleIds.contains(articleId)) {
                log.debug("召回结果的文档未处于 INDEXED 状态，已丢弃：articleId={}", articleId);
                continue;
            }

            Citation citation = new Citation(articleId, article.getTitle());
            chunks.add(new RetrievedChunk(citation, parseChunkIndex(document), truncate(document.getText())));
            citations.putIfAbsent(articleId, citation);
        }

        long elapsed = System.currentTimeMillis() - startedAt;
        log.info(">>> 知识库检索完成：query=「{}」，召回 {} 段（涉及 {} 篇文章），耗时 {} ms",
                abbreviate(query), chunks.size(), citations.size(), elapsed);

        return new RetrievalResult(chunks, List.copyOf(citations.values()), elapsed);
    }

    /** 批量取出召回文章的最新状态与标题（article 表是状态的唯一权威来源）。 */
    private Map<Long, Article> loadPublishedArticles(List<Document> documents) {
        Set<Long> articleIds = documents.stream()
                .map(this::parseArticleId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        if (articleIds.isEmpty()) {
            return Map.of();
        }
        return articleMapper
                .selectList(new LambdaQueryWrapper<Article>()
                        .in(Article::getId, articleIds)
                        .eq(Article::getStatus, ArticleService.STATUS_PUBLISHED))
                .stream()
                .collect(Collectors.toMap(Article::getId, article -> article, (left, right) -> left));
    }

    /**
     * 批量取出"索引已完成"的文章 id（S1）。
     *
     * <p>只查 {@code article_id} 一列、且用一次 {@code IN} 查询完成，
     * 避免按召回条数逐条查 {@code ai_kb_document}。
     */
    private Set<Long> loadIndexedArticleIds(Collection<Long> articleIds) {
        if (articleIds == null || articleIds.isEmpty()) {
            return Set.of();
        }
        return documentMapper.selectList(new LambdaQueryWrapper<AiKbDocument>()
                        .select(AiKbDocument::getArticleId)
                        .in(AiKbDocument::getArticleId, articleIds)
                        .eq(AiKbDocument::getIndexStatus, AiKbDocument.INDEX_STATUS_INDEXED))
                .stream()
                .map(AiKbDocument::getArticleId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private Long parseArticleId(Document document) {
        Object value = document.getMetadata().get("articleId");
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException exception) {
            log.warn("召回结果的 articleId 不是合法数字：{}", value);
            return null;
        }
    }

    private int parseChunkIndex(Document document) {
        Object value = document.getMetadata().get("chunkIndex");
        if (value == null) {
            return 0;
        }
        try {
            return (int) Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxChunkChars ? text : text.substring(0, maxChunkChars) + "…";
    }

    private String abbreviate(String query) {
        return query.length() <= 30 ? query : query.substring(0, 30) + "…";
    }
}

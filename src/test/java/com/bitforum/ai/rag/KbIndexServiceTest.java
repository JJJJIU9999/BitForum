package com.bitforum.ai.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bitforum.ai.entity.AiKbChunk;
import com.bitforum.ai.entity.AiKbDocument;
import com.bitforum.ai.mapper.AiKbChunkMapper;
import com.bitforum.ai.mapper.AiKbDocumentMapper;
import com.bitforum.entity.Article;
import com.bitforum.mapper.ArticleMapper;
import com.bitforum.service.ArticleService;

/**
 * 知识库索引服务集成测试（M15）。
 *
 * <p>覆盖：索引写入与可召回、指纹未变跳过、内容变化重建、下架即移除、
 * 以及**验收标准「全量重建后统计数与已发布文章数一致」**。
 *
 * <p><b>为什么不用 {@code @Transactional} 回滚</b>：向量写入发生在 Redis，
 * 数据库事务回滚不会撤销它，反而会留下「MySQL 无记录、Redis 有向量」的孤儿向量。
 * 因此这里显式创建测试数据并在 {@link AfterEach} 中删除，
 * 保证数据库与向量库同时回到干净状态。
 *
 * <p><b>副作用说明</b>：全量重建会把库中所有已发布文章写入索引，收尾时会清空知识库
 * （删除文档记录与向量）。知识库本身是可重建的，需要时调用
 * {@code POST /api/admin/ai/kb/rebuild} 即可恢复。
 */
@SpringBootTest
class KbIndexServiceTest {

    private static final Logger log = LoggerFactory.getLogger(KbIndexServiceTest.class);

    private static final Long AUTHOR_ID = 88601L;
    private static final Long CATEGORY_ID = 1L;

    @Autowired
    private KbIndexService kbIndexService;
    @Autowired
    private ArticleMapper articleMapper;
    @Autowired
    private AiKbDocumentMapper documentMapper;
    @Autowired
    private AiKbChunkMapper chunkMapper;
    @Autowired
    private VectorStore vectorStore;

    private final List<Long> createdArticleIds = new ArrayList<>();

    @AfterEach
    void cleanUpIndexAndArticles() {
        for (Long articleId : createdArticleIds) {
            kbIndexService.removeArticle(articleId);
            articleMapper.deleteById(articleId);
        }
        createdArticleIds.clear();

        // 全量重建会为库中既有文章建立索引；收尾清空，避免 MySQL 记录与 Redis 向量的状态不一致
        for (AiKbDocument document : documentMapper.selectList(null)) {
            kbIndexService.removeArticle(document.getArticleId());
        }
    }

    @Test
    void shouldIndexPublishedArticleAndMakeItRetrievable() {
        Article article = createPublishedArticle(
                "向量检索演示说明",
                "石榴石检索法则是本站用于演示语义检索的专用术语。它描述了把文章切成小段后，"
                        + "用本地模型把每段转成向量，再按相似度召回相关内容的完整流程。");

        KbIndexService.IndexOutcome outcome = kbIndexService.indexArticle(article);

        assertEquals(KbIndexService.IndexResult.INDEXED, outcome.result(), "已发布文章应被成功索引");
        assertTrue(outcome.chunkCount() >= 1, "至少应产出一个分块");

        AiKbDocument document = findDocument(article.getId());
        assertNotNull(document, "应写入知识库文档记录");
        assertEquals(AiKbDocument.INDEX_STATUS_INDEXED, document.getIndexStatus());
        assertEquals(outcome.chunkCount(), document.getChunkCount());
        assertEquals("bge-base-zh-v1.5", document.getEmbeddingModel());

        List<AiKbChunk> chunks = chunkMapper.selectList(
                new LambdaQueryWrapper<AiKbChunk>().eq(AiKbChunk::getDocumentId, document.getId()));
        assertEquals(document.getChunkCount(), chunks.size(), "分块表记录数应与文档记录的分块数一致");
        assertTrue(chunks.stream().allMatch(chunk -> chunk.getVectorId() != null && !chunk.getVectorId().isBlank()));

        List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                .query("石榴石检索法则是什么")
                .topK(5)
                .filterExpression("status == 'PUBLISHED'")
                .build());

        assertTrue(
                hits.stream().anyMatch(hit -> String.valueOf(article.getId()).equals(hit.getMetadata().get("articleId"))),
                "新索引的文章应能被检索召回，实际命中：" + hits.stream()
                        .map(hit -> hit.getMetadata().get("articleId"))
                        .toList());
    }

    @Test
    void shouldSkipReindexWhenContentAndModelUnchanged() {
        Article article = createPublishedArticle("指纹跳过测试", "内容没有变化时不应该重复计算向量。");

        assertEquals(KbIndexService.IndexResult.INDEXED, kbIndexService.indexArticle(article).result());

        KbIndexService.IndexOutcome second = kbIndexService.indexArticle(article);
        assertEquals(KbIndexService.IndexResult.SKIPPED, second.result(), "内容与模型均未变化时应跳过重新索引");
    }

    @Test
    void shouldReindexWhenContentChanges() {
        Article article = createPublishedArticle("内容变化测试", "第一版内容：只讲了发布流程。");
        assertEquals(KbIndexService.IndexResult.INDEXED, kbIndexService.indexArticle(article).result());

        article.setContent("第二版内容：补充了评论功能与举报入口的说明。");
        articleMapper.updateById(article);

        KbIndexService.IndexOutcome outcome = kbIndexService.indexArticle(article);
        assertEquals(KbIndexService.IndexResult.INDEXED, outcome.result(), "内容变化后必须重新索引");

        List<AiKbChunk> chunks = chunkMapper.selectList(new LambdaQueryWrapper<AiKbChunk>()
                .eq(AiKbChunk::getArticleId, article.getId()));
        assertTrue(
                chunks.stream().allMatch(chunk -> chunk.getContent().contains("第二版内容")),
                "重建后不应残留旧内容的向量记录");
    }

    @Test
    void shouldRemoveIndexWhenArticleIsNoLongerPublished() {
        Article article = createPublishedArticle("下架移除测试", "这篇文章稍后会被下架，它的向量不应继续被召回。");
        assertEquals(KbIndexService.IndexResult.INDEXED, kbIndexService.indexArticle(article).result());
        assertNotNull(findDocument(article.getId()));

        article.setStatus(ArticleService.STATUS_OFFLINE);
        articleMapper.updateById(article);

        KbIndexService.IndexOutcome outcome = kbIndexService.indexArticle(article);
        assertEquals(KbIndexService.IndexResult.REMOVED, outcome.result(), "非已发布状态的文章应被移出知识库");
        assertNull(findDocument(article.getId()), "文档记录应被删除");
        assertEquals(
                0,
                chunkMapper.selectCount(new LambdaQueryWrapper<AiKbChunk>()
                        .eq(AiKbChunk::getArticleId, article.getId())),
                "分块记录应被删除");
    }

    /** 计划书验收标准：知识库统计数与已发布文章数一致。 */
    @Test
    void shouldKeepStatsConsistentWithPublishedArticlesAfterFullRebuild() {
        createPublishedArticle(
                "全量重建测试",
                "全量重建会遍历所有已发布文章，未变化的文章按指纹跳过，保证统计口径与已发布文章数一致。");

        KbIndexService.RebuildResult result = kbIndexService.rebuildAll();
        KbIndexService.KbStats stats = kbIndexService.stats();

        log.info(">>> 全量重建结果 = {}；统计 = {}", result, stats);

        assertTrue(result.publishedArticles() >= 1, "测试库中应至少有一篇已发布文章");
        assertEquals(
                result.publishedArticles(),
                result.indexed() + result.skipped(),
                "每篇已发布文章要么被索引、要么按指纹跳过，不应有第三种结果");
        assertEquals(0, result.failed(), "全量重建不应出现失败：" + result);
        assertEquals(
                stats.publishedArticles(),
                stats.indexedDocuments(),
                "已索引文档数必须等于已发布文章数（验收标准）");
        assertTrue(stats.chunks() >= stats.indexedDocuments(), "每个已索引文档至少应有一个分块");
    }

    private Article createPublishedArticle(String title, String content) {
        Article article = new Article();
        article.setTitle(title);
        article.setContent(content);
        article.setUserId(AUTHOR_ID);
        article.setCategoryId(CATEGORY_ID);
        article.setStatus(ArticleService.STATUS_PUBLISHED);
        article.setCreateTime(LocalDateTime.now());
        article.setUpdateTime(LocalDateTime.now());
        articleMapper.insert(article);
        createdArticleIds.add(article.getId());
        return article;
    }

    private AiKbDocument findDocument(Long articleId) {
        return documentMapper.selectOne(
                new LambdaQueryWrapper<AiKbDocument>().eq(AiKbDocument::getArticleId, articleId));
    }
}

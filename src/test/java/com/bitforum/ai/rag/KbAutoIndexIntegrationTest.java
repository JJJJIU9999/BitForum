package com.bitforum.ai.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
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
import com.bitforum.entity.ArticleAuditRecord;
import com.bitforum.mapper.ArticleAuditRecordMapper;
import com.bitforum.mapper.ArticleMapper;
import com.bitforum.service.ArticleService;

/**
 * 知识库自动索引端到端测试（M15）。
 *
 * <p>验证完整链路：**文章审核通过 → RabbitMQ → 监听器 → 分块向量化入库 → 可被检索**，
 * 也就是 findings.md 待验证事项 T8「异步索引队列与既有文章发布队列不冲突」的实证。
 * 下架则验证反向链路：文章离开已发布状态后自动从知识库移除。
 *
 * <p>异步过程用轮询等待（最多 15 秒），避免依赖固定的 sleep 时长。
 */
@SpringBootTest
class KbAutoIndexIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(KbAutoIndexIntegrationTest.class);

    private static final Long AUDITOR_ID = 88701L;
    private static final Long AUTHOR_ID = 88702L;
    private static final Long CATEGORY_ID = 1L;
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Autowired
    private ArticleService articleService;
    @Autowired
    private KbIndexService kbIndexService;
    @Autowired
    private ArticleMapper articleMapper;
    @Autowired
    private ArticleAuditRecordMapper auditRecordMapper;
    @Autowired
    private AiKbDocumentMapper documentMapper;
    @Autowired
    private AiKbChunkMapper chunkMapper;
    @Autowired
    private VectorStore vectorStore;

    private final List<Long> createdArticleIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long articleId : createdArticleIds) {
            kbIndexService.removeArticle(articleId);
            auditRecordMapper.delete(
                    new LambdaQueryWrapper<ArticleAuditRecord>().eq(ArticleAuditRecord::getArticleId, articleId));
            articleMapper.deleteById(articleId);
        }
        createdArticleIds.clear();

        // 清理可能由其它测试留下的知识库记录，避免 MySQL 与 Redis 状态不一致
        for (AiKbDocument document : documentMapper.selectList(null)) {
            kbIndexService.removeArticle(document.getArticleId());
        }
    }

    @Test
    void approvingArticleShouldIndexItAsynchronouslyAndMakeItRetrievable() {
        Article article = insertArticle(
                ArticleService.STATUS_PENDING,
                "石榴石索引链路测试",
                "石榴石索引链路是本站用于验证异步索引的专用术语。它描述了文章审核通过之后，"
                        + "系统如何通过消息队列自动把文章切块并写入知识库，全过程无需人工干预。");

        articleService.approve(article.getId(), AUDITOR_ID);

        AiKbDocument document = awaitIndexed(article.getId());
        assertNotNull(document, "审核通过后应在超时前被异步写入知识库");
        assertEquals(AiKbDocument.INDEX_STATUS_INDEXED, document.getIndexStatus());
        assertTrue(document.getChunkCount() >= 1, "入库文档至少应有一个分块");
        assertEquals(
                document.getChunkCount().intValue(),
                chunkMapper
                        .selectCount(new LambdaQueryWrapper<AiKbChunk>().eq(AiKbChunk::getDocumentId, document.getId()))
                        .intValue(),
                "分块记录数应与文档记录一致");

        List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                .query("石榴石索引链路是怎么工作的")
                .topK(5)
                .filterExpression("status == 'PUBLISHED'")
                .build());
        assertTrue(
                hits.stream().anyMatch(hit -> String.valueOf(article.getId()).equals(hit.getMetadata().get("articleId"))),
                "自动入库的文章应能被检索召回，实际命中：" + hits.stream()
                        .map(hit -> hit.getMetadata().get("articleId"))
                        .toList());

        log.info(">>> 自动索引成功：articleId={}，分块 {} 个", article.getId(), document.getChunkCount());
    }

    @Test
    void offliningArticleShouldRemoveItFromKnowledgeBaseAsynchronously() {
        Article article = insertArticle(
                ArticleService.STATUS_PENDING,
                "辉石下架链路测试",
                "辉石下架链路是本站用于验证异步移除索引的专用术语，这篇文章稍后会被下架。");

        articleService.approve(article.getId(), AUDITOR_ID);
        assertNotNull(awaitIndexed(article.getId()), "前置条件：文章应先被自动索引");

        articleService.offline(article.getId(), AUDITOR_ID, "端到端测试下架");

        AiKbDocument document = awaitRemoved(article.getId());
        assertNull(document, "下架后应自动把文章移出知识库");
        assertTrue(
                vectorStore
                        .similaritySearch(SearchRequest.builder()
                                .query("辉石下架链路")
                                .topK(5)
                                .filterExpression("status == 'PUBLISHED'")
                                .build())
                        .stream()
                        .noneMatch(hit -> String.valueOf(article.getId()).equals(hit.getMetadata().get("articleId"))),
                "下架文章不应再被检索到");
    }

    /** 轮询等待文档进入 INDEXED 状态。 */
    private AiKbDocument awaitIndexed(Long articleId) {
        long deadline = System.currentTimeMillis() + TIMEOUT.toMillis();
        AiKbDocument document = null;
        while (System.currentTimeMillis() < deadline) {
            document = findDocument(articleId);
            if (document != null && AiKbDocument.INDEX_STATUS_INDEXED.equals(document.getIndexStatus())) {
                return document;
            }
            sleep();
        }
        return document != null
                && AiKbDocument.INDEX_STATUS_INDEXED.equals(document.getIndexStatus()) ? document : null;
    }

    /** 轮询等待文档记录被删除。 */
    private AiKbDocument awaitRemoved(Long articleId) {
        long deadline = System.currentTimeMillis() + TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            AiKbDocument document = findDocument(articleId);
            if (document == null) {
                return null;
            }
            sleep();
        }
        return findDocument(articleId);
    }

    private void sleep() {
        try {
            Thread.sleep(200L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待异步索引时被中断", exception);
        }
    }

    private AiKbDocument findDocument(Long articleId) {
        return documentMapper.selectOne(
                new LambdaQueryWrapper<AiKbDocument>().eq(AiKbDocument::getArticleId, articleId));
    }

    private Article insertArticle(String status, String title, String content) {
        Article article = new Article();
        article.setTitle(title);
        article.setContent(content);
        article.setUserId(AUTHOR_ID);
        article.setCategoryId(CATEGORY_ID);
        article.setStatus(status);
        article.setCreateTime(LocalDateTime.now());
        article.setUpdateTime(LocalDateTime.now());
        articleMapper.insert(article);
        createdArticleIds.add(article.getId());
        return article;
    }
}

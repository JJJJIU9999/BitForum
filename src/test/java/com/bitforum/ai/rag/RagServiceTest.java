package com.bitforum.ai.rag;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.bitforum.ai.entity.AiKbDocument;
import com.bitforum.ai.mapper.AiKbDocumentMapper;
import com.bitforum.entity.Article;
import com.bitforum.mapper.ArticleMapper;
import com.bitforum.service.ArticleService;

/**
 * 检索服务集成测试（M15）。
 *
 * <p>覆盖三件事：能召回相关已发布文章、**下架文章一定不被召回**（哪怕索引尚未重建）、
 * 空查询不触发检索。
 *
 * <p>与 {@code KbIndexServiceTest} 相同的清理策略：显式创建、显式删除，
 * 收尾时清空知识库（知识库可随时通过重建接口恢复）。
 */
@SpringBootTest
class RagServiceTest {

    private static final Long AUTHOR_ID = 88701L;
    private static final Long CATEGORY_ID = 1L;

    /** 单个片段注入前的截断长度（与 application.yml 的 max-chunk-chars 默认值一致） */
    private static final int MAX_CHUNK_CHARS = 300;

    @Autowired
    private RagService ragService;
    @Autowired
    private KbIndexService kbIndexService;
    @Autowired
    private ArticleMapper articleMapper;
    @Autowired
    private AiKbDocumentMapper documentMapper;

    private final List<Long> createdArticleIds = new ArrayList<>();

    @AfterEach
    void cleanUpIndexAndArticles() {
        for (Long articleId : createdArticleIds) {
            kbIndexService.removeArticle(articleId);
            articleMapper.deleteById(articleId);
        }
        createdArticleIds.clear();

        for (AiKbDocument document : documentMapper.selectList(null)) {
            kbIndexService.removeArticle(document.getArticleId());
        }
    }

    @Test
    void shouldRetrieveRelevantPublishedArticle() {
        Article article = createPublishedArticle(
                "玄武岩缓存策略说明",
                "玄武岩缓存策略是本站用于演示检索链路的专用术语。它描述了热点数据如何写入本地缓存，"
                        + "以及在缓存失效时回源数据库，避免缓存击穿导致数据库压力骤增。");

        kbIndexService.indexArticle(article);

        RagService.RetrievalResult result = ragService.retrieve("玄武岩缓存策略是怎么处理缓存失效的");

        assertFalse(result.isEmpty(), "相关文章应被召回");
        assertTrue(
                result.citations().stream().anyMatch(citation -> article.getId().equals(citation.articleId())),
                "引用来源应包含被索引的文章，实际：" + result.citations());
        assertTrue(
                result.citations().stream()
                        .filter(citation -> article.getId().equals(citation.articleId()))
                        .allMatch(citation -> "玄武岩缓存策略说明".equals(citation.title())),
                "引用应带文章标题，供前端渲染可点击链接");
        assertTrue(
                result.chunks().stream().allMatch(chunk -> chunk.content().length() <= MAX_CHUNK_CHARS + 1),
                "单个片段注入前必须按上限截断，避免上下文失控");
    }

    /** 关键兜底：文章下架但索引消息尚未消费时，也绝不能被召回。 */
    @Test
    void shouldNotRetrieveArticleThatWentOfflineBeforeReindex() {
        Article article = createPublishedArticle(
                "变质岩检索兜底测试",
                "这段内容用于验证：文章下架之后，即使知识库索引还没来得及重建，也不能被检索召回。");
        kbIndexService.indexArticle(article);

        // 只改 article 表的状态，不调用索引服务，模拟"下架消息还没被处理"
        article.setStatus(ArticleService.STATUS_OFFLINE);
        articleMapper.updateById(article);

        RagService.RetrievalResult result = ragService.retrieve("变质岩检索兜底测试讲了什么");

        assertTrue(
                result.chunks().stream()
                        .noneMatch(chunk -> article.getId().equals(chunk.citation().articleId())),
                "已下架文章不应出现在检索结果中");
        assertTrue(
                result.citations().stream()
                        .noneMatch(citation -> article.getId().equals(citation.articleId())),
                "已下架文章不应出现在引用来源中");
    }

    @Test
    void blankQueryShouldReturnEmptyWithoutTouchingVectorStore() {
        assertTrue(ragService.retrieve("   ").isEmpty(), "空白提问不应触发检索");
        assertTrue(ragService.retrieve(null).isEmpty(), "null 提问不应触发检索");
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
}

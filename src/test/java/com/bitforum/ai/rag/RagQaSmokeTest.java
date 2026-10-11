package com.bitforum.ai.rag;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.bitforum.ai.agent.AgentContext;
import com.bitforum.ai.agent.AgentResponse;
import com.bitforum.ai.agent.QaAgent;
import com.bitforum.ai.entity.AiKbDocument;
import com.bitforum.ai.mapper.AiKbDocumentMapper;
import com.bitforum.entity.Article;
import com.bitforum.mapper.ArticleMapper;
import com.bitforum.service.ArticleService;

/**
 * RAG 真实链路冒烟测试（M15）。
 *
 * <p>默认不执行：仅当 DEEPSEEK_CHAT_ENABLED=true 时运行，避免常规回归消耗 API 额度。
 * 它验证 mock 覆盖不到的部分：**检索结果真的进入了模型上下文，回答里真的带上了引用**，
 * 也就是计划书对 M15 的验收标准「提问能召回正确帖子且回答带引用链接」。
 *
 * <p>运行方式（用 .env 中的真实 Key）：
 *
 * <pre>
 *   export DEEPSEEK_CHAT_ENABLED=true
 *   export DEEPSEEK_API_KEY="$(grep '^DEEPSEEK_API_KEY=' .env | cut -d= -f2-)"
 *   ./mvnw -Dtest=RagQaSmokeTest test
 * </pre>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_CHAT_ENABLED", matches = "true")
class RagQaSmokeTest {

    private static final Logger log = LoggerFactory.getLogger(RagQaSmokeTest.class);

    private static final Long AUTHOR_ID = 88801L;
    private static final Long CATEGORY_ID = 1L;

    @Autowired
    private QaAgent qaAgent;
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
    void shouldAnswerStationQuestionWithCitations() {
        Article article = createPublishedArticle(
                "玄武岩缓存策略说明",
                "玄武岩缓存策略是本站用于演示检索链路的专用术语。它规定热点数据先写入本地缓存，"
                        + "缓存失效时回源数据库并重新填充；同时使用随机过期时间避免同一时刻大量缓存同时失效。");

        kbIndexService.indexArticle(article);

        AgentResponse response = qaAgent.execute(
                new AgentContext(AUTHOR_ID, 0L, "站内的玄武岩缓存策略是怎么处理缓存失效的？"), List.of());

        assertNotNull(response, "Agent 应返回结果");
        assertFalse(response.degraded(), "真实调用不应走降级链路：" + response.content());
        assertNotNull(response.content(), "回答正文不应为空");
        assertTrue(response.content().length() > 10, "回答内容过短，实际：" + response.content());

        assertTrue(
                response.citations().stream().anyMatch(citation -> article.getId().equals(citation.articleId())),
                "回答应引用被索引的站内文章，实际引用：" + response.citations());

        log.info(">>> RAG 回答（token 合计 {}）= {}", response.totalTokens(), response.content());
        log.info(">>> RAG 引用来源 = {}", response.citations());
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

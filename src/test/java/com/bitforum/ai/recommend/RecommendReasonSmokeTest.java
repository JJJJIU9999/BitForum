package com.bitforum.ai.recommend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.bitforum.ai.recommend.RecommendService.RecommendedArticle;

/**
 * 推荐理由生成的真实调用冒烟测试（M17）。
 *
 * <p>T9 探针（findings.md 6.13）验证的是"让模型为不定长列表写结构化理由"这一**通用能力**，
 * 结论是选用"应用定序、模型只按序号填理由"的方案。本测试对**真实实现**
 * {@link RecommendReasonAgent} 复验：模型能否为给定的每一条都写出可用的理由、
 * 序号是否严格对应、有没有漏条。
 *
 * <p>输入刻意用与线上同构的推荐列表（带真实召回来源与分数），
 * 但断言只针对**结构性要求**（条数、非空、长度），不对文案质量做强断言 ——
 * 文案好坏需要人工阅读，测试只保证"不会缺条、不会串位"。
 *
 * <p>默认不执行（消耗真实 API 额度）：
 *
 * <pre>
 *   export DEEPSEEK_CHAT_ENABLED=true
 *   export DEEPSEEK_API_KEY="$(grep '^DEEPSEEK_API_KEY=' .env | cut -d= -f2-)"
 *   ./mvnw -s maven-settings.xml -Dtest=RecommendReasonSmokeTest test
 * </pre>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_CHAT_ENABLED", matches = "true")
class RecommendReasonSmokeTest {

    private static final Logger log = LoggerFactory.getLogger(RecommendReasonSmokeTest.class);

    /** 与融合结果同构的推荐列表：序号、标题、板块、召回来源与分数都是"系统算出来的"。 */
    private static final List<RecommendedArticle> ARTICLES = List.of(
            new RecommendedArticle(87L, "Redis 热点数据同步方案", 16L, "技术",
                    1, 0.0325, List.of("vector", "hot"), "{\"vector\":1,\"hot\":2}", null),
            new RecommendedArticle(85L, "Spring Boot 论坛项目实践", 16L, "技术",
                    2, 0.0164, List.of("vector"), "{\"vector\":2}", null),
            new RecommendedArticle(749L, "Spring 和 Spring Boot 到底是什么关系？一篇讲清", 16L, "技术",
                    3, 0.0161, List.of("hot"), "{\"hot\":1}", null),
            new RecommendedArticle(86L, "React 与 Vite 前端开发笔记", 17L, "前端",
                    4, 0.0159, List.of("follow"), "{\"follow\":1}", null),
            new RecommendedArticle(88L, "社区使用指南", 18L, "公告",
                    5, 0.0156, List.of("hot"), "{\"hot\":3}", null));

    private static final String LOGGED_IN_PROFILE =
            "已登录用户，系统已结合其收藏与关注关系做个性化（可能出现\"来自你关注的作者\"这类依据）";

    private static final String ANONYMOUS_PROFILE =
            "未登录访客，没有个人行为数据（只会有内容相似与社区热度两类依据）";

    @Autowired
    private ObjectProvider<RecommendReasonAgent> reasonAgentProvider;

    @Test
    void shouldGenerateOneReasonPerRecommendedArticle() {
        RecommendReasonAgent agent = reasonAgentProvider.getIfAvailable();
        assertNotNull(agent, "RecommendReasonAgent 不可用（检查 spring.ai.deepseek.chat.enabled）");

        RecommendReasonAgent.ReasonOutcome outcome = agent.generate(ARTICLES, LOGGED_IN_PROFILE);

        assertFalse(outcome.degraded(), "理由生成不应降级：" + outcome.errorMessage());
        assertEquals(ARTICLES.size(), outcome.reasonsByArticleId().size(),
                "应为每一条推荐都生成理由，实际：" + outcome.reasonsByArticleId());

        for (RecommendedArticle article : ARTICLES) {
            String reason = outcome.reasonFor(article.articleId());
            assertNotNull(reason, "文章 " + article.articleId() + " 缺少理由（序号可能串位）");
            assertFalse(reason.isBlank());
            assertTrue(reason.length() <= 60,
                    "理由过长（" + reason.length() + " 字）：" + reason);
        }

        log.info(">>> 推荐理由冒烟结果（本次调用 {} ms）：", outcome.latencyMillis());
        ARTICLES.forEach(article -> log.info("    {}. 《{}》 → {}", article.rank(), article.title(),
                outcome.reasonFor(article.articleId())));
    }

    /**
     * 匿名访客也要能拿到理由。
     *
     * <p>刻意用一份**不含 follow 来源**的列表：真实链路里匿名用户不召回关注通道，
     * 如果用带 follow 的列表去测，模型会写出"来自你关注的作者"这种匿名场景下不该出现的理由 ——
     * 那是测试数据不真实，不是模型的问题。
     */
    @Test
    void shouldGenerateReasonsForAnonymousVisitor() {
        RecommendReasonAgent agent = reasonAgentProvider.getIfAvailable();
        assertNotNull(agent);

        List<RecommendedArticle> anonymousList = ARTICLES.stream()
                .map(article -> new RecommendedArticle(article.articleId(), article.title(),
                        article.categoryId(), article.categoryName(), article.rank(), article.score(),
                        article.sources().stream().filter(source -> !"follow".equals(source)).toList(),
                        article.scoreDetail(), null))
                .toList();

        RecommendReasonAgent.ReasonOutcome outcome = agent.generate(anonymousList, ANONYMOUS_PROFILE);

        assertFalse(outcome.degraded(), "匿名场景不应降级：" + outcome.errorMessage());
        assertEquals(anonymousList.size(), outcome.reasonsByArticleId().size());

        log.info(">>> 匿名访客的理由：");
        anonymousList.forEach(article -> log.info("    {}. 《{}》 → {}", article.rank(), article.title(),
                outcome.reasonFor(article.articleId())));
    }
}

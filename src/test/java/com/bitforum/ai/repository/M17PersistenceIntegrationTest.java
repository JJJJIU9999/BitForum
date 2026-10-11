package com.bitforum.ai.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bitforum.ai.entity.AiInsightReport;
import com.bitforum.ai.entity.AiRecommendLog;
import com.bitforum.ai.mapper.AiInsightReportMapper;
import com.bitforum.ai.mapper.AiRecommendLogMapper;

/**
 * M17 新增两张表的持久化验证（V16 / V17）。
 *
 * <p>这个测试的存在理由不是"跑一遍 mapper"，而是**把 Flyway 迁移与 Java 实体之间的列映射钉住**：
 * 迁移里的列名（{@code rank_no}、{@code hit}、{@code score_detail}、{@code degraded}）
 * 一旦与实体字段对不上，MyBatis-Plus 生成 SQL 时才会报错，而那时往往已经是功能写完、
 * 手工点页面才发现的阶段。这里用真实数据库（不是 mock）提前暴露。
 *
 * <p>{@code @Transactional} 让测试结束自动回滚，不污染本地库。
 */
@SpringBootTest
@Transactional
class M17PersistenceIntegrationTest {

    @Autowired
    private AiInsightReportMapper insightReportMapper;

    @Autowired
    private AiRecommendLogMapper recommendLogMapper;

    @Test
    void shouldRoundTripInsightReportIncludingSnapshot() {
        AiInsightReport report = new AiInsightReport();
        report.setStatus(AiInsightReport.STATUS_SUCCESS);
        report.setTriggerType(AiInsightReport.TRIGGER_MANUAL);
        report.setRequestedBy(13L);
        report.setContent("社区处于起步阶段：注册用户 5 人，已发布文章 5 篇……\n\n建议：先清空审核积压。");
        report.setDataSnapshot("{\"userStats\":{\"total\":5},\"articleStats\":{\"published\":5}}");
        report.setDataTime(LocalDateTime.now());
        report.setModel("deepseek-flash");
        report.setPromptTokens(1553);
        report.setCompletionTokens(715);
        report.setTotalTokens(2268);
        report.setLatencyMs(4780);

        assertEquals(1, insightReportMapper.insert(report));
        assertNotNull(report.getId(), "自增主键应回填");

        AiInsightReport loaded = insightReportMapper.selectById(report.getId());
        assertNotNull(loaded);
        assertEquals(AiInsightReport.STATUS_SUCCESS, loaded.getStatus());
        assertEquals(AiInsightReport.TRIGGER_MANUAL, loaded.getTriggerType());
        assertEquals(13L, loaded.getRequestedBy());
        // 正文与快照必须原样读回：报告要能和当时的统计对账
        assertTrue(loaded.getContent().contains("建议"));
        assertTrue(loaded.getDataSnapshot().contains("articleStats"));
        assertEquals(1553, loaded.getPromptTokens());
        assertEquals(4780, loaded.getLatencyMs());
        assertNotNull(loaded.getCreateTime(), "create_time 有默认值，应自动填充");
    }

    @Test
    void shouldRoundTripRecommendLogIncludingRankAndHitFlag() {
        AiRecommendLog log = new AiRecommendLog();
        log.setScene(AiRecommendLog.SCENE_ARTICLE_DETAIL);
        log.setUserId(13L);
        log.setSourceArticleId(87L);
        log.setArticleId(85L);
        log.setRankNo(1);
        // 六位小数刻意写满：列是 DECIMAL(12,6)，精度不能悄悄被截断
        log.setScore(new BigDecimal("0.732150"));
        log.setRecallSources("vector,hot");
        log.setScoreDetail("{\"vector\":0.7131,\"hot\":3.0}");
        log.setReason("你收藏过同主题文章，且该文属于后端方向");
        log.setExperimentTag(AiRecommendLog.TAG_RECOMMEND);
        log.setHit(false);
        log.setModel("deepseek-flash");
        log.setLatencyMs(1800);
        log.setDegraded(false);

        assertEquals(1, recommendLogMapper.insert(log));
        assertNotNull(log.getId());

        AiRecommendLog loaded = recommendLogMapper.selectById(log.getId());
        assertNotNull(loaded);
        assertEquals(AiRecommendLog.SCENE_ARTICLE_DETAIL, loaded.getScene());
        assertEquals(13L, loaded.getUserId());
        assertEquals(87L, loaded.getSourceArticleId());
        assertEquals(85L, loaded.getArticleId());
        // rank_no 是保留字规避列：映射错了这里会直接抛 SQL 异常
        assertEquals(1, loaded.getRankNo());
        assertEquals(0, new BigDecimal("0.732150").compareTo(loaded.getScore()),
                "score 精度丢失或列长度不足");
        assertEquals("vector,hot", loaded.getRecallSources());
        assertTrue(loaded.getScoreDetail().contains("vector"));
        assertFalse(loaded.getHit(), "hit 应为 false 而不是 null");
        assertEquals(AiRecommendLog.TAG_RECOMMEND, loaded.getExperimentTag());
        assertFalse(loaded.getDegraded());
        assertNotNull(loaded.getCreateTime());
    }

    /** 评估要靠 experiment_tag 分组取数，这条查询必须先能走通。 */
    @Test
    void shouldQueryByExperimentTagForEvaluation() {
        AiRecommendLog baseline = new AiRecommendLog();
        baseline.setScene(AiRecommendLog.SCENE_ADMIN);
        baseline.setArticleId(749L);
        baseline.setRankNo(1);
        baseline.setScore(new BigDecimal("1.000000"));
        baseline.setRecallSources("hot");
        baseline.setExperimentTag("BASELINE_HOT_TEST");
        baseline.setDegraded(true);
        recommendLogMapper.insert(baseline);

        List<AiRecommendLog> found = recommendLogMapper.selectList(
                new LambdaQueryWrapper<AiRecommendLog>()
                        .eq(AiRecommendLog::getExperimentTag, "BASELINE_HOT_TEST")
                        .orderByAsc(AiRecommendLog::getRankNo));

        assertEquals(1, found.size());
        assertEquals(749L, found.get(0).getArticleId());
        assertEquals("hot", found.get(0).getRecallSources());
        assertTrue(found.get(0).getDegraded(), "降级标记应能读回（基线不生成理由）");
    }
}

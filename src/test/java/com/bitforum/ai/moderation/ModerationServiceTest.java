package com.bitforum.ai.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bitforum.ai.entity.AiModerationRecord;
import com.bitforum.ai.mapper.AiModerationRecordMapper;
import com.bitforum.ai.trace.TraceDegradeReason;
import com.bitforum.service.ArticleService;

/**
 * 审核动作规则测试（M16）。
 *
 * <p>本测试是 M16 三条核心决策的**可执行规格**：
 *
 * <ol>
 *   <li><b>Decision 与 Action 解耦</b>：同样的 PASS，在开关关/开时对应不同动作；</li>
 *   <li><b>自动 PASS 但不自动 REJECT</b>：任何 REJECT 都必须转人工，绝不调用驳回/删除；</li>
 *   <li><b>阈值不预设</b>：默认配置（confidence ≥ 1 且 risk &lt; 0）下永不自动放行。</li>
 * </ol>
 *
 * <p>用纯 Mockito 单元测试而不是集成测试：这里验证的是**纯规则逻辑**，
 * 不涉及数据库与模型调用，跑得快且能穷举各种阈值组合。
 */
@ExtendWith(MockitoExtension.class)
class ModerationServiceTest {

    private static final Long TARGET_ID = 1001L;
    private static final Long AUTHOR_ID = 2002L;
    private static final String CONTENT = "这是一段用于测试的普通内容";

    @Mock
    private ModerationAgent moderationAgent;
    @Mock
    private AiModerationRecordMapper recordMapper;
    @Mock
    private ArticleService articleService;

    // ==================== 决策 2：绝不自动驳回 ====================

    @Test
    void rejectDecisionShouldNeverAutoRejectOrDeleteContent() {
        when(moderationAgent.analyze(ModerationTargetType.ARTICLE, CONTENT))
                .thenReturn(outcome(assessment("REJECT", 0.98, 0.95)));

        ModerationService.ModerationResult result =
                service(true, 0.5, 0.9).moderate(ModerationTargetType.ARTICLE, TARGET_ID, CONTENT, AUTHOR_ID);

        assertEquals(ModerationAction.HIGH_PRIORITY_REVIEW, result.action(), "REJECT 必须转高优先级人工复核");
        assertFalse(result.autoApproved(), "REJECT 不得触发任何自动动作");
        // 即使开关全开、阈值极宽松，也绝不能自动放行或自动驳回
        verify(articleService, never()).approve(anyLong(), anyLong());
        verify(articleService, never()).reject(anyLong(), anyLong(), any());
        verify(articleService, never()).deleteByAdmin(anyLong());
    }

    @Test
    void rejectOnCommentShouldCreateHighPriorityRecordWithoutRemoval() {
        when(moderationAgent.analyze(ModerationTargetType.COMMENT, CONTENT))
                .thenReturn(outcome(assessment("REJECT", 0.9, 0.9)));

        ModerationService.ModerationResult result =
                service(false, 1d, 0d).moderate(ModerationTargetType.COMMENT, TARGET_ID, CONTENT, AUTHOR_ID);

        assertEquals(ModerationAction.HIGH_PRIORITY_REVIEW, result.action());
        // 评论发布即公开，AI 不能删除，只能生成待处理记录（此处只落库，不调用删除类接口）
        verify(articleService, never()).deleteByAdmin(anyLong());
    }

    // ==================== 决策 1：决定与动作解耦 ====================

    @Test
    void articlePassShouldStayPendingWhenAutoApproveDisabled() {
        when(moderationAgent.analyze(ModerationTargetType.ARTICLE, CONTENT))
                .thenReturn(outcome(assessment("PASS", 0.99, 0.01)));

        ModerationService.ModerationResult result =
                service(false, 0.5, 0.9).moderate(ModerationTargetType.ARTICLE, TARGET_ID, CONTENT, AUTHOR_ID);

        assertEquals(ModerationDecision.PASS, result.decision(), "AI 判断仍是 PASS");
        assertEquals(ModerationAction.PENDING_REVIEW, result.action(), "但开关关闭时动作是转人工");
        verify(articleService, never()).approve(anyLong(), anyLong());
    }

    @Test
    void articlePassShouldAutoApproveOnlyWhenAllConditionsMet() {
        when(moderationAgent.analyze(ModerationTargetType.ARTICLE, CONTENT))
                .thenReturn(outcome(assessment("PASS", 0.96, 0.05)));
        when(articleService.approve(TARGET_ID, ModerationService.SYSTEM_OPERATOR_ID)).thenReturn(true);

        ModerationService.ModerationResult result =
                service(true, 0.9, 0.2).moderate(ModerationTargetType.ARTICLE, TARGET_ID, CONTENT, AUTHOR_ID);

        assertEquals(ModerationAction.AUTO_APPROVED, result.action());
        assertTrue(result.autoApproved());
        // 自动放行必须以系统操作者 id 走标准审核流程，而不是直接改状态
        verify(articleService).approve(TARGET_ID, ModerationService.SYSTEM_OPERATOR_ID);
    }

    @Test
    void articlePassShouldNotAutoApproveWhenConfidenceTooLow() {
        when(moderationAgent.analyze(ModerationTargetType.ARTICLE, CONTENT))
                .thenReturn(outcome(assessment("PASS", 0.80, 0.05)));

        ModerationService.ModerationResult result =
                service(true, 0.9, 0.2).moderate(ModerationTargetType.ARTICLE, TARGET_ID, CONTENT, AUTHOR_ID);

        assertEquals(ModerationAction.PENDING_REVIEW, result.action(), "置信度不足不得自动放行");
        verify(articleService, never()).approve(anyLong(), anyLong());
    }

    @Test
    void articlePassShouldNotAutoApproveWhenRiskTooHigh() {
        when(moderationAgent.analyze(ModerationTargetType.ARTICLE, CONTENT))
                .thenReturn(outcome(assessment("PASS", 0.99, 0.45)));

        ModerationService.ModerationResult result =
                service(true, 0.9, 0.2).moderate(ModerationTargetType.ARTICLE, TARGET_ID, CONTENT, AUTHOR_ID);

        assertEquals(ModerationAction.PENDING_REVIEW, result.action(), "风险分超标不得自动放行");
        verify(articleService, never()).approve(anyLong(), anyLong());
    }

    // ==================== 决策 3：阈值不预设 ====================

    @Test
    void defaultThresholdsShouldNeverAutoApproveEvenForPerfectScore() {
        // 默认配置：autoApproveConfidence=1.0、autoApproveMaxRisk=0.0。
        // 判定用 risk >= maxRisk 拦截，所以 risk=0 也会被拦下 —— 即使开关误开也不会放行。
        when(moderationAgent.analyze(ModerationTargetType.ARTICLE, CONTENT))
                .thenReturn(outcome(assessment("PASS", 1.0, 0.0)));

        ModerationService.ModerationResult result = service(true, 1.0, 0.0)
                .moderate(ModerationTargetType.ARTICLE, TARGET_ID, CONTENT, AUTHOR_ID);

        assertEquals(ModerationAction.PENDING_REVIEW, result.action(),
                "实验前的占位阈值必须保证永不自动放行");
        verify(articleService, never()).approve(anyLong(), anyLong());
    }

    // ==================== 评论与降级 ====================

    @Test
    void commentPassShouldRequireNoAction() {
        when(moderationAgent.analyze(ModerationTargetType.COMMENT, CONTENT))
                .thenReturn(outcome(assessment("PASS", 0.95, 0.02)));

        ModerationService.ModerationResult result =
                service(true, 0.5, 0.9).moderate(ModerationTargetType.COMMENT, TARGET_ID, CONTENT, AUTHOR_ID);

        assertEquals(ModerationAction.NO_ACTION, result.action(), "评论已公开，PASS 时无需任何动作");
        assertFalse(result.autoApproved());
    }

    @Test
    void analysisFailureShouldFallBackToHumanReview() {
        when(moderationAgent.analyze(ModerationTargetType.ARTICLE, CONTENT))
                .thenReturn(ModerationAgent.ModerationOutcome.degraded(
                        TraceDegradeReason.LLM_TIMEOUT, "模型超时", "deepseek-flash", 5000L));

        ModerationService.ModerationResult result =
                service(true, 0.9, 0.2).moderate(ModerationTargetType.ARTICLE, TARGET_ID, CONTENT, AUTHOR_ID);

        assertEquals(ModerationAction.ANALYSIS_FAILED, result.action());
        assertEquals(ModerationDecision.REVIEW, result.decision(), "分析失败时判断兜底为 REVIEW");
        verify(articleService, never()).approve(anyLong(), anyLong());

        AiModerationRecord saved = captureSavedRecord();
        assertEquals(ModerationAction.ANALYSIS_FAILED.name(), saved.getAction());
        assertEquals("模型超时", saved.getErrorMessage());
    }

    @Test
    void autoApproveFailureShouldDowngradeToManualReview() {
        when(moderationAgent.analyze(ModerationTargetType.ARTICLE, CONTENT))
                .thenReturn(outcome(assessment("PASS", 0.96, 0.05)));
        doThrow(new RuntimeException("只能审核待审核文章"))
                .when(articleService).approve(TARGET_ID, ModerationService.SYSTEM_OPERATOR_ID);

        ModerationService.ModerationResult result =
                service(true, 0.9, 0.2).moderate(ModerationTargetType.ARTICLE, TARGET_ID, CONTENT, AUTHOR_ID);

        assertFalse(result.autoApproved(), "放行失败时不应报告成功");
        AiModerationRecord saved = captureSavedRecord();
        assertEquals(ModerationAction.PENDING_REVIEW.name(), saved.getAction(), "放行失败必须降级为待人工");
        assertFalse(saved.getHandled(), "降级后应留在待处理列表");
    }

    @Test
    void newTextRuleBeforeAutoApprovalShouldKeepArticleForHumanReview() {
        when(moderationAgent.analyze(ModerationTargetType.ARTICLE, CONTENT))
                .thenReturn(outcome(assessment("PASS", 0.96, 0.05)));
        when(articleService.approve(TARGET_ID, ModerationService.SYSTEM_OPERATOR_ID)).thenReturn(false);

        ModerationService.ModerationResult result =
                service(true, 0.9, 0.2).moderate(ModerationTargetType.ARTICLE, TARGET_ID, CONTENT, AUTHOR_ID);

        assertFalse(result.autoApproved());
        AiModerationRecord saved = captureSavedRecord();
        assertEquals(ModerationAction.PENDING_REVIEW.name(), saved.getAction());
        assertFalse(saved.getHandled());
    }

    // ==================== 落库字段 ====================

    @Test
    void shouldPersistBothAiDecisionAndSystemAction() {
        when(moderationAgent.analyze(ModerationTargetType.ARTICLE, CONTENT))
                .thenReturn(outcome(assessment("REJECT", 0.88, 0.77)));

        service(false, 1d, 0d).moderate(ModerationTargetType.ARTICLE, TARGET_ID, CONTENT, AUTHOR_ID);

        AiModerationRecord saved = captureSavedRecord();
        assertEquals(ModerationDecision.REJECT.name(), saved.getDecision(), "AI 判断要落库");
        assertEquals(ModerationAction.HIGH_PRIORITY_REVIEW.name(), saved.getAction(), "系统动作要落库");
        assertEquals(2, saved.getPriority(), "REJECT 对应高优先级");
        assertEquals(0.77, saved.getRiskScore(), 0.0001, "综合风险分取五维最大值");
        assertEquals(TARGET_ID, saved.getTargetId());
        assertEquals(AUTHOR_ID, saved.getAuthorId());
        assertEquals("deepseek-flash", saved.getModel());
        assertFalse(saved.getHandled(), "待人工处理的记录不应标记为已处理");
    }

    // ==================== 辅助方法 ====================

    private ModerationService service(boolean autoApproveEnabled, double confidence, double maxRisk) {
        return new ModerationService(moderationAgent, recordMapper, articleService,
                autoApproveEnabled, confidence, maxRisk);
    }

    private ModerationAgent.ModerationOutcome outcome(ModerationAssessment assessment) {
        // M18：token 三项由 ChatResponse 采集；本单元测试不关心，传 null 即可
        return ModerationAgent.ModerationOutcome.of(assessment, "deepseek-flash", 800L, null, null, null);
    }

    /** 构造五维评估；{@code maxDimensionScore} 用于制造"某一维度风险最高"的场景。 */
    private ModerationAssessment assessment(String decision, double confidence, double maxDimensionScore) {
        return new ModerationAssessment(
                decision, confidence,
                0d, "无有害内容",
                0d, "无推广行为",
                maxDimensionScore, "疑似风险点",
                0d, "无灌水特征",
                0d, "无敏感内容",
                "测试用结论");
    }

    private AiModerationRecord captureSavedRecord() {
        ArgumentCaptor<AiModerationRecord> captor = ArgumentCaptor.forClass(AiModerationRecord.class);
        verify(recordMapper, org.mockito.Mockito.atLeastOnce()).insert(captor.capture());
        return captor.getValue();
    }
}

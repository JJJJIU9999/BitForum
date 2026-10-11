package com.bitforum.ai.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.bitforum.ai.dto.AiUsageDtos;
import com.bitforum.ai.mapper.AiUsageStatMapper;
import com.bitforum.ai.trace.TraceDegradeReason;

/**
 * 用量预算闸门的单元测试（M18 收尾，克制版）。
 *
 * <p>要验证的只有三件事，以及它们的边界：
 * 单用户每日预算（token / 费用两个维度）、超限后的统一降级文案、单次输入保护。
 * 另外明确锁定两个**有意的**软边界：查询失败时放行（fail-open）、匿名不检查。
 */
class AiTokenBudgetGuardTest {

    private static final long TOKEN_LIMIT = 1000L;
    private static final BigDecimal COST_LIMIT = new BigDecimal("0.50");

    private AiUsageStatMapper usageMapper;

    @BeforeEach
    void setUp() {
        usageMapper = mock(AiUsageStatMapper.class);
    }

    private AiTokenBudgetGuard guard(boolean enabled, long tokenLimit, BigDecimal costLimit, int maxInput) {
        return new AiTokenBudgetGuard(usageMapper, enabled, tokenLimit, costLimit, maxInput, 1024);
    }

    private void usageOf(long tokens, String cost) {
        AiUsageDtos.UserUsage usage = new AiUsageDtos.UserUsage();
        usage.setTotalTokens(tokens);
        usage.setCost(new BigDecimal(cost));
        when(usageMapper.selectUserDaily(any(), any())).thenReturn(usage);
    }

    /** 还没用过：放行。 */
    @Test
    void shouldAllowWhenNoUsageToday() {
        when(usageMapper.selectUserDaily(any(), any())).thenReturn(null);

        assertTrue(guard(true, TOKEN_LIMIT, COST_LIMIT, 4000).check(13L).allowed());
    }

    @Test
    void shouldAllowWhenUserIdIsNull() {
        // 匿名访客没有可归属的用量，预算对他没有意义
        assertTrue(guard(true, TOKEN_LIMIT, COST_LIMIT, 4000).check(null).allowed());
    }

    @Test
    void shouldAllowWhenDisabled() {
        usageOf(999999L, "99.9");

        assertTrue(guard(false, TOKEN_LIMIT, COST_LIMIT, 4000).check(13L).allowed(),
                "开关关闭时必须完全放行（便于本地演示与回归）");
    }

    @Test
    void shouldDenyWhenDailyTokenLimitReached() {
        usageOf(TOKEN_LIMIT, "0.01");

        AiTokenBudgetGuard.BudgetDecision decision = guard(true, TOKEN_LIMIT, COST_LIMIT, 4000).check(13L);

        assertFalse(decision.allowed());
        assertEquals(TraceDegradeReason.BUDGET_EXCEEDED, decision.reason());
        assertEquals(TraceDegradeReason.userMessage(TraceDegradeReason.BUDGET_EXCEEDED), decision.message(),
                "超预算的对外文案必须与其他降级一样是统一口径");
        assertTrue(decision.detail().contains("1000"), "细节里要能看出用了多少、限了多少：" + decision.detail());
    }

    @Test
    void shouldDenyWhenDailyCostLimitReached() {
        usageOf(10L, "0.50");

        AiTokenBudgetGuard.BudgetDecision decision = guard(true, TOKEN_LIMIT, COST_LIMIT, 4000).check(13L);

        assertFalse(decision.allowed());
        assertTrue(decision.detail().contains("0.50"), "费用维度也要能触发：" + decision.detail());
    }

    @Test
    void shouldTreatNonPositiveLimitAsUnlimited() {
        usageOf(10_000_000L, "999.9");

        AiTokenBudgetGuard.BudgetDecision decision = guard(true, 0L, BigDecimal.ZERO, 4000).check(13L);

        assertTrue(decision.allowed(), "配置 <= 0 表示不限制该维度");
    }

    /** 预算组件自身故障不应让所有人用不了 AI：fail-open，与"AI 挂了不影响主流程"同一原则。 */
    @Test
    void shouldFailOpenWhenQueryFails() {
        when(usageMapper.selectUserDaily(any(), any()))
                .thenThrow(new IllegalStateException("数据库不可用"));

        assertTrue(guard(true, TOKEN_LIMIT, COST_LIMIT, 4000).check(13L).allowed());
    }

    @Test
    void shouldDenyWhenInputTooLong() {
        AiTokenBudgetGuard.BudgetDecision decision = guard(true, TOKEN_LIMIT, COST_LIMIT, 100)
                .checkInputLength("字".repeat(101));

        assertFalse(decision.allowed());
        assertEquals(TraceDegradeReason.INPUT_TOO_LONG, decision.reason());
        assertEquals(TraceDegradeReason.userMessage(TraceDegradeReason.INPUT_TOO_LONG), decision.message());
    }

    @Test
    void shouldAllowInputWithinLimit() {
        AiTokenBudgetGuard.BudgetDecision decision = guard(true, TOKEN_LIMIT, COST_LIMIT, 100)
                .checkInputLength("字".repeat(100));

        assertTrue(decision.allowed());
        assertNull(decision.reason());
    }

    @Test
    void shouldExposeConfiguredLimitsForAdminView() {
        AiTokenBudgetGuard guard = guard(true, 1234L, new BigDecimal("1.5"), 4000);

        assertTrue(guard.enabled());
        assertEquals(1234L, guard.dailyTokenLimit());
        assertEquals(new BigDecimal("1.5"), guard.dailyCostLimit());
        assertEquals(4000, guard.maxInputChars());
        assertEquals(1024, guard.maxOutputTokens());
        // 查询使用的日期就是"今天"，避免跨天时把昨天的用量算进来
        usageOf(1L, "0");
        guard.check(13L);
        org.mockito.Mockito.verify(usageMapper).selectUserDaily(13L, LocalDate.now());
    }
}

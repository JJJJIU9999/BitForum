package com.bitforum.ai.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.Data;

/**
 * 用量聚合的返回结构（M18 管理端用量概览）。
 *
 * <p>几个聚合项放在同一个文件里作为静态内部类：它们只在"用量概览"这一个接口里出现，
 * 拆成五个文件只会让包变乱。
 */
public final class AiUsageDtos {

    private AiUsageDtos() {
    }

    /** 总量。 */
    @Data
    public static class Totals {
        private Long calls;
        private Long promptTokens;
        private Long completionTokens;
        private Long totalTokens;
        private BigDecimal cost;
        private BigDecimal averageLatencyMs;
        /** 非 SUCCESS 的调用数（降级 + 失败） */
        private Long abnormalCalls;
    }

    /** 按天。 */
    @Data
    public static class DailyItem {
        private LocalDate statDate;
        private Long calls;
        private Long totalTokens;
        private BigDecimal cost;
    }

    /** 按 Agent。 */
    @Data
    public static class AgentItem {
        private String agentType;
        private Long calls;
        private Long totalTokens;
        private BigDecimal cost;
    }

    /** 按用户（消耗最高的若干位）。 */
    @Data
    public static class UserItem {
        private Long userId;
        private Long calls;
        private Long totalTokens;
        private BigDecimal cost;
    }

    /** 某个用户在某一天的合计用量：M18 预算闸门（`TokenBudgetGuard`）据此判断是否超限。 */
    @Data
    public static class UserUsage {
        private Long totalTokens;
        private BigDecimal cost;
    }
}

package com.bitforum.ai.dto;

import java.math.BigDecimal;
import java.util.List;

import lombok.Data;

/**
 * 用量概览返回体（M18 管理端）。
 *
 * <p>刻意把"单价"一并返回：页面上的成本是**估算值**，把单价和说明一起展示，
 * 评审/管理员才能判断这个数字是怎么来的（M18 验收第 2 条要求"能算出单次成本"，
 * 而可解释性同样适用于成本本身）。
 */
@Data
public class AiUsageOverviewResponse {

    /** 统计区间天数（含今天） */
    private int days;

    private AiUsageDtos.Totals totals;

    private List<AiUsageDtos.DailyItem> daily;

    private List<AiUsageDtos.AgentItem> byAgent;

    private List<AiUsageDtos.UserItem> topUsers;

    /** 计价用的输入单价（元 / 百万 token） */
    private BigDecimal inputPricePerMillion;

    /** 计价用的输出单价（元 / 百万 token） */
    private BigDecimal outputPricePerMillion;

    /** 计价口径说明，前端直接展示 */
    private String priceNote;

    /** 预算口径（M18 收尾）：让管理端能解释"为什么某个用户会被限流" */
    private Budget budget;

    /** 预算配置的对外快照。 */
    @Data
    public static class Budget {
        private boolean enabled;
        /** 单用户每日 token 上限；<= 0 表示不限制 */
        private long dailyTokenLimit;
        /** 单用户每日费用上限（元）；<= 0 表示不限制 */
        private BigDecimal dailyCostLimit;
        /** 单次提问输入长度上限（字符） */
        private int maxInputChars;
        /** 单次回答输出上限（token） */
        private int maxOutputTokens;
        /** 生效范围与边界说明 */
        private String note;
    }
}

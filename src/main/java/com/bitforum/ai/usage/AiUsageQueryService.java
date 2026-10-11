package com.bitforum.ai.usage;

import java.time.LocalDate;

import org.springframework.stereotype.Service;

import com.bitforum.ai.dto.AiUsageDtos;
import com.bitforum.ai.dto.AiUsageOverviewResponse;
import com.bitforum.ai.mapper.AiUsageStatMapper;

/**
 * 用量查询服务（M18 管理端）。
 *
 * <p>聚合全部下推到 SQL（见 {@link AiUsageStatMapper}）：明细行数会随调用量增长，
 * 把明细捞进内存再聚合是不可持续的。
 */
@Service
public class AiUsageQueryService {

    /** 概览里展示的"消耗最高的用户"条数。 */
    private static final int TOP_USER_LIMIT = 10;
    /** 查询区间下限与上限：上限防止把整表扫出来。 */
    private static final int MIN_DAYS = 1;
    private static final int MAX_DAYS = 90;

    private static final String PRICE_NOTE =
            "成本为按配置单价（输入/输出分列）估算的参考值，非账单金额；单价可能随供应商调价而变化。";

    private static final String BUDGET_NOTE =
            "预算只对「用户主动消耗额度」的链路生效（AI 助手对话、推荐理由生成）；"
                    + "内容审核与运营洞察不设闸门。判定基于已落库的当日用量，属软上限。";

    private final AiUsageStatMapper usageMapper;
    private final AiUsageRecorder usageRecorder;
    private final AiTokenBudgetGuard budgetGuard;

    public AiUsageQueryService(AiUsageStatMapper usageMapper, AiUsageRecorder usageRecorder,
                               AiTokenBudgetGuard budgetGuard) {
        this.usageMapper = usageMapper;
        this.usageRecorder = usageRecorder;
        this.budgetGuard = budgetGuard;
    }

    /** 最近 days 天（含今天）的用量概览。 */
    public AiUsageOverviewResponse overview(int days) {
        int normalized = Math.min(MAX_DAYS, Math.max(MIN_DAYS, days));
        LocalDate from = LocalDate.now().minusDays(normalized - 1L);

        AiUsageOverviewResponse response = new AiUsageOverviewResponse();
        response.setDays(normalized);
        response.setTotals(usageMapper.selectTotals(from));
        response.setDaily(usageMapper.selectDaily(from));
        response.setByAgent(usageMapper.selectByAgent(from));
        response.setTopUsers(usageMapper.selectTopUsers(from, TOP_USER_LIMIT));
        response.setInputPricePerMillion(usageRecorder.inputPricePerMillion());
        response.setOutputPricePerMillion(usageRecorder.outputPricePerMillion());
        response.setPriceNote(PRICE_NOTE);
        response.setBudget(budgetSnapshot());
        return response;
    }

    /** 预算配置的对外快照：管理端据此解释"为什么某个用户会被限流"。 */
    private AiUsageOverviewResponse.Budget budgetSnapshot() {
        AiUsageOverviewResponse.Budget budget = new AiUsageOverviewResponse.Budget();
        budget.setEnabled(budgetGuard.enabled());
        budget.setDailyTokenLimit(budgetGuard.dailyTokenLimit());
        budget.setDailyCostLimit(budgetGuard.dailyCostLimit());
        budget.setMaxInputChars(budgetGuard.maxInputChars());
        budget.setMaxOutputTokens(budgetGuard.maxOutputTokens());
        budget.setNote(BUDGET_NOTE);
        return budget;
    }

    /** 按天明细（供"用量趋势"单独使用）。 */
    public java.util.List<AiUsageDtos.DailyItem> daily(int days) {
        int normalized = Math.min(MAX_DAYS, Math.max(MIN_DAYS, days));
        return usageMapper.selectDaily(LocalDate.now().minusDays(normalized - 1L));
    }
}

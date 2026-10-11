package com.bitforum.ai.usage;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.bitforum.ai.dto.AiUsageDtos;
import com.bitforum.ai.mapper.AiUsageStatMapper;
import com.bitforum.ai.trace.TraceDegradeReason;

/**
 * AI 用量预算闸门（M18 收尾，克制版）。
 *
 * <p><b>只做三件事</b>（刻意不做套餐、充值、余额、会员等级、分布式配额中心、动态配置后台）：
 *
 * <ol>
 *   <li><b>单用户每日预算</b>：按 token 与费用（估算）两个维度，任一超限即拦截；</li>
 *   <li><b>超预算后的统一降级</b>：复用 {@link TraceDegradeReason#BUDGET_EXCEEDED} 的固定文案，
 *       对话给出"额度用完"的兜底回答，推荐退化为"无理由推荐"（列表照常可用）；</li>
 *   <li><b>单次请求保护</b>：输入长度上限（防止用超长提问把上下文撑爆）。</li>
 * </ol>
 *
 * <p><b>数据来源</b>：直接聚合 {@code ai_usage_stat} 的"该用户 + 今天"，**不新增表、不新增迁移** ——
 * M18 的用量明细本来就按 用户/天 建了索引，预算判断只是把它读出来比大小。
 *
 * <p><b>只在用户主动消耗额度的链路生效</b>：AI 助手对话与推荐理由生成。
 * 内容审核（MQ 异步、影响治理公平性）与运营洞察（管理员触发）**不设预算闸门** ——
 * 让"作者超额"导致内容不被审核，是拿治理正确性换成本，不划算。
 *
 * <p><b>两个有意的软边界</b>：
 * <ul>
 *   <li>判定基于"今天已写入的用量"，而当前这次调用的 token 要等调用结束才落库，
 *       因此实际用量可能略微超出上限（soft limit）。要做到硬上限需要预扣额度，
 *       那属于"配额中心"的范畴，本轮明确不做。</li>
 *   <li>查询失败时**放行**（fail-open）：预算组件自身的故障不应该让用户完全用不了 AI，
 *       这与"AI 不可用不能影响论坛主流程"是同一条原则。</li>
 * </ul>
 */
@Service
public class AiTokenBudgetGuard {

    private static final Logger log = LoggerFactory.getLogger(AiTokenBudgetGuard.class);

    private final AiUsageStatMapper usageMapper;
    private final boolean enabled;
    private final long dailyTokenLimit;
    private final BigDecimal dailyCostLimit;
    private final int maxInputChars;
    private final int maxOutputTokens;

    public AiTokenBudgetGuard(AiUsageStatMapper usageMapper,
                              @Value("${bitforum.ai.budget.enabled:true}") boolean enabled,
                              @Value("${bitforum.ai.budget.daily-token-limit:200000}") long dailyTokenLimit,
                              @Value("${bitforum.ai.budget.daily-cost-limit:2.0}") BigDecimal dailyCostLimit,
                              // S4：默认值与 HTTP DTO（AiChatRequest @Size(max=2000)）保持一致，
                              // 否则更严的 HTTP 入口会让这条链路闸门永远不可达
                              @Value("${bitforum.ai.budget.max-input-chars:2000}") int maxInputChars,
                              @Value("${bitforum.ai.budget.max-output-tokens:1024}") int maxOutputTokens) {
        this.usageMapper = usageMapper;
        this.enabled = enabled;
        this.dailyTokenLimit = dailyTokenLimit;
        this.dailyCostLimit = dailyCostLimit;
        this.maxInputChars = maxInputChars;
        this.maxOutputTokens = maxOutputTokens;
    }

    /**
     * 一次预算判定。
     *
     * @param allowed 是否放行
     * @param reason  拒绝时的原因码（放行为 null）
     * @param message 拒绝时面向用户的统一文案（放行为 null）
     * @param detail  拒绝时的内部说明（写入轨迹，便于管理员看清"用了多少、限了多少"）
     */
    public record BudgetDecision(boolean allowed, String reason, String message, String detail) {

        public static BudgetDecision allow() {
            return new BudgetDecision(true, null, null, null);
        }

        public static BudgetDecision deny(String reason, String detail) {
            return new BudgetDecision(false, reason, TraceDegradeReason.userMessage(reason), detail);
        }
    }

    /**
     * 判断该用户今天是否还有额度。
     *
     * <p>{@code userId} 为空（匿名访客）或预算功能关闭时直接放行：
     * 前者没有可归属的用量，后者是运维开关。
     */
    public BudgetDecision check(Long userId) {
        if (!enabled || userId == null) {
            return BudgetDecision.allow();
        }
        AiUsageDtos.UserUsage usage;
        try {
            usage = usageMapper.selectUserDaily(userId, LocalDate.now());
        } catch (RuntimeException exception) {
            // fail-open：预算查询失败不能变成"所有人都用不了 AI"
            log.warn("AI 预算查询失败，本次放行：userId={}", userId, exception);
            return BudgetDecision.allow();
        }
        long tokens = usage == null || usage.getTotalTokens() == null ? 0L : usage.getTotalTokens();
        BigDecimal cost = usage == null || usage.getCost() == null ? BigDecimal.ZERO : usage.getCost();

        if (dailyTokenLimit > 0 && tokens >= dailyTokenLimit) {
            return BudgetDecision.deny(TraceDegradeReason.BUDGET_EXCEEDED,
                    "今日已用 " + tokens + " token，达到上限 " + dailyTokenLimit);
        }
        if (dailyCostLimit != null && dailyCostLimit.signum() > 0 && cost.compareTo(dailyCostLimit) >= 0) {
            return BudgetDecision.deny(TraceDegradeReason.BUDGET_EXCEEDED,
                    "今日已用约 " + cost + " 元，达到上限 " + dailyCostLimit + " 元");
        }
        return BudgetDecision.allow();
    }

    /**
     * 单次请求的输入保护：超长直接拒绝，避免把上下文与费用一次性撑爆。
     *
     * <p>用**字符数**而不是 token 数：中文按字近似 1 token，但精确 tokenize 需要在请求前
     * 额外跑一次分词，为了一个保护性上限不值得（真正的 token 统计在用量表里）。
     */
    public BudgetDecision checkInputLength(String text) {
        if (!enabled || maxInputChars <= 0 || text == null) {
            return BudgetDecision.allow();
        }
        if (text.length() > maxInputChars) {
            return BudgetDecision.deny(TraceDegradeReason.INPUT_TOO_LONG,
                    "输入长度 " + text.length() + " 字符，超过上限 " + maxInputChars);
        }
        return BudgetDecision.allow();
    }

    // ==================== 供管理端展示口径 ====================

    public boolean enabled() {
        return enabled;
    }

    public long dailyTokenLimit() {
        return dailyTokenLimit;
    }

    public BigDecimal dailyCostLimit() {
        return dailyCostLimit;
    }

    public int maxInputChars() {
        return maxInputChars;
    }

    public int maxOutputTokens() {
        return maxOutputTokens;
    }
}

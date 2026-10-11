package com.bitforum.ai.moderation;

/**
 * 模型返回的结构化审核结论（M16）。
 *
 * <p><b>为什么用固定字段而不是「维度列表」</b>：T7 实测（findings.md 6.12）发现，
 * 让模型返回 {@code List<DimensionScore>} 时维度数量并不稳定（4、5、6 个都出现过）。
 * 固定字段才能在数据库与校验层面强制五维齐全。
 *
 * <p><b>刻意不含综合分</b>：综合风险分由 Java 按确定性规则计算（取五维最大值），
 * 不用模型自评的分数 —— 阈值判定必须是可复现的。
 *
 * @param decision        三档判断文本（PASS / REVIEW / REJECT），解析时容错
 * @param confidence      模型对本次判断的置信度 0~1
 * @param harmfulScore    有害内容（人身攻击、侮辱、仇恨、暴力）风险分 0~1
 * @param harmfulReason   该维度的判定理由
 * @param promotionScore  广告推广与站外引流风险分 0~1
 * @param promotionReason 该维度的判定理由
 * @param fraudScore      诈骗与钓鱼风险分 0~1
 * @param fraudReason     该维度的判定理由
 * @param spamScore       灌水刷屏风险分 0~1
 * @param spamReason      该维度的判定理由
 * @param sensitiveScore  敏感信息（涉政、违法、色情、隐私）风险分 0~1
 * @param sensitiveReason 该维度的判定理由
 * @param summary         整体结论摘要（一句话）
 */
public record ModerationAssessment(
        String decision,
        double confidence,
        double harmfulScore,
        String harmfulReason,
        double promotionScore,
        String promotionReason,
        double fraudScore,
        String fraudReason,
        double spamScore,
        String spamReason,
        double sensitiveScore,
        String sensitiveReason,
        String summary) {

    /** 解析为枚举档位（无法识别时回退 REVIEW）。 */
    public ModerationDecision decisionEnum() {
        return ModerationDecision.fromName(decision);
    }

    /**
     * 五维中的最大值 —— 综合风险分的原始依据。
     *
     * <p>取最大值而不是加权平均：最大值对"某一维度明确违规"最敏感，
     * 与「检测高召回、执行高精度」的取向一致，且规则简单、可解释、可复现。
     */
    public double maxScore() {
        return Math.max(
                Math.max(Math.max(harmfulScore, promotionScore), Math.max(fraudScore, spamScore)),
                sensitiveScore);
    }

    /** 风险最高的维度名（与 {@link #maxScore()} 对应）。 */
    public String maxDimension() {
        double max = maxScore();
        if (max == harmfulScore) {
            return "harmful";
        }
        if (max == fraudScore) {
            return "fraud";
        }
        if (max == promotionScore) {
            return "promotion";
        }
        if (max == sensitiveScore) {
            return "sensitive";
        }
        return "spam";
    }
}

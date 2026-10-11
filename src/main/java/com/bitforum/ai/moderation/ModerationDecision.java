package com.bitforum.ai.moderation;

/**
 * AI 审核判断档位（M16）。
 *
 * <p><b>注意：这只是 AI 的判断，不等于系统动作。</b>
 * 系统实际做什么由 {@link ModerationAction} 表达，两者在数据中分开保存
 * （见 V15 迁移脚本的说明与 task_plan.md 的 M16 实施决策）。
 */
public enum ModerationDecision {

    /** 放行：内容正常 */
    PASS,
    /** 转人工：可能有风险但特征不明确，或信息不足 */
    REVIEW,
    /** 拒绝：存在明确违规特征 */
    REJECT;

    /**
     * 容错解析模型返回的档位文本。
     *
     * <p>模型偶尔会返回大小写不一致或带空格的文本，这里统一规整；
     * 无法识别时回退到 {@link #REVIEW}（转人工），符合"拿不准就交给人工"的安全取向。
     */
    public static ModerationDecision fromName(String name) {
        if (name == null || name.isBlank()) {
            return REVIEW;
        }
        String normalized = name.trim().toUpperCase();
        for (ModerationDecision decision : values()) {
            if (decision.name().equals(normalized)) {
                return decision;
            }
        }
        return REVIEW;
    }
}

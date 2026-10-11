package com.bitforum.ai.moderation;

/**
 * 系统对一次 AI 审核判断采取的动作（M16）。
 *
 * <p>与 {@link ModerationDecision} 解耦：AI 判断"是什么"，本枚举表达"系统做了什么"。
 * 同一种 AI 判断在不同配置下可能对应不同动作（例如文章判 PASS：
 * 自动放行开关开启时是 {@link #AUTO_APPROVED}，关闭时是 {@link #PENDING_REVIEW}）。
 *
 * <p><b>本枚举刻意不包含任何"自动驳回/自动删除"</b>：按 M16 实施决策，
 * 系统绝不自动驳回或删除内容，误判代价不对称。
 */
public enum ModerationAction {

    /** 高置信 PASS 且自动放行开关开启：文章已被系统自动放行 */
    AUTO_APPROVED("自动放行", 0),

    /** 无需人工处理：评论判定 PASS，仅留存判断记录 */
    NO_ACTION("无需处理", 0),

    /** 待人工复核：文章的常规状态，或评论判定 REVIEW */
    PENDING_REVIEW("待人工复核", 1),

    /** 高优先级待复核：判定 REJECT，排在管理台待处理列表前面 */
    HIGH_PRIORITY_REVIEW("高优先级待复核", 2),

    /** AI 分析失败：不产生判断，按原有人工流程处理 */
    ANALYSIS_FAILED("分析失败转人工", 1);

    private final String displayName;
    private final int priority;

    ModerationAction(String displayName, int priority) {
        this.displayName = displayName;
        this.priority = priority;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** 待处理优先级：0 无需处理 / 1 普通 / 2 高优先 */
    public int getPriority() {
        return priority;
    }

    /** 是否需要人工作业（管理台待处理列表只展示这些） */
    public boolean needsHumanReview() {
        return priority > 0;
    }
}

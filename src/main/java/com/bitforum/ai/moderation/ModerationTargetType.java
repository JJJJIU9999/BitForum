package com.bitforum.ai.moderation;

/**
 * 审核对象类型（M16）。
 *
 * <p>两类对象的审核时机不同，这是业务事实而非实现细节：
 *
 * <ul>
 *   <li>{@link #ARTICLE}：作者提交后进入 PENDING，**先审后发**，AI 可在人工审核前给建议；</li>
 *   <li>{@link #COMMENT}：发布即公开，AI 只能**事后检测**，REJECT 的语义是"高优先级提醒管理员"，
 *       而不是"拒绝发布"。</li>
 * </ul>
 */
public enum ModerationTargetType {

    ARTICLE("文章"),
    COMMENT("评论");

    private final String displayName;

    ModerationTargetType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}

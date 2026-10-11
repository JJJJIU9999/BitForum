package com.bitforum.message;

/**
 * 内容审核消息（M16）。
 *
 * <p>文章提交审核、评论发布后投递，由消费者异步调用 AI 审核。
 *
 * <p><b>只携带对象标识、不携带内容</b>：消费者按 id 回查最新内容，
 * 避免消息体过大（文章正文可能上千字），也避免消费到已被修改的旧内容。
 * 查不到对象（已删除）时直接跳过，不做任何处理。
 */
public class ModerationMessage {

    /**
     * 对象类型常量：文章。
     *
     * <p>放在消息类而不是 {@code ModerationTargetType} 枚举里，是为了避免包循环依赖：
     * 消息层被业务层与 ai 层共同依赖，而 ai 层已经依赖业务层（审核服务要调用文章审核接口）。
     * 业务层只引用这里的常量即可，不需要反向依赖 ai 包。
     */
    public static final String TARGET_ARTICLE = "ARTICLE";

    /** 对象类型常量：评论 */
    public static final String TARGET_COMMENT = "COMMENT";

    /** 对象类型：ARTICLE / COMMENT，与 ModerationTargetType 对应 */
    private String targetType;

    private Long targetId;

    /** 消息唯一编号，用于 Redis 幂等去重（与既有消息同一套机制） */
    private String messageId;

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public Long getTargetId() {
        return targetId;
    }

    public void setTargetId(Long targetId) {
        this.targetId = targetId;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }
}

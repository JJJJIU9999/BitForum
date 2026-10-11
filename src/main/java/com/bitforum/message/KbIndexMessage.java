package com.bitforum.message;

/**
 * 知识库索引消息（M15）。
 *
 * <p>文章可见性发生变化时发出，消费者按**文章当前状态**决定是写入还是移除索引，
 * 因此消息本身只需要携带 articleId：
 *
 * <ul>
 *   <li>审核通过 → 文章为 PUBLISHED → 写入索引</li>
 *   <li>下架 / 删除 → 查不到或非 PUBLISHED → 移除索引</li>
 * </ul>
 *
 * <p>这样一个消息类型覆盖全部触发点，消费者逻辑保持单一。
 */
public class KbIndexMessage {

    private Long articleId;
    /** 消息唯一编号，用于 Redis 幂等去重（与既有的文章发布消息同一套机制） */
    private String messageId;

    public Long getArticleId() {
        return articleId;
    }

    public void setArticleId(Long articleId) {
        this.articleId = articleId;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }
}

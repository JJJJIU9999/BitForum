package com.bitforum.message;

public class ArticlePublishMessage {
    // MQ 消息对象只放消费者需要的信息，不直接复用数据库实体，避免消息格式和表结构强绑定。
    private Long articleId;
    private Long userId;
    private Long auditorId;
    private String title;
    private Long publishTime;
    // 每条 MQ 消息的唯一编号，后续做“重复消费幂等”时可以用它判断消息是否处理过。
    private String messageId;
    public String getMessageId() {
        return messageId;
    }
    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }
    public Long getArticleId() {
        return articleId;
    }
    public void setArticleId(Long articleId) {
        this.articleId = articleId;
    }
    public Long getUserId() {
        return userId;
    }
    public void setUserId(Long userId) {
        this.userId = userId;
    }
    public Long getAuditorId() {
        return auditorId;
    }
    public void setAuditorId(Long auditorId) {
        this.auditorId = auditorId;
    }
    public String getTitle() {
        return title;
    }
    public void setTitle(String title) {
        this.title = title;
    }
    public Long getPublishTime() {
        return publishTime;
    }
    public void setPublishTime(Long publishTime) {
        this.publishTime = publishTime;
    }
}

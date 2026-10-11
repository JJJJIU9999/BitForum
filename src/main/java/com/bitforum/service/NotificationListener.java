package com.bitforum.service;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.amqp.core.Message;

import com.bitforum.config.RabbitMQConfig;
import com.bitforum.entity.Article;
import com.bitforum.message.ArticlePublishMessage;
import com.rabbitmq.client.Channel;

@Service
public class NotificationListener {
    private static final Logger log = LoggerFactory.getLogger(NotificationListener.class);

    @Autowired
    private RedisService redisService;
    @Autowired
    private NotificationService notificationService;

    // 监听文章发布队列。RabbitTemplate 发送 JSON 后，这里会自动反序列化成 ArticlePublishMessage。
    @RabbitListener(queues = RabbitMQConfig.ARTICLE_PUBLISH_QUEUE)
    public void handlePublish(ArticlePublishMessage message, Channel channel, Message rawMessage) throws IOException {
        long deliveryTag = rawMessage.getMessageProperties().getDeliveryTag();

        try {
            if (redisService.isMessageProcessed(message.getMessageId())) {
                log.info("文章发布消息已经过处理，跳过重复消费：messageId={}", message.getMessageId());
                channel.basicAck(deliveryTag, false);
                return;
            }

            log.info("收到文章发布消息：messageId={}, articleId={}, userId={}, title={}",
                    message.getMessageId(),
                    message.getArticleId(),
                    message.getUserId(),
                    message.getTitle());

            processPublish(message);
            redisService.markMessageProcessed(message.getMessageId());
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("文章发布消息处理失败：messageId={}", message.getMessageId(), e);
            channel.basicNack(deliveryTag, false, false);
        }
    }

    void processPublish(ArticlePublishMessage message) {
        Article article = new Article();
        article.setId(message.getArticleId());
        article.setUserId(message.getUserId());
        article.setTitle(message.getTitle());
        notificationService.notifyAuditApproved(article, message.getAuditorId(), message.getMessageId());
    }

    // 监听死信队列。这里先只记录失败消息，后续可以扩展为人工补偿、告警或重新投递。
    @RabbitListener(queues = RabbitMQConfig.ARTICLE_PUBLISH_DLQ)
    public void handlePublishDeadLetter(ArticlePublishMessage message) {
        log.error("文章发布死信消息：messageId={}, articleId={}, userId={}, title={}",
                message.getMessageId(),
                message.getArticleId(),
                message.getUserId(),
                message.getTitle());
    }
}

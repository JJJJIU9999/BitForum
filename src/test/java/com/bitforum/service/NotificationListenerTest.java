package com.bitforum.service;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import com.bitforum.message.ArticlePublishMessage;
import com.rabbitmq.client.Channel;

@ExtendWith(MockitoExtension.class)
public class NotificationListenerTest {

    @InjectMocks
    private NotificationListener notificationListener;

    @Mock
    private RedisService redisService;

    @Mock
    private NotificationService notificationService;

    @Mock
    private Channel channel;

    @Test
    void successfulMessageShouldCreateNotificationThenMarkAndAck() throws Exception {
        ArticlePublishMessage message = message("normal-message-id");
        Message rawMessage = rawMessage(101L);
        when(redisService.isMessageProcessed(message.getMessageId())).thenReturn(false);

        notificationListener.handlePublish(message, channel, rawMessage);

        InOrder order = inOrder(redisService, notificationService, channel);
        order.verify(redisService).isMessageProcessed(message.getMessageId());
        order.verify(notificationService).notifyAuditApproved(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(message.getAuditorId()),
                org.mockito.ArgumentMatchers.eq(message.getMessageId()));
        order.verify(redisService).markMessageProcessed(message.getMessageId());
        order.verify(channel).basicAck(101L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void duplicateMessageShouldAckAndSkip() throws Exception {
        ArticlePublishMessage message = message("duplicate-message-id");
        Message rawMessage = rawMessage(123L);
        when(redisService.isMessageProcessed(message.getMessageId())).thenReturn(true);

        notificationListener.handlePublish(message, channel, rawMessage);

        verify(channel).basicAck(123L, false);
        verify(notificationService, never()).notifyAuditApproved(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
        verify(redisService, never()).markMessageProcessed(message.getMessageId());
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void businessFailureShouldNackWithoutProcessedMarker() throws Exception {
        ArticlePublishMessage message = message("failed-message-id");
        when(redisService.isMessageProcessed(message.getMessageId())).thenReturn(false);
        doThrow(new RuntimeException("notification insert failed"))
                .when(notificationService)
                .notifyAuditApproved(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq(message.getAuditorId()),
                        org.mockito.ArgumentMatchers.eq(message.getMessageId()));

        notificationListener.handlePublish(message, channel, rawMessage(201L));

        verify(redisService, never()).markMessageProcessed(message.getMessageId());
        verify(channel).basicNack(201L, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void failedMessageShouldBeProcessableOnReplay() throws Exception {
        ArticlePublishMessage message = message("replay-message-id");
        when(redisService.isMessageProcessed(message.getMessageId())).thenReturn(false);
        doThrow(new RuntimeException("first attempt failed"))
                .doNothing()
                .when(notificationService)
                .notifyAuditApproved(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq(message.getAuditorId()),
                        org.mockito.ArgumentMatchers.eq(message.getMessageId()));

        notificationListener.handlePublish(message, channel, rawMessage(301L));
        notificationListener.handlePublish(message, channel, rawMessage(302L));

        verify(notificationService, times(2)).notifyAuditApproved(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(message.getAuditorId()),
                org.mockito.ArgumentMatchers.eq(message.getMessageId()));
        verify(redisService).markMessageProcessed(message.getMessageId());
        verify(channel).basicNack(301L, false, false);
        verify(channel).basicAck(302L, false);
    }

    private ArticlePublishMessage message(String messageId) {
        ArticlePublishMessage message = new ArticlePublishMessage();
        message.setMessageId(messageId);
        message.setArticleId(1L);
        message.setUserId(2L);
        message.setAuditorId(3L);
        message.setTitle("测试文章");
        return message;
    }

    private Message rawMessage(long deliveryTag) {
        MessageProperties messageProperties = new MessageProperties();
        messageProperties.setDeliveryTag(deliveryTag);
        return new Message(new byte[0], messageProperties);
    }
}

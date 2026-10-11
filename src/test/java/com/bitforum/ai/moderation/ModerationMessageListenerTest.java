package com.bitforum.ai.moderation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import com.bitforum.ai.trace.TraceRecorder;
import com.bitforum.entity.Article;
import com.bitforum.entity.Comment;
import com.bitforum.mapper.ArticleMapper;
import com.bitforum.mapper.CommentMapper;
import com.bitforum.message.ModerationMessage;
import com.bitforum.service.ArticleService;
import com.bitforum.service.RedisService;
import com.rabbitmq.client.Channel;

/**
 * 审核消息监听器测试（M16）。
 *
 * <p>重点验证两件事：手动 ACK + 幂等的完整分支，以及**文章只在待审核状态才送审**
 * （避免重复分析、也避免自动放行去改一个已被人工处理的文章）。
 */
@ExtendWith(MockitoExtension.class)
class ModerationMessageListenerTest {

    @InjectMocks
    private ModerationMessageListener listener;

    @Mock
    private RedisService redisService;
    @Mock
    private ArticleMapper articleMapper;
    @Mock
    private CommentMapper commentMapper;
    @Mock
    private ModerationService moderationService;
    @Mock
    private Channel channel;
    /** M18：轨迹记录器；本测试只关心 ACK/幂等分支，轨迹的写入由轨迹自身的测试覆盖。 */
    @Mock
    private TraceRecorder traceRecorder;

    @Test
    void pendingArticleShouldBeSubmittedForModeration() throws Exception {
        ModerationMessage message = articleMessage("mod-article", 701L);
        when(redisService.isMessageProcessed("mod-article")).thenReturn(false);
        when(articleMapper.selectById(701L)).thenReturn(article(701L, ArticleService.STATUS_PENDING, 42L));

        listener.handleModeration(message, channel, rawMessage(21L));

        // 标题与正文一起送审
        verify(moderationService).moderate(
                eq(ModerationTargetType.ARTICLE), eq(701L),
                org.mockito.ArgumentMatchers.contains("测试标题"), eq(42L));
        verify(redisService).markMessageProcessed("mod-article");
        verify(channel).basicAck(21L, false);
    }

    @Test
    void articleAlreadyHandledShouldBeSkippedWithoutModelCall() throws Exception {
        ModerationMessage message = articleMessage("mod-published", 702L);
        when(redisService.isMessageProcessed("mod-published")).thenReturn(false);
        when(articleMapper.selectById(702L)).thenReturn(article(702L, ArticleService.STATUS_PUBLISHED, 42L));

        listener.handleModeration(message, channel, rawMessage(22L));

        verifyNoInteractions(moderationService);
        // 消息仍要标记并 ack，否则会反复重投
        verify(redisService).markMessageProcessed("mod-published");
        verify(channel).basicAck(22L, false);
    }

    @Test
    void missingArticleShouldBeSkipped() throws Exception {
        ModerationMessage message = articleMessage("mod-missing", 703L);
        when(redisService.isMessageProcessed("mod-missing")).thenReturn(false);
        when(articleMapper.selectById(703L)).thenReturn(null);

        listener.handleModeration(message, channel, rawMessage(23L));

        verifyNoInteractions(moderationService);
        verify(channel).basicAck(23L, false);
    }

    @Test
    void commentShouldBeSubmittedForModeration() throws Exception {
        ModerationMessage message = commentMessage("mod-comment", 801L);
        when(redisService.isMessageProcessed("mod-comment")).thenReturn(false);
        Comment comment = new Comment();
        comment.setId(801L);
        comment.setUserId(43L);
        comment.setContent("这是一条评论");
        when(commentMapper.selectById(801L)).thenReturn(comment);

        listener.handleModeration(message, channel, rawMessage(24L));

        verify(moderationService).moderate(
                eq(ModerationTargetType.COMMENT), eq(801L), eq("这是一条评论"), eq(43L));
        verify(channel).basicAck(24L, false);
    }

    @Test
    void duplicateMessageShouldOnlyAck() throws Exception {
        ModerationMessage message = articleMessage("mod-duplicate", 704L);
        when(redisService.isMessageProcessed("mod-duplicate")).thenReturn(true);

        listener.handleModeration(message, channel, rawMessage(25L));

        verify(channel).basicAck(25L, false);
        verifyNoInteractions(moderationService);
        verify(articleMapper, never()).selectById(anyLong());
        verify(redisService, never()).markMessageProcessed(anyString());
    }

    @Test
    void failureShouldNackToDeadLetterQueue() throws Exception {
        ModerationMessage message = commentMessage("mod-failed", 802L);
        when(redisService.isMessageProcessed("mod-failed")).thenReturn(false);
        Comment comment = new Comment();
        comment.setId(802L);
        comment.setUserId(43L);
        comment.setContent("内容");
        when(commentMapper.selectById(802L)).thenReturn(comment);
        doThrow(new IllegalStateException("审核服务不可用"))
                .when(moderationService).moderate(any(), anyLong(), anyString(), anyLong());

        listener.handleModeration(message, channel, rawMessage(26L));

        verify(channel).basicNack(26L, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(redisService, never()).markMessageProcessed(anyString());
    }

    // ==================== 辅助方法 ====================

    private ModerationMessage articleMessage(String messageId, Long articleId) {
        ModerationMessage message = new ModerationMessage();
        message.setMessageId(messageId);
        message.setTargetType(ModerationMessage.TARGET_ARTICLE);
        message.setTargetId(articleId);
        return message;
    }

    private ModerationMessage commentMessage(String messageId, Long commentId) {
        ModerationMessage message = new ModerationMessage();
        message.setMessageId(messageId);
        message.setTargetType(ModerationMessage.TARGET_COMMENT);
        message.setTargetId(commentId);
        return message;
    }

    private Article article(Long id, String status, Long userId) {
        Article article = new Article();
        article.setId(id);
        article.setStatus(status);
        article.setUserId(userId);
        article.setTitle("测试标题");
        article.setContent("测试正文内容");
        return article;
    }

    private Message rawMessage(long deliveryTag) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(deliveryTag);
        return new Message(new byte[0], properties);
    }
}

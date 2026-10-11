package com.bitforum.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bitforum.config.RabbitMQConfig;
import com.bitforum.entity.Article;
import com.bitforum.entity.Comment;
import com.bitforum.entity.Notification;
import com.bitforum.mapper.ArticleMapper;
import com.bitforum.mapper.CommentMapper;
import com.bitforum.mapper.NotificationMapper;
import com.bitforum.message.ModerationMessage;

/**
 * 审核触发点测试（M16）。
 *
 * <p>验证 M16 的两个接入点确实投递了审核消息：
 * 文章「提交审核」后、评论「发布」后。
 *
 * <p><b>刻意不加 {@code @Transactional}</b>：发送逻辑在事务提交后才执行，
 * 测试事务一旦回滚，消息根本不会投递，就验证不到真实行为
 * （与 {@code KbIndexTriggerTest} 同一个原因）。因此这里让事务真实提交并手工清理数据。
 */
@SpringBootTest
class ModerationTriggerTest {

    private static final Long AUTHOR_ID = 88611L;
    private static final Long COMMENTER_ID = 88612L;
    private static final Long CATEGORY_ID = 1L;

    @Autowired
    private ArticleService articleService;
    @Autowired
    private CommentService commentService;
    @Autowired
    private ArticleMapper articleMapper;
    @Autowired
    private CommentMapper commentMapper;
    @Autowired
    private NotificationMapper notificationMapper;

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    private Long articleId;

    @AfterEach
    void cleanUp() {
        if (articleId != null) {
            commentMapper.delete(new QueryWrapper<Comment>().eq("article_id", articleId));
            articleMapper.deleteById(articleId);
            articleId = null;
        }
        notificationMapper.delete(new QueryWrapper<Notification>().eq("receiver_id", COMMENTER_ID));
    }

    @Test
    void submittingDraftArticleShouldSendModerationMessage() {
        Article article = insertArticle(ArticleService.STATUS_DRAFT);

        articleService.submit(AUTHOR_ID, article.getId());

        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMQConfig.ARTICLE_EXCHANGE),
                eq(RabbitMQConfig.MODERATION_ROUTING_KEY),
                any(ModerationMessage.class),
                any(MessagePostProcessor.class));
    }

    @Test
    void publishingCommentShouldSendModerationMessage() {
        Article article = insertArticle(ArticleService.STATUS_PUBLISHED);

        boolean published = commentService.publish(COMMENTER_ID, article.getId(), "AI 是怎么审核内容的？");

        assertTrue(published, "评论应发布成功");
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMQConfig.ARTICLE_EXCHANGE),
                eq(RabbitMQConfig.MODERATION_ROUTING_KEY),
                any(ModerationMessage.class),
                any(MessagePostProcessor.class));
    }

    private Article insertArticle(String status) {
        Article article = new Article();
        article.setTitle("审核触发点测试");
        article.setContent("用于验证文章提交审核后是否投递了审核消息。");
        article.setUserId(AUTHOR_ID);
        article.setCategoryId(CATEGORY_ID);
        article.setStatus(status);
        article.setCreateTime(LocalDateTime.now());
        article.setUpdateTime(LocalDateTime.now());
        articleMapper.insert(article);
        articleId = article.getId();
        return article;
    }
}

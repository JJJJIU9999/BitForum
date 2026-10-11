package com.bitforum.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doThrow;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bitforum.dto.UserProfileUpdateRequest;
import com.bitforum.entity.Article;
import com.bitforum.entity.Category;
import com.bitforum.entity.Comment;
import com.bitforum.entity.TextReviewRecord;
import com.bitforum.entity.User;
import com.bitforum.mapper.ArticleMapper;
import com.bitforum.mapper.CategoryMapper;
import com.bitforum.mapper.CommentMapper;
import com.bitforum.mapper.NotificationMapper;
import com.bitforum.mapper.TextReviewRecordMapper;
import com.bitforum.mapper.UserMapper;

@SpringBootTest
@Transactional
class TextModerationIntegrationTest {
    @Autowired private TextRuleService ruleService;
    @Autowired private TextReviewService reviewService;
    @Autowired private TextRescanService rescanService;
    @Autowired private CommentService commentService;
    @Autowired private ArticleService articleService;
    @Autowired private UserService userService;
    @Autowired private ArticleMapper articleMapper;
    @Autowired private CategoryMapper categoryMapper;
    @Autowired private CommentMapper commentMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private NotificationMapper notificationMapper;
    @Autowired private TextReviewRecordMapper reviewMapper;
    @MockitoBean private RabbitTemplate rabbitTemplate;
    @MockitoBean private RedisService redisService;

    @Test
    void normalizationHandlesFullWidthCaseAndZeroWidth() {
        assertEquals("abc", TextRuleService.normalize("Ａ\u200bＢ\u200dＣ"));
        assertEquals("abc", TextRuleService.normalize("aBc"));
    }

    @Test
    void matchingCommentIsInvisibleAndNotNotifiedUntilApproval() {
        Article article = publishedArticle("普通文章");
        String phrase = "c-m17-" + UUID.randomUUID();
        ruleService.create(phrase, 1L);
        String content = "命中 " + phrase;
        assertTrue(commentService.publish(40002L, article.getId(), content));

        Comment comment = commentMapper.selectOne(new QueryWrapper<Comment>()
                .eq("article_id", article.getId()).eq("user_id", 40002L).orderByDesc("id").last("LIMIT 1"));
        assertEquals(CommentService.STATUS_PENDING, comment.getStatus());
        assertTrue(commentService.listByArticleId(article.getId()).isEmpty());
        assertEquals(0L, notificationMapper.selectCount(new QueryWrapper<com.bitforum.entity.Notification>()
                .eq("article_id", article.getId()).eq("type", NotificationService.TYPE_COMMENT)));

        TextReviewRecord review = reviewMapper.selectOne(new QueryWrapper<TextReviewRecord>()
                .eq("target_type", TextReviewService.COMMENT).eq("target_id", comment.getId()));
        assertTrue(reviewService.pagePending(1, 100).getRecords().stream()
                .anyMatch(item -> item.getId().equals(review.getId()) && content.equals(item.getTargetText())));
        reviewService.decide(review.getId(), 1L, true, null);
        assertEquals(CommentService.STATUS_PUBLISHED, commentMapper.selectById(comment.getId()).getStatus());
        assertEquals(1, commentService.listByArticleId(article.getId()).size());
        assertEquals(1L, notificationMapper.selectCount(new QueryWrapper<com.bitforum.entity.Notification>()
                .eq("article_id", article.getId()).eq("type", NotificationService.TYPE_COMMENT)));
    }

    @Test
    void matchingProfileKeepsOldPublicTextUntilApproval() {
        User user = new User();
        user.setUsername("m17-user-" + UUID.randomUUID());
        user.setPassword("test-hash");
        user.setRole("USER");
        user.setStatus(1);
        user.setNickname("原昵称");
        user.setPublicProfileStatus("PUBLISHED");
        userMapper.insert(user);
        String phrase = "c-m17-" + UUID.randomUUID();
        ruleService.create(phrase, 1L);

        UserProfileUpdateRequest request = new UserProfileUpdateRequest();
        request.setNickname("候选 " + phrase);
        request.setBio("候选简介");
        assertTrue(userService.updateProfile(user.getId(), request).isPendingTextReview());
        assertEquals("原昵称", userService.getPublicProfile(user.getId()).getNickname());

        TextReviewRecord review = reviewMapper.selectOne(new QueryWrapper<TextReviewRecord>()
                .eq("target_type", TextReviewService.PROFILE).eq("target_id", user.getId()));
        reviewService.decide(review.getId(), 1L, true, null);
        assertEquals(request.getNickname(), userService.getPublicProfile(user.getId()).getNickname());
    }

    @Test
    void rescanHoldsPublishedArticleOnce() {
        String phrase = "c-m17-" + UUID.randomUUID();
        Article article = publishedArticle("历史 " + phrase);
        ruleService.create(phrase, 1L);
        doThrow(new IllegalStateException("Redis 不可用"))
                .when(redisService).deleteArticleData(article.getId());
        TextRescanService.Preview preview = rescanService.preview(1L);
        assertTrue(preview.matched() >= 1);
        String cursor = null;
        TextRescanService.Batch batch;
        do {
            batch = rescanService.execute(preview.ruleVersion(), cursor, 1L);
            cursor = batch.nextCursor();
        } while (!batch.complete());
        assertEquals(ArticleService.STATUS_PENDING, articleMapper.selectById(article.getId()).getStatus());
        assertEquals(1L, reviewMapper.selectCount(new QueryWrapper<TextReviewRecord>()
                .eq("target_type", TextReviewService.ARTICLE).eq("target_id", article.getId())));
        assertEquals(0, rescanService.preview(1L).matched());
    }

    @Test
    void aiCannotApproveArticleThatHitsRule() {
        String phrase = "c-m17-" + UUID.randomUUID();
        Article article = publishedArticle("待审 " + phrase);
        article.setStatus(ArticleService.STATUS_PENDING);
        articleMapper.updateById(article);
        ruleService.create(phrase, 1L);

        assertFalse(articleService.approve(article.getId(), 0L));
        assertEquals(ArticleService.STATUS_PENDING, articleMapper.selectById(article.getId()).getStatus());
        assertEquals(1L, reviewMapper.selectCount(new QueryWrapper<TextReviewRecord>()
                .eq("target_type", TextReviewService.ARTICLE).eq("target_id", article.getId())
                .eq("status", TextReviewService.PENDING)));
    }

    @Test
    void changedRulesInvalidateRescanPreview() {
        String phrase = "c-m17-" + UUID.randomUUID();
        publishedArticle("历史 " + phrase);
        var rule = ruleService.create(phrase, 1L);
        long previewVersion = rescanService.preview(1L).ruleVersion();
        ruleService.update(rule.getId(), null, false, 1L);

        assertThrows(com.bitforum.exception.BusinessException.class, () -> rescanService.execute(previewVersion, null, 1L));
    }

    @Test
    void rescanCannotExecuteWithoutPreviewBySameAdmin() {
        assertThrows(com.bitforum.exception.BusinessException.class,
                () -> rescanService.execute(ruleService.currentVersion(), null, 99999L));
    }

    @Test
    void deletingPendingCommentClosesReviewTask() {
        Article article = publishedArticle("普通文章");
        String phrase = "c-m17-" + UUID.randomUUID();
        ruleService.create(phrase, 1L);
        commentService.publish(40002L, article.getId(), phrase);
        Comment comment = commentMapper.selectOne(new QueryWrapper<Comment>()
                .eq("article_id", article.getId()).orderByDesc("id").last("LIMIT 1"));
        assertTrue(commentService.deleteByAdmin(comment.getId()));
        assertEquals(0L, reviewMapper.selectCount(new QueryWrapper<TextReviewRecord>()
                .eq("target_type", TextReviewService.COMMENT).eq("target_id", comment.getId())
                .eq("status", TextReviewService.PENDING)));
    }

    private Article publishedArticle(String title) {
        Category category = categoryMapper.selectList(null).get(0);
        Article article = new Article();
        article.setTitle(title);
        article.setContent("用于文字审核集成测试");
        article.setUserId(40001L);
        article.setCategoryId(category.getId());
        article.setStatus(ArticleService.STATUS_PUBLISHED);
        articleMapper.insert(article);
        return article;
    }
}

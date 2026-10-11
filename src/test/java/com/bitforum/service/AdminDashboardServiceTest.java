package com.bitforum.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import com.bitforum.dto.AdminDashboardSummaryResponse;
import com.bitforum.dto.DashboardHotArticle;
import com.bitforum.entity.Article;
import com.bitforum.entity.ArticleFavorite;
import com.bitforum.entity.Category;
import com.bitforum.entity.Comment;
import com.bitforum.entity.ContentReport;
import com.bitforum.entity.Notification;
import com.bitforum.entity.User;
import com.bitforum.mapper.ArticleFavoriteMapper;
import com.bitforum.mapper.ArticleMapper;
import com.bitforum.mapper.CategoryMapper;
import com.bitforum.mapper.CommentMapper;
import com.bitforum.mapper.ContentReportMapper;
import com.bitforum.mapper.NotificationMapper;
import com.bitforum.mapper.UserMapper;

@SpringBootTest
@Transactional
class AdminDashboardServiceTest {
    @Autowired
    private AdminDashboardService adminDashboardService;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private ArticleMapper articleMapper;
    @Autowired
    private CommentMapper commentMapper;
    @Autowired
    private CategoryMapper categoryMapper;
    @Autowired
    private ArticleFavoriteMapper articleFavoriteMapper;
    @Autowired
    private NotificationMapper notificationMapper;
    @Autowired
    private ContentReportMapper contentReportMapper;
    @MockitoBean
    private RedisService redisService;

    @Test
    void summaryShouldAggregateCoreStats() {
        AdminDashboardSummaryResponse before = adminDashboardService.summary();

        Category enabledCategory = createCategory("M6启用板块", 1);
        Category disabledCategory = createCategory("M6禁用板块", 0);

        createUser("m6-user-enabled", "USER", UserService.STATUS_ENABLED);
        createUser("m6-admin-enabled", UserService.ROLE_ADMIN, UserService.STATUS_ENABLED);
        createUser("m6-user-disabled", "USER", UserService.STATUS_DISABLED);

        Article draft = createArticle(enabledCategory.getId(), ArticleService.STATUS_DRAFT, 0);
        createArticle(enabledCategory.getId(), ArticleService.STATUS_PENDING, 0);
        Article published = createArticle(enabledCategory.getId(), ArticleService.STATUS_PUBLISHED, 0);
        createArticle(enabledCategory.getId(), ArticleService.STATUS_REJECTED, 0);
        createArticle(disabledCategory.getId(), ArticleService.STATUS_OFFLINE, 8);

        createComment(published.getId(), 0);
        createComment(published.getId(), 8);
        createFavorite(published.getId(), 72001L);
        createNotification(published.getId(), NotificationService.READ_STATUS_UNREAD);
        createNotification(draft.getId(), NotificationService.READ_STATUS_READ);
        createReport(ContentReportService.STATUS_PENDING, 0);
        createReport(ContentReportService.STATUS_RESOLVED, 0);
        createReport(ContentReportService.STATUS_REJECTED, 8);

        AdminDashboardSummaryResponse after = adminDashboardService.summary();

        assertEquals(before.getUserStats().getTotal() + 3, after.getUserStats().getTotal());
        assertEquals(before.getUserStats().getNormalUsers() + 2, after.getUserStats().getNormalUsers());
        assertEquals(before.getUserStats().getAdmins() + 1, after.getUserStats().getAdmins());
        assertEquals(before.getUserStats().getEnabled() + 2, after.getUserStats().getEnabled());
        assertEquals(before.getUserStats().getDisabled() + 1, after.getUserStats().getDisabled());

        assertEquals(before.getArticleStats().getTotal() + 5, after.getArticleStats().getTotal());
        assertEquals(before.getArticleStats().getDraft() + 1, after.getArticleStats().getDraft());
        assertEquals(before.getArticleStats().getPending() + 1, after.getArticleStats().getPending());
        assertEquals(before.getArticleStats().getPublished() + 1, after.getArticleStats().getPublished());
        assertEquals(before.getArticleStats().getRejected() + 1, after.getArticleStats().getRejected());
        assertEquals(before.getArticleStats().getOffline() + 1, after.getArticleStats().getOffline());
        assertEquals(before.getArticleStats().getTodayCreated() + 4, after.getArticleStats().getTodayCreated());
        assertEquals(before.getArticleStats().getLast7DaysCreated() + 4,
                after.getArticleStats().getLast7DaysCreated());

        assertEquals(before.getCommentStats().getTotal() + 2, after.getCommentStats().getTotal());
        assertEquals(before.getCommentStats().getTodayCreated() + 1, after.getCommentStats().getTodayCreated());
        assertEquals(before.getCommentStats().getLast7DaysCreated() + 1,
                after.getCommentStats().getLast7DaysCreated());

        assertEquals(before.getCategoryStats().getTotal() + 2, after.getCategoryStats().getTotal());
        assertEquals(before.getCategoryStats().getEnabled() + 1, after.getCategoryStats().getEnabled());
        assertEquals(before.getCategoryStats().getDisabled() + 1, after.getCategoryStats().getDisabled());

        assertEquals(before.getFavoriteStats().getTotal() + 1, after.getFavoriteStats().getTotal());
        assertEquals(before.getNotificationStats().getTotal() + 2, after.getNotificationStats().getTotal());
        assertEquals(before.getNotificationStats().getUnread() + 1, after.getNotificationStats().getUnread());

        assertEquals(before.getReportStats().getTotal() + 3, after.getReportStats().getTotal());
        assertEquals(before.getReportStats().getPending() + 1, after.getReportStats().getPending());
        assertEquals(before.getReportStats().getResolved() + 1, after.getReportStats().getResolved());
        assertEquals(before.getReportStats().getRejected() + 1, after.getReportStats().getRejected());
        assertEquals(before.getReportStats().getTodayCreated() + 2, after.getReportStats().getTodayCreated());
        assertEquals(before.getReportStats().getLast7DaysCreated() + 2, after.getReportStats().getLast7DaysCreated());
    }

    @Test
    void summaryShouldReturnEmptyHotArticlesWhenRedisHasNoData() {
        when(redisService.getHotList(10)).thenReturn(Collections.emptySet());

        AdminDashboardSummaryResponse response = adminDashboardService.summary();

        assertTrue(response.getHotArticles().isEmpty());
    }

    @Test
    void summaryShouldReturnPublishedHotArticlesFromRedis() {
        Category category = createCategory("M6热榜板块", 1);
        Article published = createArticle(category.getId(), ArticleService.STATUS_PUBLISHED, 1);
        Article offline = createArticle(category.getId(), ArticleService.STATUS_OFFLINE, 1);

        when(redisService.getHotList(10)).thenReturn(Set.of(
                new DefaultTypedTuple<>(published.getId().toString(), 20.0),
                new DefaultTypedTuple<>(offline.getId().toString(), 30.0),
                new DefaultTypedTuple<>("invalid", 40.0)));
        when(redisService.getViews(published.getId())).thenReturn(12L);
        when(redisService.getLikeCount(published.getId())).thenReturn(3L);

        AdminDashboardSummaryResponse response = adminDashboardService.summary();

        assertEquals(1, response.getHotArticles().size());
        DashboardHotArticle hotArticle = response.getHotArticles().get(0);
        assertEquals(published.getId(), hotArticle.getArticleId());
        assertEquals(published.getTitle(), hotArticle.getTitle());
        assertEquals(20L, hotArticle.getHotScore());
        assertEquals(12, hotArticle.getViewCount());
        assertEquals(3, hotArticle.getLikeCount());
    }

    private User createUser(String prefix, String role, Integer status) {
        User user = new User();
        user.setUsername("m6" + UUID.randomUUID().toString().substring(0, 8));
        user.setPassword("encoded-password");
        user.setRole(role);
        user.setStatus(status);
        userMapper.insert(user);
        return user;
    }

    private Category createCategory(String prefix, Integer status) {
        Category category = new Category();
        category.setName(prefix + "-" + UUID.randomUUID());
        category.setDescription("M6 数据看板测试板块");
        category.setSortOrder(1);
        category.setStatus(status);
        categoryMapper.insert(category);
        return category;
    }

    private Article createArticle(Long categoryId, String status, int daysAgo) {
        Article article = new Article();
        article.setTitle("M6-" + UUID.randomUUID());
        article.setContent("M6 数据看板测试文章");
        article.setUserId(71001L);
        article.setCategoryId(categoryId);
        article.setStatus(status);
        article.setCreateTime(LocalDate.now().minusDays(daysAgo).atStartOfDay());
        articleMapper.insert(article);
        return article;
    }

    private Comment createComment(Long articleId, int daysAgo) {
        Comment comment = new Comment();
        comment.setContent("M6 数据看板测试评论");
        comment.setUserId(71002L);
        comment.setArticleId(articleId);
        comment.setParentCommentId(0L);
        comment.setCreateTime(LocalDate.now().minusDays(daysAgo).atStartOfDay());
        commentMapper.insert(comment);
        return comment;
    }

    private void createFavorite(Long articleId, Long userId) {
        ArticleFavorite favorite = new ArticleFavorite();
        favorite.setArticleId(articleId);
        favorite.setUserId(userId);
        articleFavoriteMapper.insert(favorite);
    }

    private void createNotification(Long articleId, int readStatus) {
        Notification notification = new Notification();
        notification.setReceiverId(71003L);
        notification.setSenderId(71004L);
        notification.setType(NotificationService.TYPE_COMMENT);
        notification.setTitle("M6 通知");
        notification.setContent("M6 数据看板测试通知");
        notification.setArticleId(articleId);
        notification.setReadStatus(readStatus);
        notificationMapper.insert(notification);
    }

    private void createReport(String status, int daysAgo) {
        ContentReport report = new ContentReport();
        report.setReporterId(71005L);
        report.setTargetType(ContentReportService.TARGET_ARTICLE);
        report.setTargetId(71006L);
        report.setTargetOwnerId(71007L);
        report.setReason("M6 数据看板测试举报");
        report.setStatus(status);
        report.setCreateTime(LocalDate.now().minusDays(daysAgo).atStartOfDay());
        contentReportMapper.insert(report);
    }
}

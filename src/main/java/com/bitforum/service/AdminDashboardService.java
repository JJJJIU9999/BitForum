package com.bitforum.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bitforum.dto.AdminDashboardSummaryResponse;
import com.bitforum.dto.DashboardArticleStats;
import com.bitforum.dto.DashboardCategoryStats;
import com.bitforum.dto.DashboardCommentStats;
import com.bitforum.dto.DashboardFavoriteStats;
import com.bitforum.dto.DashboardHotArticle;
import com.bitforum.dto.DashboardNotificationStats;
import com.bitforum.dto.DashboardReportStats;
import com.bitforum.dto.DashboardUserStats;
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

@Service
public class AdminDashboardService {
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
    @Autowired
    private RedisService redisService;
    @Autowired
    private ArticleService articleService;

    public AdminDashboardSummaryResponse summary() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime last7DaysStart = LocalDate.now().minusDays(6).atStartOfDay();

        AdminDashboardSummaryResponse response = new AdminDashboardSummaryResponse();
        response.setUserStats(buildUserStats());
        response.setArticleStats(buildArticleStats(todayStart, last7DaysStart));
        response.setCommentStats(buildCommentStats(todayStart, last7DaysStart));
        response.setCategoryStats(buildCategoryStats());
        response.setFavoriteStats(buildFavoriteStats());
        response.setNotificationStats(buildNotificationStats());
        response.setReportStats(buildReportStats(todayStart, last7DaysStart));
        response.setHotArticles(buildHotArticles());
        return response;
    }

    private DashboardUserStats buildUserStats() {
        DashboardUserStats stats = new DashboardUserStats();
        stats.setTotal(userMapper.selectCount(null));
        stats.setNormalUsers(userMapper.selectCount(new QueryWrapper<User>().eq("role", "USER")));
        stats.setAdmins(userMapper.selectCount(new QueryWrapper<User>().eq("role", UserService.ROLE_ADMIN)));
        stats.setEnabled(userMapper.selectCount(new QueryWrapper<User>().eq("status", UserService.STATUS_ENABLED)));
        stats.setDisabled(userMapper.selectCount(new QueryWrapper<User>().eq("status", UserService.STATUS_DISABLED)));
        return stats;
    }

    private DashboardArticleStats buildArticleStats(LocalDateTime todayStart, LocalDateTime last7DaysStart) {
        DashboardArticleStats stats = new DashboardArticleStats();
        stats.setTotal(articleMapper.selectCount(null));
        stats.setDraft(countArticlesByStatus(ArticleService.STATUS_DRAFT));
        stats.setPending(countArticlesByStatus(ArticleService.STATUS_PENDING));
        stats.setPublished(countArticlesByStatus(ArticleService.STATUS_PUBLISHED));
        stats.setRejected(countArticlesByStatus(ArticleService.STATUS_REJECTED));
        stats.setOffline(countArticlesByStatus(ArticleService.STATUS_OFFLINE));
        stats.setTodayCreated(articleMapper.selectCount(new QueryWrapper<Article>().ge("create_time", todayStart)));
        stats.setLast7DaysCreated(articleMapper.selectCount(new QueryWrapper<Article>().ge("create_time", last7DaysStart)));
        return stats;
    }

    private DashboardCommentStats buildCommentStats(LocalDateTime todayStart, LocalDateTime last7DaysStart) {
        DashboardCommentStats stats = new DashboardCommentStats();
        stats.setTotal(commentMapper.selectCount(null));
        stats.setTodayCreated(commentMapper.selectCount(new QueryWrapper<Comment>().ge("create_time", todayStart)));
        stats.setLast7DaysCreated(commentMapper.selectCount(new QueryWrapper<Comment>().ge("create_time", last7DaysStart)));
        return stats;
    }

    private DashboardCategoryStats buildCategoryStats() {
        DashboardCategoryStats stats = new DashboardCategoryStats();
        stats.setTotal(categoryMapper.selectCount(null));
        stats.setEnabled(categoryMapper.selectCount(new QueryWrapper<Category>().eq("status", 1)));
        stats.setDisabled(categoryMapper.selectCount(new QueryWrapper<Category>().eq("status", 0)));
        return stats;
    }

    private DashboardFavoriteStats buildFavoriteStats() {
        DashboardFavoriteStats stats = new DashboardFavoriteStats();
        stats.setTotal(articleFavoriteMapper.selectCount(null));
        return stats;
    }

    private DashboardNotificationStats buildNotificationStats() {
        DashboardNotificationStats stats = new DashboardNotificationStats();
        stats.setTotal(notificationMapper.selectCount(null));
        stats.setUnread(notificationMapper.selectCount(
                new QueryWrapper<Notification>().eq("read_status", NotificationService.READ_STATUS_UNREAD)));
        return stats;
    }

    private DashboardReportStats buildReportStats(LocalDateTime todayStart, LocalDateTime last7DaysStart) {
        DashboardReportStats stats = new DashboardReportStats();
        stats.setTotal(contentReportMapper.selectCount(null));
        stats.setPending(countReportsByStatus(ContentReportService.STATUS_PENDING));
        stats.setResolved(countReportsByStatus(ContentReportService.STATUS_RESOLVED));
        stats.setRejected(countReportsByStatus(ContentReportService.STATUS_REJECTED));
        stats.setTodayCreated(contentReportMapper.selectCount(new QueryWrapper<ContentReport>().ge("create_time", todayStart)));
        stats.setLast7DaysCreated(contentReportMapper.selectCount(new QueryWrapper<ContentReport>().ge("create_time", last7DaysStart)));
        return stats;
    }

    private Long countArticlesByStatus(String status) {
        return articleMapper.selectCount(new QueryWrapper<Article>().eq("status", status));
    }

    private Long countReportsByStatus(String status) {
        return contentReportMapper.selectCount(new QueryWrapper<ContentReport>().eq("status", status));
    }

    private List<DashboardHotArticle> buildHotArticles() {
        try {
            Set<ZSetOperations.TypedTuple<String>> hotSet = redisService.getHotList(10);
            if (hotSet == null || hotSet.isEmpty()) {
                return Collections.emptyList();
            }

            List<DashboardHotArticle> hotArticles = new ArrayList<>();
            for (ZSetOperations.TypedTuple<String> tuple : hotSet) {
                DashboardHotArticle hotArticle = toHotArticle(tuple);
                if (hotArticle != null) {
                    hotArticles.add(hotArticle);
                }
            }
            return hotArticles;
        } catch (RuntimeException e) {
            return Collections.emptyList();
        }
    }

    private DashboardHotArticle toHotArticle(ZSetOperations.TypedTuple<String> tuple) {
        if (tuple == null || tuple.getValue() == null) {
            return null;
        }
        Long articleId;
        try {
            articleId = Long.parseLong(tuple.getValue());
        } catch (NumberFormatException e) {
            return null;
        }

        Article article = articleService.findPublishedById(articleId);
        if (article == null) {
            return null;
        }

        DashboardHotArticle hotArticle = new DashboardHotArticle();
        hotArticle.setArticleId(articleId);
        hotArticle.setTitle(article.getTitle());
        hotArticle.setHotScore(tuple.getScore() == null ? 0L : tuple.getScore().longValue());
        hotArticle.setViewCount(redisService.getViews(articleId).intValue());
        hotArticle.setLikeCount(redisService.getLikeCount(articleId).intValue());
        return hotArticle;
    }
}

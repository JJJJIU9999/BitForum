package com.bitforum.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bitforum.entity.Article;
import com.bitforum.entity.Category;
import com.bitforum.mapper.ArticleMapper;
import com.bitforum.mapper.CategoryMapper;

@SpringBootTest
@Transactional
class ArticleMetricSyncServiceTest {
    @Autowired
    private ArticleMetricSyncService articleMetricSyncService;
    @Autowired
    private ArticleMapper articleMapper;
    @Autowired
    private CategoryMapper categoryMapper;
    @MockitoBean
    private RedisService redisService;

    @Test
    void syncShouldPersistRedisViewAndLikeCountsToArticleTable() {
        Article article = createPublishedArticle(0, 0);
        // 先声明"其余已发布文章在 Redis 里都没有指标数据"，
        // 否则 Mockito 对未打桩的 Long 返回类型默认返回 0L（不是 null），
        // 会被 syncSingleArticle 误判为"有指标值 0"，从而更新无关文章、污染 updatedCount。
        stubNoRedisDataForExistingArticles();
        // 再用 eq() 把打桩精确限定到本篇，避免对全库文章返回同一份数据。
        when(redisService.getViewsIfPresent(eq(article.getId()))).thenReturn(12L);
        when(redisService.getLikeCountIfPresent(eq(article.getId()))).thenReturn(3L);

        int updatedCount = articleMetricSyncService.syncArticleMetrics();

        Article saved = articleMapper.selectById(article.getId());
        assertEquals(1, updatedCount);
        assertEquals(12, saved.getViewCount());
        assertEquals(3, saved.getLikeCount());
    }

    @Test
    void syncShouldKeepDatabaseCountsWhenRedisHasNoMetricData() {
        Article article = createPublishedArticle(7, 4);
        // 显式声明全库已发布文章在 Redis 中都没有指标数据，
        // 让本测试不再依赖"数据库里只有这一篇已发布文章"这一脆弱前提。
        stubNoRedisDataForExistingArticles();

        int updatedCount = articleMetricSyncService.syncArticleMetrics();

        Article saved = articleMapper.selectById(article.getId());
        assertEquals(0, updatedCount);
        assertEquals(7, saved.getViewCount());
        assertEquals(4, saved.getLikeCount());
    }

    @Test
    void syncShouldNotFailWhenRedisThrowsException() {
        Article article = createPublishedArticle(5, 2);
        when(redisService.getViewsIfPresent(article.getId())).thenThrow(new RuntimeException("redis unavailable"));

        assertDoesNotThrow(() -> articleMetricSyncService.syncArticleMetrics());

        Article saved = articleMapper.selectById(article.getId());
        assertEquals(5, saved.getViewCount());
        assertEquals(2, saved.getLikeCount());
    }

    /**
     * 显式声明"数据库中已有的已发布文章在 Redis 里没有指标数据"。
     *
     * 背景：syncArticleMetrics() 会遍历全库 PUBLISHED 文章，而 Mockito 对未打桩的
     * Long 返回类型默认返回 0L 而不是 null，会被误判为"指标值为 0"从而更新无关文章。
     * 因此凡涉及 updatedCount 精确断言的测试，都必须先调用本方法隔离已有数据。
     */
    private void stubNoRedisDataForExistingArticles() {
        List<Article> existing = articleMapper.selectList(
                new QueryWrapper<Article>().eq("status", ArticleService.STATUS_PUBLISHED));
        for (Article a : existing) {
            lenient().when(redisService.getViewsIfPresent(eq(a.getId()))).thenReturn(null);
            lenient().when(redisService.getLikeCountIfPresent(eq(a.getId()))).thenReturn(null);
        }
    }

    private Article createPublishedArticle(int viewCount, int likeCount) {
        Category category = categoryMapper.selectList(null).get(0);
        Article article = new Article();
        article.setTitle("M11-" + UUID.randomUUID());
        article.setContent("M11 Redis metric sync test article");
        article.setUserId(81101L);
        article.setCategoryId(category.getId());
        article.setStatus(ArticleService.STATUS_PUBLISHED);
        article.setViewCount(viewCount);
        article.setLikeCount(likeCount);
        articleMapper.insert(article);
        return article;
    }
}

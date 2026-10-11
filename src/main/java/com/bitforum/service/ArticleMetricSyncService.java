package com.bitforum.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bitforum.entity.Article;
import com.bitforum.mapper.ArticleMapper;

@Service
public class ArticleMetricSyncService {
    private static final Logger log = LoggerFactory.getLogger(ArticleMetricSyncService.class);

    @Autowired
    private ArticleMapper articleMapper;
    @Autowired
    private RedisService redisService;

    public int syncArticleMetrics() {
        List<Article> articles;
        try {
            articles = articleMapper.selectList(new QueryWrapper<Article>()
                    .eq("status", ArticleService.STATUS_PUBLISHED));
        } catch (RuntimeException e) {
            log.warn("Failed to load articles for Redis metric sync", e);
            return 0;
        }

        int updatedCount = 0;
        for (Article article : articles) {
            if (article == null || article.getId() == null) {
                continue;
            }
            try {
                if (syncSingleArticle(article)) {
                    updatedCount++;
                }
            } catch (RuntimeException e) {
                log.warn("Failed to sync Redis metrics for article {}", article.getId(), e);
            }
        }
        return updatedCount;
    }

    private boolean syncSingleArticle(Article article) {
        Long redisViews = redisService.getViewsIfPresent(article.getId());
        Long redisLikes = redisService.getLikeCountIfPresent(article.getId());
        if (redisViews == null && redisLikes == null) {
            return false;
        }

        boolean changed = false;
        if (redisViews != null) {
            int viewCount = toArticleCount(redisViews);
            if (article.getViewCount() != viewCount) {
                article.setViewCount(viewCount);
                changed = true;
            }
        }
        if (redisLikes != null) {
            int likeCount = toArticleCount(redisLikes);
            if (article.getLikeCount() != likeCount) {
                article.setLikeCount(likeCount);
                changed = true;
            }
        }

        if (!changed) {
            return false;
        }
        articleMapper.updateById(article);
        return true;
    }

    private int toArticleCount(Long count) {
        if (count == null || count <= 0) {
            return 0;
        }
        if (count > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return count.intValue();
    }
}

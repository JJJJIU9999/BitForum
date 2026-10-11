package com.bitforum.job;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.bitforum.service.ArticleMetricSyncService;

@Component
public class ArticleMetricSyncJob {
    @Autowired
    private ArticleMetricSyncService articleMetricSyncService;

    @Scheduled(
            fixedDelayString = "${bitforum.article-metric-sync.fixed-delay:300000}",
            initialDelayString = "${bitforum.article-metric-sync.initial-delay:300000}")
    public void syncArticleMetrics() {
        articleMetricSyncService.syncArticleMetrics();
    }
}

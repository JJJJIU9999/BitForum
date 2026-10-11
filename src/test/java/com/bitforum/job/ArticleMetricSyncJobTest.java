package com.bitforum.job;

import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.bitforum.service.ArticleMetricSyncService;

@SpringBootTest
class ArticleMetricSyncJobTest {
    @Autowired
    private ArticleMetricSyncJob articleMetricSyncJob;
    @MockitoBean
    private ArticleMetricSyncService articleMetricSyncService;

    @Test
    void scheduledMethodShouldDelegateToSyncService() {
        articleMetricSyncJob.syncArticleMetrics();

        verify(articleMetricSyncService).syncArticleMetrics();
    }
}

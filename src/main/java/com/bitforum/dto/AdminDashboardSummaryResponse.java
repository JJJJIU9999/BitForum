package com.bitforum.dto;

import java.util.List;

import lombok.Data;

@Data
public class AdminDashboardSummaryResponse {
    private DashboardUserStats userStats;
    private DashboardArticleStats articleStats;
    private DashboardCommentStats commentStats;
    private DashboardCategoryStats categoryStats;
    private DashboardFavoriteStats favoriteStats;
    private DashboardNotificationStats notificationStats;
    private DashboardReportStats reportStats;
    private List<DashboardHotArticle> hotArticles;
}

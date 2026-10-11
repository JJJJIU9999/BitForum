package com.bitforum.dto;

import lombok.Data;

@Data
public class DashboardArticleStats {
    private Long total;
    private Long draft;
    private Long pending;
    private Long published;
    private Long rejected;
    private Long offline;
    private Long todayCreated;
    private Long last7DaysCreated;
}

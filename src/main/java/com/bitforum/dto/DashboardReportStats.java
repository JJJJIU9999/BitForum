package com.bitforum.dto;

import lombok.Data;

@Data
public class DashboardReportStats {
    private Long total;
    private Long pending;
    private Long resolved;
    private Long rejected;
    private Long todayCreated;
    private Long last7DaysCreated;
}

package com.bitforum.dto;

import lombok.Data;

@Data
public class DashboardCommentStats {
    private Long total;
    private Long todayCreated;
    private Long last7DaysCreated;
}

package com.bitforum.dto;

import lombok.Data;

@Data
public class DashboardCategoryStats {
    private Long total;
    private Long enabled;
    private Long disabled;
}

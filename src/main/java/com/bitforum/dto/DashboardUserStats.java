package com.bitforum.dto;

import lombok.Data;

@Data
public class DashboardUserStats {
    private Long total;
    private Long normalUsers;
    private Long admins;
    private Long enabled;
    private Long disabled;
}

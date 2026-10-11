package com.bitforum.dto;

import java.time.LocalDateTime;

import lombok.Data;

@Data
public class AdminHealthResponse {
    private String overallStatus;
    private HealthComponentStatus application;
    private HealthComponentStatus mysql;
    private HealthComponentStatus redis;
    private HealthComponentStatus rabbitmq;
    private LocalDateTime checkedAt;
}

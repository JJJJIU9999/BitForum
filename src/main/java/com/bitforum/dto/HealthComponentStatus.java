package com.bitforum.dto;

import lombok.Data;

@Data
public class HealthComponentStatus {
    private String name;
    private String status;
    private String message;

    public static HealthComponentStatus of(String name, String status, String message) {
        HealthComponentStatus component = new HealthComponentStatus();
        component.setName(name);
        component.setStatus(status);
        component.setMessage(message);
        return component;
    }
}

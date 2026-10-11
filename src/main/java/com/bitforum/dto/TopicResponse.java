package com.bitforum.dto;

import java.time.LocalDateTime;

public record TopicResponse(Long categoryId, String name, String description, Integer status,
        boolean followedByCurrentUser, LocalDateTime followedAt) {}

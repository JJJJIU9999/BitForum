package com.bitforum.dto;

import lombok.Data;

@Data
public class DashboardHotArticle {
    private Long articleId;
    private String title;
    private Long hotScore;
    private Integer viewCount;
    private Integer likeCount;
}

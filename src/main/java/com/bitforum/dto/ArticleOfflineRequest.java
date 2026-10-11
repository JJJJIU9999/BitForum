package com.bitforum.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ArticleOfflineRequest {
    @NotNull(message = "文章ID不能为空")
    private Long articleId;

    @Size(max = 255, message = "下架原因不能超过255个字符")
    private String reason;
}

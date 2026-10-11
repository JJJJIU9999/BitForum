package com.bitforum.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ArticleAuditRejectRequest {
    @NotNull(message = "文章ID不能为空")
    private Long articleId;

    @NotBlank(message = "驳回原因不能为空")
    @Size(max = 255, message = "驳回原因不能超过255个字符")
    private String reason;
}

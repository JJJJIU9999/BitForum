package com.bitforum.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

@Data
public class CommentPublishRequest {
    // 发布评论只允许前端传文章 id 和评论内容，用户 id 仍然来自登录态
    @NotNull(message = "文章ID不能为空")
    @Positive(message = "文章ID必须为正整数")
    private Long articleId;

    @NotBlank(message = "评论区内容不能为空")
    private String content;

    @PositiveOrZero(message = "父评论ID不能为负数")
    private Long parentCommentId;
}

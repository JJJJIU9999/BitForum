package com.bitforum.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class AnswerLikeRequest {
    @NotNull(message = "回答ID不能为空")
    @Positive(message = "回答ID必须为正整数")
    private Long commentId;
}

package com.bitforum.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class AnswerAcceptRequest {
    @NotNull(message = "问题 ID 不能为空")
    @Positive
    private Long articleId;
    @NotNull(message = "回答 ID 不能为空")
    @Positive
    private Long answerId;
}

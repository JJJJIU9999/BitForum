package com.bitforum.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "新建 AI 会话请求")
public class AiConversationCreateRequest {
    @Schema(description = "会话标题；留空时使用默认标题「新对话」")
    @Size(max = 100, message = "会话标题不能超过 100 个字符")
    private String title;
}

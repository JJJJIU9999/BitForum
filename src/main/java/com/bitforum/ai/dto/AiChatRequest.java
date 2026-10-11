package com.bitforum.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "向 AI 助手发送消息请求")
public class AiChatRequest {
    /**
     * 用户提问内容。
     *
     * <p>S4：这里的 2000 与 {@code bitforum.ai.budget.max-input-chars} 的默认值**必须保持一致**。
     * 两者一个在 HTTP 入口、一个在 AI 链路内部；不一致时更严的那个会永远不可达，
     * 且两处对"超长输入"的报错口径会分裂。回归测试见 {@code AiInputLengthConsistencyTest}。
     */
    // 用户 id 一律来自登录态（LoginInterceptor 解析 JWT），不接受前端传入
    @NotBlank(message = "消息内容不能为空")
    @Size(max = 2000, message = "单条消息不能超过 2000 个字符")
    @Schema(description = "用户提问内容")
    private String content;
}

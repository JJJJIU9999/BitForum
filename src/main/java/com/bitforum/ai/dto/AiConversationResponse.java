package com.bitforum.ai.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "AI 会话摘要")
public class AiConversationResponse {
    @Schema(description = "会话 ID")
    private Long id;
    @Schema(description = "会话标题")
    private String title;
    @Schema(description = "Agent 类型：QA / MODERATION / ANALYST / RECOMMEND")
    private String agentType;
    @Schema(description = "会话内消息条数")
    private Integer messageCount;
    @Schema(description = "会话累计消耗 Token")
    private Integer totalTokens;
    @Schema(description = "创建时间")
    private LocalDateTime createTime;
    @Schema(description = "最后更新时间")
    private LocalDateTime updateTime;
}

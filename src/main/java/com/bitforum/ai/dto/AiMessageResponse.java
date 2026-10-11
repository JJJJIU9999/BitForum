package com.bitforum.ai.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.bitforum.ai.rag.Citation;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "AI 会话消息")
public class AiMessageResponse {
    @Schema(description = "消息 ID")
    private Long id;
    @Schema(description = "消息角色：user / assistant / system / tool")
    private String role;
    @Schema(description = "消息内容")
    private String content;
    @Schema(description = "该条消息消耗的总 Token")
    private Integer totalTokens;
    @Schema(description = "处理耗时（毫秒）")
    private Integer latencyMs;
    @Schema(description = "创建时间")
    private LocalDateTime createTime;
    /** M15：本轮回答引用的站内文章，前端据此渲染可点击的原帖链接 */
    @Schema(description = "RAG 引用来源；仅助手消息可能非空")
    private List<Citation> citations;
}

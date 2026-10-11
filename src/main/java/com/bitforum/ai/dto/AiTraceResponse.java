package com.bitforum.ai.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.bitforum.ai.trace.TraceStep;

import lombok.Data;

/**
 * 执行轨迹的返回体（M18 管理端）。
 *
 * <p>与实体分开的原因：{@code steps} 在库里是 JSON 字符串，前端要的是**已解析的步骤数组**；
 * 列表页又不需要步骤明细（只要"N 步"）。把"存什么"和"传什么"分开，
 * 列表接口就不会把每行的 JSON 都塞进响应里。
 */
@Data
public class AiTraceResponse {

    private Long id;

    private String traceId;

    private String scene;

    private String agentType;

    private Long userId;

    private Long conversationId;

    private String refType;

    private Long refId;

    private String status;

    private String route;

    private String model;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    private Integer latencyMs;

    private Integer stepCount;

    /** 步骤明细；列表页为 null，详情接口才填充 */
    private List<TraceStep> steps;

    /** 降级原因码；未降级为 null */
    private String degradeReason;

    /** 面向人的说明（降级原因 / 失败原因） */
    private String message;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

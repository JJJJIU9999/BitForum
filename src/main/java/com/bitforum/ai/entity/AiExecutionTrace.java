package com.bitforum.ai.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * AI 执行轨迹实体，对应 {@code ai_execution_trace} 表（M18）。
 *
 * <p>一行 = 一次 AI 调用（含确定性链路中的推荐排序）。字段设计依据见迁移脚本
 * {@code V18__add_ai_execution_trace.sql} 与 {@code m18-decision-brief.md}。
 *
 * <p><b>为什么状态与降级原因分成两组常量</b>：{@code status} 回答"这次调用最终怎么样"，
 * {@code degradeReason} 回答"如果降级了，是因为什么"。M18 验收第 3 条要求
 * "AI 不可用时全站有统一降级表现，且**用户能看懂为什么降级**" ——
 * 前者是给人看的 {@code message}，后者是可以做统计的原因码。
 */
@Data
@TableName("ai_execution_trace")
public class AiExecutionTrace {

    // ==================== 场景（scene） ====================

    /** AI 助手对话（QA Agent） */
    public static final String SCENE_CHAT = "CHAT";
    /** 内容审核（MODERATION Agent，MQ 异步） */
    public static final String SCENE_MODERATION = "MODERATION";
    /** 运营洞察（ANALYST Agent，线程池异步） */
    public static final String SCENE_INSIGHT = "INSIGHT";
    /** 智能推荐的理由生成（RECOMMEND Agent） */
    public static final String SCENE_RECOMMEND = "RECOMMEND";

    // ==================== 状态（status） ====================

    /** 进行中：同步段已写入，异步段还没回来 */
    public static final String STATUS_RUNNING = "RUNNING";
    /** 正常完成 */
    public static final String STATUS_SUCCESS = "SUCCESS";
    /** 走了降级链路：主流程仍可用，但结果不是模型产出的 */
    public static final String STATUS_DEGRADED = "DEGRADED";
    /** 异常结束 */
    public static final String STATUS_FAILED = "FAILED";

    // ==================== 业务引用（ref_type） ====================

    /** 审核对象是文章 */
    public static final String REF_ARTICLE = "ARTICLE";
    /** 审核对象是评论 */
    public static final String REF_COMMENT = "COMMENT";
    /** 推荐的来源文章 */
    public static final String REF_SOURCE_ARTICLE = "SOURCE_ARTICLE";
    /** AI 会话 */
    public static final String REF_CONVERSATION = "CONVERSATION";

    @TableId(type = IdType.AUTO)
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

    /** 步骤数组 JSON，形如 {@code [{"seq":1,"type":"ROUTE",...}]} */
    private String steps;

    private String degradeReason;

    private String message;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

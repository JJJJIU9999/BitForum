package com.bitforum.ai.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * AI 用量明细实体，对应 {@code ai_usage_stat} 表（M18）。
 *
 * <p>一行 = 一次真实的大模型调用。设计依据见迁移脚本
 * {@code V19__add_ai_usage_stat.sql} 与 {@code m18-decision-brief.md} 的 Q1。
 *
 * <p><b>为什么这张表由 {@code TraceRecorder} 统一写入</b>：
 * M18 的四个 Agent 都已经被轨迹覆盖，把用量写在"轨迹收尾"同一处，
 * 就不存在"某个 Agent 忘了记 token"的可能 —— 这正是 Q1 选择"统一埋点"而不是
 * "各自写各自的用量"的原因。
 */
@Data
@TableName("ai_usage_stat")
public class AiUsageStat {

    /** 调用正常完成 */
    public static final String RESULT_SUCCESS = "SUCCESS";
    /** 走了降级链路（token 可能已消耗，但结果不是模型产出的） */
    public static final String RESULT_DEGRADED = "DEGRADED";
    /** 调用失败 */
    public static final String RESULT_FAILED = "FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String traceId;

    private String scene;

    private String agentType;

    private Long userId;

    private String model;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    private Integer latencyMs;

    private String result;

    /** 按配置单价估算的费用（元）；单价变动只影响此后写入的行 */
    private BigDecimal estimatedCost;

    private LocalDate statDate;

    private LocalDateTime createTime;
}

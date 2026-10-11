package com.bitforum.ai.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * AI 运营洞察报告实体，对应 ai_insight_report 表（M17）。
 *
 * <p><b>核心设计：报告正文与数据快照一起保存。</b>
 * {@code content} 是模型写的洞察，{@code dataSnapshot} 是生成这份报告时喂给模型的原始统计。
 * 只存正文的话，事后没人能回答"报告里那个数字当时对不对" —— 运营数据每天都在变，
 * 结论和依据必须成对留存。
 *
 * <p><b>与触发方式解耦</b>：{@code triggerType} 预留 MANUAL / SCHEDULED，
 * 先实现管理员手动触发，将来改为定时生成不需要动表结构，也不需要动本实体。
 */
@Data
@TableName("ai_insight_report")
public class AiInsightReport {

    /** 已受理，等待生成 */
    public static final String STATUS_PENDING = "PENDING";
    /** 生成成功 */
    public static final String STATUS_SUCCESS = "SUCCESS";
    /** 生成失败，errorMessage 中有原因 */
    public static final String STATUS_FAILED = "FAILED";

    /** 管理员手动触发 */
    public static final String TRIGGER_MANUAL = "MANUAL";
    /** 定时任务触发（预留） */
    public static final String TRIGGER_SCHEDULED = "SCHEDULED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String status;

    private String triggerType;

    /** 触发人（管理员 id）；定时任务触发时为空 */
    private Long requestedBy;

    /** 模型生成的洞察正文 */
    private String content;

    /** 生成时喂给模型的统计快照（JSON），用于事后对账 */
    private String dataSnapshot;

    /** 数据快照对应的时刻 */
    private LocalDateTime dataTime;

    private String model;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    private Integer latencyMs;

    private String errorMessage;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

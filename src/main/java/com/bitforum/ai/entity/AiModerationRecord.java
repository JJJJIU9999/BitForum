package com.bitforum.ai.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * AI 内容审核记录实体，对应 ai_moderation_record 表（M16）。
 *
 * <p><b>核心设计：AI 判断与系统动作分开保存。</b>
 * {@code decision} 是模型给出的判断（PASS / REVIEW / REJECT），
 * {@code action} 是系统实际执行的动作（是否自动放行、是否生成待处理记录）。
 * 两者可能不一致：例如文章判 PASS 但自动放行开关关闭时，行动作是 PENDING_REVIEW。
 * 合并成一个字段就无法回答"AI 说了什么"与"系统做了什么"这两个不同的问题。
 */
@Data
@TableName("ai_moderation_record")
public class AiModerationRecord {

    /** 对象类型：ARTICLE / COMMENT，与 ModerationTargetType 对应 */
    public static final String TARGET_ARTICLE = "ARTICLE";
    public static final String TARGET_COMMENT = "COMMENT";

    /** 人工反馈：AI 判断正确 */
    public static final String FEEDBACK_CORRECT = "CORRECT";
    /** 人工反馈：AI 判断错误 */
    public static final String FEEDBACK_WRONG = "WRONG";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String targetType;

    private Long targetId;

    /** 内容摘要（截断），管理台列表直接展示 */
    private String targetPreview;

    private Long authorId;

    // ==================== AI 判断 ====================

    /** 三档判断：PASS / REVIEW / REJECT */
    private String decision;

    /** 模型对本次判断的置信度 0~1 */
    private Double confidence;

    private Double harmfulScore;
    private Double promotionScore;
    private Double fraudScore;
    private Double spamScore;
    private Double sensitiveScore;

    private String harmfulReason;
    private String promotionReason;
    private String fraudReason;
    private String spamReason;
    private String sensitiveReason;

    private String summary;

    // ==================== 系统计算（确定性） ====================

    /** 综合风险分 = 五维最大值，由 Java 计算，非模型输出 */
    private Double riskScore;

    private String maxDimension;

    private Double maxDimensionScore;

    // ==================== 系统动作 ====================

    /** 动作类型，取值见 ModerationAction */
    private String action;

    private String actionReason;

    /** 待处理优先级：0 无需处理 / 1 普通 / 2 高优先 */
    private Integer priority;

    private Boolean handled;

    private Long handledBy;

    private LocalDateTime handledTime;

    // ==================== 运行信息 ====================

    private String model;

    private Integer latencyMs;

    /** 分析失败时的原因；此时 decision 由系统兜底为 REVIEW */
    private String errorMessage;

    // ==================== 人工反馈 ====================

    /** CORRECT / WRONG，由管理员在审核台标记 */
    private String feedback;

    private Long feedbackBy;

    private LocalDateTime feedbackTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

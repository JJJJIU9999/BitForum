package com.bitforum.ai.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * AI 推荐记录实体，对应 ai_recommend_log 表（M17）。
 *
 * <p><b>这张表是 M17 验收的数据来源</b>："给出 Top-N 推荐，并与纯热榜基线对比命中率"。
 * 要能对比，就必须把推荐了什么、为什么推、用了哪几路信号完整记下来；
 * 只存最终列表，事后既解释不了排序也复现不了。
 *
 * <p><b>与排序方式解耦</b>：无论最终是"模型自选文章"还是"应用定序、模型只写理由"，
 * 记录字段完全相同。因此排序方式的取舍不影响本实体。
 *
 * <p><b>基线对比靠 experimentTag 区分批次</b>：同一批候选上分别写入推荐系统与
 * {@link #TAG_BASELINE_HOT} 两组记录，评估脚本按批次分组统计命中率。
 * 该字段为空表示线上真实请求，不参与实验统计。
 */
@Data
@TableName("ai_recommend_log")
public class AiRecommendLog {

    /** 文章详情页的「相关推荐」 */
    public static final String SCENE_ARTICLE_DETAIL = "ARTICLE_DETAIL";
    /** AI 助手回答末尾的「相关帖」 */
    public static final String SCENE_ASSISTANT = "ASSISTANT";
    /** 管理台预览 */
    public static final String SCENE_ADMIN = "ADMIN";

    /** 纯热榜基线实验批次 */
    public static final String TAG_BASELINE_HOT = "BASELINE_HOT";
    /** 推荐系统实验批次 */
    public static final String TAG_RECOMMEND = "RECOMMEND";

    @TableId(type = IdType.AUTO)
    private Long id;

    // ==================== 请求上下文 ====================

    private String scene;

    /** 发起请求的用户；未登录访客为空 */
    private Long userId;

    /** 相关推荐的来源文章；AI 助手等没有来源文章的场景为空 */
    private Long sourceArticleId;

    // ==================== 推荐结果 ====================

    private Long articleId;

    /** 排名，从 1 开始 */
    private Integer rankNo;

    /** 融合分数：由 Java 按确定性规则计算，不由模型给出 */
    private BigDecimal score;

    /** 命中的召回通道，逗号分隔：vector / hot / follow */
    private String recallSources;

    /** 各路原始信号明细（JSON），用于复现排序与调权重 */
    private String scoreDetail;

    /** 推荐理由（模型生成）；为空表示降级或该场景不生成理由 */
    private String reason;

    // ==================== 评估用 ====================

    /** 实验批次：空 = 线上真实请求；非空 = 某次离线评测 */
    private String experimentTag;

    /** 是否命中评估真值（评估脚本回填）；null = 尚未判定 */
    private Boolean hit;

    // ==================== 运行信息 ====================

    private String model;

    private Integer latencyMs;

    /** 是否走了降级链路（无模型理由，但确定性排序仍然可用） */
    private Boolean degraded;

    private LocalDateTime createTime;
}

package com.bitforum.ai.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 知识库文档实体，对应 ai_kb_document 表（M15）。
 *
 * <p>一篇已发布文章对应一行，记录"这篇文章有没有进知识库"。向量本体存在 Redis，
 * 本表只保存映射与状态，用于统计、重建与引用溯源。
 */
@Data
@TableName("ai_kb_document")
public class AiKbDocument {

    /** 索引状态：待索引 */
    public static final String INDEX_STATUS_PENDING = "PENDING";
    /** 索引状态：已入库 */
    public static final String INDEX_STATUS_INDEXED = "INDEXED";
    /** 索引状态：失败（可重试） */
    public static final String INDEX_STATUS_FAILED = "FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 来源文章 id，数据库有唯一约束，保证同一篇文章不会被重复索引 */
    private Long articleId;

    private String title;

    private Long categoryId;

    /** 文章作者，与 article.user_id 对应 */
    private Long authorId;

    /** 索引时的文章状态；只有 PUBLISHED 的文章进入知识库 */
    private String status;

    /** 文章发布时间，取自 article.create_time */
    private LocalDateTime publishTime;

    /** 「标题 + 正文」的 SHA-256 指纹，用于跳过未变化的重复索引 */
    private String contentHash;

    /** 该文档切分出的分块数 */
    private Integer chunkCount;

    /** 索引状态，取值见本类的 INDEX_STATUS_* 常量 */
    private String indexStatus;

    /** 计算本文档向量所用的嵌入模型标识，换模型后据此识别需要重建的数据 */
    private String embeddingModel;

    /** 最近一次索引失败的原因 */
    private String lastError;

    private LocalDateTime indexTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

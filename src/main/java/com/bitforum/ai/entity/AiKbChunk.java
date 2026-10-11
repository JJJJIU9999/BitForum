package com.bitforum.ai.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 知识库分块实体，对应 ai_kb_chunk 表（M15）。
 *
 * <p>一篇文档切成的若干片段，每段对应 Redis 中的一条向量。原文保存在这里，
 * 检索命中后用它回填引用片段，同时也是全量重建时的数据来源。
 */
@Data
@TableName("ai_kb_chunk")
public class AiKbChunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属文档 id */
    private Long documentId;

    /** 冗余的文章 id，便于检索命中后直接构造引用链接 */
    private Long articleId;

    /** 分块序号，从 0 开始 */
    private Integer chunkIndex;

    /** 分块原文（含标题上下文） */
    private String content;

    private Integer charCount;

    /** 该分块在 Redis 中的向量 id，删除与重建时用于精确定位 */
    private String vectorId;

    private LocalDateTime createTime;
}

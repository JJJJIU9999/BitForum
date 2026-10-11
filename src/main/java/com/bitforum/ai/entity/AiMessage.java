package com.bitforum.ai.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * AI 消息实体，对应 ai_message 表（M13）。
 *
 * 除对话内容外，还承载工具调用记录、RAG 引用、Token 统计与耗时，
 * 为 M18 的执行轨迹可视化和效果评估提供数据基础。
 */
@Data
@TableName("ai_message")
public class AiMessage {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 所属会话，与 ai_conversation.id 对应 */
    private Long conversationId;
    /** 消息角色：user / assistant / system / tool */
    private String role;
    private String content;
    /** 工具调用原始 JSON（M14 起使用）；普通对话消息为 null */
    private String toolCalls;
    /** 命中的知识库文档 id，逗号分隔（M15 RAG 引用溯源使用） */
    private String retrievedDocIds;
    /** 本轮提问消耗的 Token（模型输入的计量） */
    private Integer promptTokens;
    /** 模型回答消耗的 Token */
    private Integer completionTokens;
    private Integer totalTokens;
    /** 该条消息的处理耗时（毫秒），用于 M18 执行轨迹 */
    private Integer latencyMs;
    private LocalDateTime createTime;
}

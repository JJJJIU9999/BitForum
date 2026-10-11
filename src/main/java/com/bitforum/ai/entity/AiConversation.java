package com.bitforum.ai.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * AI 会话实体，对应 ai_conversation 表（M13）。
 *
 * 一次对话上下文对应一行；M13 只有 QA（问答助手）一种类型，
 * M16-M17 会加入 MODERATION / ANALYST / RECOMMEND。
 */
@Data
@TableName("ai_conversation")
public class AiConversation {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 会话归属用户，与 user_info.id 对应（不建物理外键，与既有表设计一致） */
    private Long userId;
    /** 会话标题；M13 取用户首条消息的前若干字符自动生成 */
    private String title;
    /** Agent 类型，见 AgentType；数据库存字符串 */
    private String agentType;
    /** 会话内消息条数，用于列表展示与窗口裁剪 */
    private Integer messageCount;
    /** 会话累计消耗 Token，用于 M18 成本统计 */
    private Integer totalTokens;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

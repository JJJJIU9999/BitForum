package com.bitforum.ai.agent;

import java.util.List;

import org.springframework.ai.chat.messages.Message;

/**
 * Agent 统一接口（M13）。
 *
 * M13 只有 QaAgent 一个实现；M14 起会加入工具集，
 * M16-M17 会加入 MODERATION / ANALYST / RECOMMEND，届时由 AgentOrchestrator 按类型路由。
 */
public interface Agent {

    /** 本 Agent 的类型标识。 */
    AgentType type();

    /**
     * 执行一次对话。
     *
     * @param context 执行上下文（用户身份、会话、本次输入）
     * @param history 该会话的历史消息，按时间正序；不含本次输入
     * @return Agent 的回答，包含文本与 Token 统计
     */
    AgentResponse execute(AgentContext context, List<Message> history);
}

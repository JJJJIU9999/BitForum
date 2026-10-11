package com.bitforum.ai.agent;

/**
 * Agent 执行上下文（M13）。
 *
 * 承载一次 Agent 调用所需的身份与范围信息，避免各 Agent 直接依赖 Web 层对象。
 * M14 起会在这里补充工具注册表、工具调用白名单等字段。
 *
 * @param userId         当前登录用户 id，用于约束工具调用的数据范围
 * @param conversationId 会话 id，作为会话记忆的 conversationId
 * @param userMessage    用户本次输入
 */
public record AgentContext(Long userId, Long conversationId, String userMessage) {
}

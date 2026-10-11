package com.bitforum.ai.tool;

/**
 * 工具上下文键名（M14）。
 *
 * 这些键由应用在调用模型时注入（见 QaAgent），用于把"当前登录用户"等
 * 应用侧已知信息传给工具，而不是让模型在参数里传或向用户索要。
 */
public final class AgentContextKeys {

    /** 当前登录用户 id，值为 Long */
    public static final String USER_ID = "userId";

    /** 当前会话 id，值为 Long */
    public static final String CONVERSATION_ID = "conversationId";

    private AgentContextKeys() {
    }
}

package com.bitforum.ai.agent;

/**
 * Agent 类型（M13 起）。
 *
 * M13 只实现 QA；其余三个在 M16-M17 落地，这里先定义常量避免数据库存裸字符串。
 */
public enum AgentType {
    /** 论坛问答助手：RAG 检索 + 工具调用回答用户提问 */
    QA("问答助手"),
    /** 内容审核：对文章与评论做多维度风险评估（M16） */
    MODERATION("内容审核"),
    /** 运营分析：读取看板数据生成洞察报告（M17） */
    ANALYST("运营分析"),
    /** 智能推荐：多路召回并生成推荐理由（M17） */
    RECOMMEND("智能推荐");

    private final String displayName;

    AgentType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * 按名称解析，非法值回退到 QA。
     * 用于会话记录中存量数据的容错读取。
     */
    public static AgentType fromName(String name) {
        if (name == null || name.isBlank()) {
            return QA;
        }
        for (AgentType type : values()) {
            if (type.name().equalsIgnoreCase(name)) {
                return type;
            }
        }
        return QA;
    }
}

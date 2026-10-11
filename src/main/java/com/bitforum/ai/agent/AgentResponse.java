package com.bitforum.ai.agent;

import java.util.List;

import com.bitforum.ai.rag.Citation;

/**
 * Agent 执行结果（M13；M15 增加引用来源）。
 *
 * @param content          生成给用户的回答正文
 * @param promptTokens     输入 Token（模型未返回时为 null）
 * @param completionTokens 输出 Token（模型未返回时为 null）
 * @param totalTokens      合计 Token（模型未返回时为 null）
 * @param degraded         是否走了降级链路（AI 不可用时的兜底回答）
 * @param citations        RAG 引用来源（M15）；未检索或未命中时为空列表
 */
public record AgentResponse(String content,
                            Integer promptTokens,
                            Integer completionTokens,
                            Integer totalTokens,
                            boolean degraded,
                            List<Citation> citations) {

    /** 正常回答（无引用）。 */
    public static AgentResponse of(String content, Integer promptTokens,
                                   Integer completionTokens, Integer totalTokens) {
        return of(content, promptTokens, completionTokens, totalTokens, List.of());
    }

    /** 正常回答（带引用）。 */
    public static AgentResponse of(String content, Integer promptTokens,
                                   Integer completionTokens, Integer totalTokens,
                                   List<Citation> citations) {
        return new AgentResponse(content, promptTokens, completionTokens, totalTokens, false,
                citations == null ? List.of() : List.copyOf(citations));
    }

    /**
     * 降级回答：AI 不可用或调用失败时的兜底结果。
     * 论坛主流程不应因为 AI 故障而阻塞，因此这里始终返回可用文案。
     */
    public static AgentResponse degraded(String content) {
        return new AgentResponse(content, null, null, null, true, List.of());
    }
}

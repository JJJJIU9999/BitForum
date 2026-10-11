package com.bitforum.ai.orchestrator;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import com.bitforum.ai.agent.Agent;
import com.bitforum.ai.agent.AgentContext;
import com.bitforum.ai.agent.AgentResponse;
import com.bitforum.ai.agent.AgentType;
import com.bitforum.ai.dto.AiMessageResponse;
import com.bitforum.ai.entity.AiConversation;
import com.bitforum.ai.entity.AiExecutionTrace;
import com.bitforum.ai.memory.MysqlChatMemoryRepository;
import com.bitforum.ai.rag.Citation;
import com.bitforum.ai.service.AiConversationService;
import com.bitforum.ai.trace.TraceRecorder;
import com.bitforum.ai.trace.TraceStepType;
import com.bitforum.ai.usage.AiTokenBudgetGuard;

/**
 * Agent 编排器（M13）。
 *
 * 职责：加载会话历史 → 路由到对应 Agent → 持久化用户消息与助手回复 → 更新会话统计。
 *
 * M13 只有 QA 一个 Agent，路由表按类型装配，M16-M17 增加 Agent 时无需改动本类结构。
 */
@Service
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    private final Map<AgentType, Agent> agentsByType;
    private final AiConversationService conversationService;
    private final MysqlChatMemoryRepository chatMemoryRepository;
    private final TraceRecorder traceRecorder;
    private final AiTokenBudgetGuard budgetGuard;

    public AgentOrchestrator(List<Agent> agents,
                             AiConversationService conversationService,
                             MysqlChatMemoryRepository chatMemoryRepository,
                             TraceRecorder traceRecorder,
                             AiTokenBudgetGuard budgetGuard) {
        this.conversationService = conversationService;
        this.chatMemoryRepository = chatMemoryRepository;
        this.traceRecorder = traceRecorder;
        this.budgetGuard = budgetGuard;
        // 过滤掉 type() 返回 null 的实现：null 作为 map key 会让该 Agent 永久无法被路由，
        // 且不会有任何报错，属于静默失效。这里显式跳过并记录警告，便于尽早发现实现错误。
        Map<AgentType, Agent> registry = new EnumMap<>(AgentType.class);
        for (Agent agent : agents) {
            AgentType type = agent.type();
            if (type == null) {
                log.warn("Agent 实现的 type() 返回 null，已跳过注册：{}", agent.getClass().getName());
                continue;
            }
            Agent previous = registry.put(type, agent);
            if (previous != null) {
                log.warn("存在重复的 Agent 类型 {}，后注册的实现将覆盖前者：{} -> {}",
                        type, previous.getClass().getName(), agent.getClass().getName());
            }
        }
        this.agentsByType = registry;
    }

    /**
     * 处理一轮对话：保存用户提问、生成回答、保存回答。
     *
     * @param conversationId 会话 id
     * @param userId         当前登录用户 id（用于归属校验）
     * @param userMessage    用户输入
     * @return 助手回复（已落库）
     */
    public AiMessageResponse chat(Long conversationId, Long userId, String userMessage) {
        // 1. 会话存在性与归属校验；越权直接抛 403/404
        AiConversation conversation = conversationService.getOwnedConversation(conversationId, userId);

        // 2. 先落库用户消息，保证即使后续模型调用失败，用户提问也不会丢
        conversationService.saveMessage(conversationId, "user", userMessage, null, null, null, null);

        // 3. 判断是否首轮对话：是则用首条提问生成会话标题
        int messageCount = conversation.getMessageCount() == null ? 0 : conversation.getMessageCount();
        if (messageCount == 0) {
            conversationService.renameIfDefault(conversationId, userMessage);
        }

        // 4. 加载完整历史（含刚保存的这条），与 Spring AI 的会话记忆共用同一数据源
        List<Message> history = new ArrayList<>(
                chatMemoryRepository.findByConversationId(String.valueOf(conversationId)));

        // 5. 分离出"本次输入"和"之前的历史"，避免把当前提问重复发送给模型
        String currentInput = extractAndRemoveLastUserMessage(history, userMessage);

        // 6. 路由到对应 Agent 执行
        //    M18：从"路由"这一步开始记录执行轨迹。QaAgent 内部的检索、模型调用与工具调用
        //    会挂到同一条轨迹上（ThreadLocal 传递），收尾时一次性落库。
        AgentType agentType = AgentType.fromName(conversation.getAgentType());
        Agent agent = agentsByType.get(agentType);
        traceRecorder.start(AiExecutionTrace.SCENE_CHAT, agentType.name(), userId, conversationId,
                AiExecutionTrace.REF_CONVERSATION, conversationId);
        if (agent == null) {
            log.warn("未找到 Agent 实现：type={}，回退到 QA", agentType);
            traceRecorder.step(TraceStepType.ROUTE, agentType.name(),
                    "未找到该 Agent 的实现，已回退到问答助手");
            agent = agentsByType.get(AgentType.QA);
        } else {
            traceRecorder.step(TraceStepType.ROUTE, agentType.name(),
                    "按会话 agent_type 路由到「" + agentType.getDisplayName() + "」");
        }
        if (agent == null) {
            // 理论上不会发生（QaAgent 始终注册）；防御性处理，避免 NPE
            traceRecorder.finishFailed("没有任何可用的 Agent 实现");
            throw new IllegalStateException("没有任何可用的 Agent 实现");
        }

        // 6.1 M18 收尾：单次输入保护 + 单用户每日预算闸门。
        //     两者都在"调用模型之前"判断，命中时走**同一套**降级路径（不会调用模型、不产生费用）。
        //     刻意只挡用户主动消耗额度的链路：审核（MQ）与洞察（管理员）不设闸门，
        //     否则"作者超额"会导致内容不被审核，是拿治理正确性换成本。
        AiTokenBudgetGuard.BudgetDecision inputDecision = budgetGuard.checkInputLength(currentInput);
        AiTokenBudgetGuard.BudgetDecision budgetDecision = inputDecision.allowed()
                ? budgetGuard.check(userId) : inputDecision;
        if (!budgetDecision.allowed()) {
            traceRecorder.degrade(budgetDecision.reason(), budgetDecision.detail());
            log.info("AI 请求被预算闸门拦截：userId={}，reason={}，detail={}",
                    userId, budgetDecision.reason(), budgetDecision.detail());
            return persistAndRespond(conversationId,
                    AgentResponse.degraded(budgetDecision.message()), 0);
        }

        long startedAt = System.currentTimeMillis();
        AgentResponse response;
        try {
            response = agent.execute(new AgentContext(userId, conversationId, currentInput), history);
        } catch (RuntimeException exception) {
            // Agent 内部已尽量降级，走到这里说明是兜底失败。轨迹必须离开 RUNNING 状态，
            // 否则管理端会一直显示"进行中"，看不出这次调用其实失败了
            traceRecorder.finishFailed(exception.getMessage());
            throw exception;
        }
        int latencyMs = (int) (System.currentTimeMillis() - startedAt);

        return persistAndRespond(conversationId, response, latencyMs);
    }

    /**
     * 保存助手回复、更新会话统计、收尾轨迹，并组装返回体。
     *
     * <p>M18 收尾时抽出来：预算闸门拦截的降级回答与正常回答必须走**完全相同**的落库与轨迹收尾逻辑，
     * 否则"被限流的这轮"会变成历史里查不到、轨迹停在 RUNNING 的孤儿记录。
     */
    private AiMessageResponse persistAndRespond(Long conversationId, AgentResponse response, int latencyMs) {
        // M15：本轮引用到的文章 id 一并落库（ai_message.retrieved_doc_ids），
        // 这样刷新页面重新加载历史消息时引用链接依然在。
        var saved = conversationService.saveMessage(conversationId, "assistant", response.content(),
                response.promptTokens(), response.completionTokens(), response.totalTokens(), latencyMs,
                formatCitationIds(response.citations()));
        if (response.degraded()) {
            log.warn("本轮对话走了降级链路，conversationId={}", conversationId);
        }
        conversationService.updateConversationStats(conversationId, 2,
                response.totalTokens() == null ? 0 : response.totalTokens());
        traceRecorder.step(TraceStepType.PERSIST, "会话落库",
                "助手回复已保存（messageId=" + saved.getId() + "），会话累计 token 已更新");

        // 收尾：状态由链路中是否标记过降级决定（M18 统一的降级表现）
        traceRecorder.finish(null, response.promptTokens(), response.completionTokens(),
                response.totalTokens());

        AiMessageResponse result = new AiMessageResponse();
        result.setId(saved.getId());
        result.setRole(saved.getRole());
        result.setContent(saved.getContent());
        result.setTotalTokens(saved.getTotalTokens());
        result.setLatencyMs(saved.getLatencyMs());
        result.setCreateTime(saved.getCreateTime());
        result.setCitations(response.citations());
        return result;
    }

    /** 把引用来源转成逗号分隔的文章 id，便于落库与回查。 */
    private String formatCitationIds(List<Citation> citations) {
        if (citations == null || citations.isEmpty()) {
            return null;
        }
        return citations.stream()
                .map(citation -> String.valueOf(citation.articleId()))
                .collect(java.util.stream.Collectors.joining(","));
    }

    /**
     * 从历史消息中取出本次输入，并在返回的历史列表中移除它。
     *
     * 取最后一条 user 消息作为当前输入；若因异常情况取不到，则回退使用接口传入的文本。
     */
    private String extractAndRemoveLastUserMessage(List<Message> history, String fallback) {
        for (int i = history.size() - 1; i >= 0; i--) {
            Message message = history.get(i);
            if (message instanceof UserMessage) {
                String text = message.getText() == null || message.getText().isBlank()
                        ? fallback
                        : message.getText();
                history.remove(i);
                return text;
            }
        }
        return fallback;
    }
}

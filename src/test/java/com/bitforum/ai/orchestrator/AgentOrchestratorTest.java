package com.bitforum.ai.orchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.bitforum.ai.agent.Agent;
import com.bitforum.ai.agent.AgentContext;
import com.bitforum.ai.agent.AgentResponse;
import com.bitforum.ai.agent.AgentType;
import com.bitforum.ai.agent.QaAgent;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bitforum.ai.dto.AiConversationResponse;
import com.bitforum.ai.dto.AiMessageResponse;
import com.bitforum.ai.entity.AiConversation;
import com.bitforum.ai.entity.AiExecutionTrace;
import com.bitforum.ai.trace.TraceDegradeReason;
import com.bitforum.ai.mapper.AiConversationMapper;
import com.bitforum.ai.service.AiConversationService;

/**
 * AgentOrchestrator 集成测试（M13）。
 *
 * 测试策略：额外注册一个 RECOMMEND 类型的测试 Agent（见 TestAgentConfig），
 * 并把会话的 agentType 置为 RECOMMEND 来驱动路由。
 *
 * 这样做而不是直接 mock QaAgent，原因有二：
 * 1. 真实的 QaAgent 保留在容器中，其 type() 返回值会被真实执行，可验证路由注册是否正确；
 * 2. 避免 @MockitoBean 替换 @Component 时 type() 返回 null 导致编排器注册失败。
 *
 * 测试 Agent 不会发起真实大模型请求。
 */
@SpringBootTest
@Transactional
@Import(AgentOrchestratorTest.TestAgentConfig.class)
class AgentOrchestratorTest {

    /**
     * 测试专用 Agent。
     *
     * 刻意使用真实实现类而不是 Mockito mock：AgentOrchestrator 在构造阶段就会读取
     * 每个 Agent 的 type() 来建立路由表，而 mock 的打桩在该时点尚未生效（会返回 null，
     * 导致 Agent 被判定为无法路由）。真实实现的 type() 是硬编码的，与打桩时序无关。
     */
    @TestConfiguration
    static class TestAgentConfig {
        @Bean
        TestRoutingAgent testRoutingAgent() {
            return new TestRoutingAgent();
        }
    }

    /** 固定 RECOMMEND 类型、回答内容可切换的测试 Agent。 */
    static class TestRoutingAgent implements Agent {
        private volatile AgentResponse response =
                AgentResponse.of("这是模拟的 AI 回答", 100, 20, 120);
        private volatile List<Message> lastHistory;
        private volatile int callCount;

        @Override
        public AgentType type() {
            return AgentType.RECOMMEND;
        }

        @Override
        public AgentResponse execute(AgentContext context, List<Message> history) {
            this.lastHistory = history;
            this.callCount++;
            return response;
        }

        /** 重置所有可变状态。方法级隔离必须显式做：同一测试类的多个方法共用同一个 Spring 上下文与 bean 实例。 */
        void reset() {
            this.response = AgentResponse.of("这是模拟的 AI 回答", 100, 20, 120);
            this.lastHistory = null;
            this.callCount = 0;
        }

        void willReturn(AgentResponse response) {
            this.response = response;
        }

        List<Message> lastHistory() {
            return lastHistory;
        }

        int callCount() {
            return callCount;
        }
    }

    @Autowired
    private AgentOrchestrator orchestrator;
    @Autowired
    private AiConversationService conversationService;
    @Autowired
    private AiConversationMapper aiConversationMapper;
    @Autowired
    private TestRoutingAgent testRoutingAgent;
    @Autowired
    private com.bitforum.ai.mapper.AiExecutionTraceMapper traceMapper;
    @Autowired
    private com.bitforum.ai.mapper.AiUsageStatMapper usageMapper;

    /** 真实 QaAgent 保留在容器里用于验证路由注册；它不会被本测试调用。 */
    @MockitoBean
    private QaAgent qaAgent;

    @BeforeEach
    void resetTestAgent() {
        // 必须在每个测试方法前重置：Spring 上下文被整个测试类复用，
        // 若不重置，上一轮设置的降级回答与调用计数会污染后续测试。
        testRoutingAgent.reset();
    }

    @Test
    void shouldPersistBothUserAndAssistantMessages() {
        Long conversationId = createConversation(83001L, AgentType.RECOMMEND);

        AiMessageResponse answer = orchestrator.chat(conversationId, 83001L, "论坛怎么发帖？");

        assertEquals("assistant", answer.getRole());
        assertEquals("这是模拟的 AI 回答", answer.getContent());
        assertNotNull(answer.getId(), "助手回答应已落库并获得主键");

        List<AiMessageResponse> history = conversationService.listMessages(conversationId, 83001L);
        assertEquals(2, history.size(), "用户提问与助手回答都应落库");
        assertEquals("user", history.get(0).getRole());
        assertEquals("论坛怎么发帖？", history.get(0).getContent());
        assertEquals("assistant", history.get(1).getRole());
    }

    @Test
    void shouldPassConversationHistoryToAgent() {
        Long conversationId = createConversation(83002L, AgentType.RECOMMEND);

        orchestrator.chat(conversationId, 83002L, "第一个问题");

        // 第二次对话时，Agent 应收到包含第一轮问答的历史
        orchestrator.chat(conversationId, 83002L, "第二个问题");

        List<Message> history = testRoutingAgent.lastHistory();
        assertNotNull(history, "Agent 应被调用并记录历史");
        assertEquals(2, history.size(), "历史应包含上一轮的用户提问与助手回答");
        assertTrue(history.stream().anyMatch(m -> "第一个问题".equals(m.getText())),
                "历史中应包含上一轮提问");
    }

    @Test
    void shouldRouteToAgentMatchingConversationType() {
        Long conversationId = createConversation(83008L, AgentType.RECOMMEND);

        orchestrator.chat(conversationId, 83008L, "路由验证");

        // 验证确实按会话类型路由到了对应 Agent，而不是回退到 QA
        assertEquals(1, testRoutingAgent.callCount(), "应按 agentType 路由到 RECOMMEND 类型的 Agent");
    }

    @Test
    void shouldUpdateConversationStats() {
        Long conversationId = createConversation(83003L, AgentType.RECOMMEND);

        orchestrator.chat(conversationId, 83003L, "统计测试");

        AiConversation updated = aiConversationMapper.selectById(conversationId);
        assertEquals(2, updated.getMessageCount(), "一次对话应计入两条消息");
        assertEquals(120, updated.getTotalTokens(), "应累计 Token 消耗");
    }

    @Test
    void shouldRenameConversationFromFirstMessage() {
        Long conversationId = createConversation(83004L, AgentType.RECOMMEND);

        orchestrator.chat(conversationId, 83004L, "如何配置 Redis 缓存？");

        AiConversation updated = aiConversationMapper.selectById(conversationId);
        assertEquals("如何配置 Redis 缓存？", updated.getTitle(), "首轮对话应自动生成会话标题");
    }

    @Test
    void shouldRejectOtherUsersConversation() {
        Long conversationId = createConversation(83005L, AgentType.RECOMMEND);

        // 另一个用户尝试访问，应被拒绝且不产生任何消息
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> orchestrator.chat(conversationId, 83999L, "越权提问"));
        assertEquals(403, ex.getStatusCode().value());

        assertTrue(conversationService.listMessages(conversationId, 83005L).isEmpty(),
                "越权请求不应写入任何消息");
    }

    @Test
    void shouldRejectUnknownConversation() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> orchestrator.chat(999999999L, 83006L, "不存在的会话"));
        assertEquals(404, ex.getStatusCode().value());
    }

    @Test
    void shouldPersistDegradedAnswerWhenAiUnavailable() {
        Long conversationId = createConversation(83007L, AgentType.RECOMMEND);
        testRoutingAgent.willReturn(AgentResponse.degraded("AI 服务暂时不可用，请稍后重试。"));

        AiMessageResponse answer = orchestrator.chat(conversationId, 83007L, "降级测试");

        // 降级回答也必须正常落库，保证用户能在历史中看到这条记录
        assertEquals("AI 服务暂时不可用，请稍后重试。", answer.getContent());
        assertEquals(2, conversationService.listMessages(conversationId, 83007L).size());
    }

    /**
     * M18：一轮对话必须留下**一条**执行轨迹，且轨迹里有路由与落库两步。
     *
     * <p>这是"埋点真的接上了"的最小验证 —— 埋点写错位置时最常见的表现就是
     * 功能照常工作、但轨迹表里什么都没有（属于静默失效）。
     */
    @Test
    void shouldRecordExecutionTraceForChat() {
        Long conversationId = createConversation(83008L, AgentType.RECOMMEND);

        orchestrator.chat(conversationId, 83008L, "轨迹测试");

        List<AiExecutionTrace> traces = traceMapper.selectList(
                new LambdaQueryWrapper<AiExecutionTrace>()
                        .eq(AiExecutionTrace::getScene, AiExecutionTrace.SCENE_CHAT)
                        .eq(AiExecutionTrace::getConversationId, conversationId));
        assertEquals(1, traces.size(), "一轮对话应产生且只产生一条执行轨迹");

        AiExecutionTrace trace = traces.get(0);
        assertEquals(AiExecutionTrace.STATUS_SUCCESS, trace.getStatus());
        assertEquals(AgentType.RECOMMEND.name(), trace.getAgentType());
        assertEquals(83008L, trace.getUserId());
        assertEquals(2, trace.getStepCount(), "至少应有 ROUTE 与 PERSIST 两步");
        assertTrue(trace.getSteps().contains("\"ROUTE\""), "轨迹应包含路由步骤：" + trace.getSteps());
        assertTrue(trace.getSteps().contains("\"PERSIST\""), "轨迹应包含落库步骤：" + trace.getSteps());
        assertEquals(120, trace.getTotalTokens(), "token 应随轨迹一并记录");
        assertNotNull(trace.getRoute(), "列表页要能一眼看到分派结果，route 字段应已派生");
        assertNull(trace.getDegradeReason(), "正常回答不应带降级原因");
    }

    /**
     * 轨迹状态由**显式标记**驱动，而不是靠返回值的形状猜。
     *
     * <p>这条测试锁定 {@code TraceRecorder} 的契约：只有链路中调用过
     * {@code degrade(...)}（QaAgent 的真实降级点、M18 模块 3 的 Guard 都会调）才会记为
     * {@code DEGRADED}。测试 Agent 直接返回 {@code AgentResponse.degraded(...)} 但没有标记，
     * 因此轨迹仍是 {@code SUCCESS} —— 这是刻意为之：靠"返回值长什么样"反推状态，
     * 迟早会把"部分降级"（例如只有检索失败、回答正常）判错。
     */
    @Test
    void shouldKeepSuccessStatusWhenDegradeIsNotExplicitlyMarked() {
        Long conversationId = createConversation(83009L, AgentType.RECOMMEND);
        testRoutingAgent.willReturn(AgentResponse.degraded("AI 服务暂时不可用，请稍后重试。"));

        orchestrator.chat(conversationId, 83009L, "降级轨迹测试");

        List<AiExecutionTrace> traces = traceMapper.selectList(
                new LambdaQueryWrapper<AiExecutionTrace>()
                        .eq(AiExecutionTrace::getScene, AiExecutionTrace.SCENE_CHAT)
                        .eq(AiExecutionTrace::getConversationId, conversationId));
        assertEquals(1, traces.size());
        assertEquals(AiExecutionTrace.STATUS_SUCCESS, traces.get(0).getStatus(),
                "未调用 degrade(...) 时不应自动推断为降级");
    }

    /**
     * M18 收尾：当日用量达到上限后，对话必须在**调用模型之前**被拦下。
     *
     * <p>断言的落点刻意是"测试 Agent 没有被调用" —— 只断言返回文案的话，
     * 埋点写错位置（先调用再判断）也会通过，而那时费用已经花掉了。
     */
    @Test
    void shouldDegradeWithoutCallingAgentWhenDailyBudgetExceeded() {
        Long conversationId = createConversation(83010L, AgentType.RECOMMEND);
        insertTodayUsage(83010L, 200_000);

        AiMessageResponse answer = orchestrator.chat(conversationId, 83010L, "预算超限测试");

        assertEquals(TraceDegradeReason.userMessage(TraceDegradeReason.BUDGET_EXCEEDED), answer.getContent());
        assertEquals(0, testRoutingAgent.callCount(), "超预算时不应再调用 Agent（否则费用已经产生）");

        List<AiExecutionTrace> traces = traceMapper.selectList(
                new LambdaQueryWrapper<AiExecutionTrace>()
                        .eq(AiExecutionTrace::getScene, AiExecutionTrace.SCENE_CHAT)
                        .eq(AiExecutionTrace::getConversationId, conversationId));
        assertEquals(1, traces.size());
        assertEquals(AiExecutionTrace.STATUS_DEGRADED, traces.get(0).getStatus());
        assertEquals(TraceDegradeReason.BUDGET_EXCEEDED, traces.get(0).getDegradeReason(),
                "轨迹要能回答「为什么这次没有 AI 结果」");

        // 降级回答同样要落库，否则用户刷新后看不到自己问过什么
        assertEquals(2, conversationService.listMessages(conversationId, 83010L).size());
    }

    /** 单次输入保护：超长提问在调用模型之前被拒。 */
    @Test
    void shouldDegradeWhenInputTooLong() {
        Long conversationId = createConversation(83011L, AgentType.RECOMMEND);

        AiMessageResponse answer = orchestrator.chat(conversationId, 83011L, "字".repeat(4001));

        assertEquals(TraceDegradeReason.userMessage(TraceDegradeReason.INPUT_TOO_LONG), answer.getContent());
        assertEquals(0, testRoutingAgent.callCount(), "超长输入不应触发模型调用");
    }

    // ==================== 辅助方法 ====================
    /** 造一条"今天该用户已经用了多少 token"的用量明细（预算闸门据此判定）。 */
    private void insertTodayUsage(Long userId, int tokens) {
        com.bitforum.ai.entity.AiUsageStat stat = new com.bitforum.ai.entity.AiUsageStat();
        stat.setScene(AiExecutionTrace.SCENE_CHAT);
        stat.setAgentType(AgentType.QA.name());
        stat.setUserId(userId);
        stat.setPromptTokens(tokens);
        stat.setCompletionTokens(0);
        stat.setTotalTokens(tokens);
        stat.setResult(com.bitforum.ai.entity.AiUsageStat.RESULT_SUCCESS);
        stat.setEstimatedCost(new java.math.BigDecimal("0.01"));
        stat.setStatDate(java.time.LocalDate.now());
        usageMapper.insert(stat);
    }

    /**
     * 创建会话并把 agentType 改成指定类型。
     * 走 Service 创建再更新，而不是直接 insert，目的是同时覆盖 Service 的默认值逻辑。
     */
    private Long createConversation(Long userId, AgentType agentType) {
        AiConversationResponse created = conversationService.createConversation(userId, null);
        AiConversation conversation = aiConversationMapper.selectById(created.getId());
        conversation.setAgentType(agentType.name());
        aiConversationMapper.updateById(conversation);
        return created.getId();
    }
}

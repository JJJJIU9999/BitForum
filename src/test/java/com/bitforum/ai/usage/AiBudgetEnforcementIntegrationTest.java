package com.bitforum.ai.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bitforum.ai.dto.AiConversationResponse;
import com.bitforum.ai.dto.AiMessageResponse;
import com.bitforum.ai.entity.AiConversation;
import com.bitforum.ai.entity.AiExecutionTrace;
import com.bitforum.ai.entity.AiMessage;
import com.bitforum.ai.entity.AiUsageStat;
import com.bitforum.ai.mapper.AiConversationMapper;
import com.bitforum.ai.mapper.AiExecutionTraceMapper;
import com.bitforum.ai.mapper.AiMessageMapper;
import com.bitforum.ai.mapper.AiUsageStatMapper;
import com.bitforum.ai.orchestrator.AgentOrchestrator;
import com.bitforum.ai.service.AiConversationService;
import com.bitforum.ai.trace.TraceDegradeReason;
import com.bitforum.entity.User;
import com.bitforum.mapper.UserMapper;
import com.bitforum.service.UserService;

/**
 * 用量预算闸门的**边界与拦截**集成测试（M18 收口阶段 3C）。
 *
 * <p>要证明的四件事（对应任务书 3C）：
 * <ol>
 *   <li><b>边界行为与实现一致</b>：当日用量 = 阈值 − 1 放行；= 阈值 与 &gt; 阈值 拦截；</li>
 *   <li><b>拦截发生在模型调用之前</b>：用可计数的 {@link DeepSeekChatModel} 替身，
 *       超限时断言 <code>never().call(...)</code> —— 这是"没有产生模型费用"的硬证据；</li>
 *   <li><b>降级与口径</b>：返回统一友好文案、轨迹标记 <code>DEGRADED + BUDGET_EXCEEDED</code>、
 *       且被拦截的请求**不新增任何 <code>total_tokens &gt; 0</code> 的用量行**（无重复计费）；</li>
 *   <li><b>单次输入保护</b>：输入长度 = 上限放行、超 1 个字符即拦截（同样在模型调用之前）。</li>
 * </ol>
 *
 * <p><b>不调用真实 DeepSeek</b>：模型 bean 被测试替身替换；base-url 也指向不可达回环端口作为双保险。
 * 全部注入仅作用于本测试上下文，**不读不改 `.env`**，生产默认阈值（20 万 token / 2 元）未被改动。
 */
@SpringBootTest(properties = {
        "spring.ai.deepseek.chat.enabled=true",
        "spring.ai.deepseek.api-key=budget-enforcement-placeholder",
        "spring.ai.deepseek.base-url=http://127.0.0.1:9",
        "spring.ai.retry.max-attempts=1",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        // 小阈值便于构造"="与">"两种边界；生产默认值不受影响
        "bitforum.ai.budget.daily-token-limit=100",
        "bitforum.ai.budget.daily-cost-limit=1.0",
        // S4：与生产默认值、以及 HTTP DTO 的 @Size(max=2000) 保持一致
        "bitforum.ai.budget.max-input-chars=2000"
})
class AiBudgetEnforcementIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AiBudgetEnforcementIntegrationTest.class);

    private static final long USER_ID = 99095L;
    private static final long TOKEN_LIMIT = 100L;
    /** 与 HTTP DTO（AiChatRequest）以及配置默认值一致的上限（S4） */
    private static final int MAX_INPUT_CHARS = 2000;
    /** 预置的"今日已用"行的 traceId 前缀，用于把它与本次请求新增的行区分开。 */
    private static final String SEED_PREFIX = "budget-seed-";

    @Autowired
    private AgentOrchestrator orchestrator;
    @Autowired
    private AiConversationService conversationService;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private AiConversationMapper conversationMapper;
    @Autowired
    private AiMessageMapper messageMapper;
    @Autowired
    private AiExecutionTraceMapper traceMapper;
    @Autowired
    private AiUsageStatMapper usageMapper;

    /** 可计数的模型替身：既让上下文正常装配，又能证明"超限时没有进入模型"。 */
    @MockitoBean
    private DeepSeekChatModel deepSeekChatModel;

    private final List<Long> conversationIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setId(USER_ID);
        user.setUsername("m18_budget_user");
        user.setPassword("$2a$10$m18-budget-placeholder");
        user.setNickname("M18 预算测试用户");
        user.setRole("USER");
        user.setStatus(UserService.STATUS_ENABLED);
        userMapper.insert(user);

        // 放行用例需要模型返回一个非空结果；用 lenient 是因为拦截用例不会用到它
        lenient().when(deepSeekChatModel.getDefaultOptions())
                .thenReturn(DeepSeekChatOptions.builder().build());
        lenient().when(deepSeekChatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(
                        AssistantMessage.builder().content("预算测试替身回答").build()))));
    }

    @AfterEach
    void cleanUp() {
        messageMapper.delete(new LambdaQueryWrapper<AiMessage>()
                .in(AiMessage::getConversationId, conversationIds));
        conversationMapper.delete(new LambdaQueryWrapper<AiConversation>()
                .eq(AiConversation::getUserId, USER_ID));
        traceMapper.delete(new LambdaQueryWrapper<AiExecutionTrace>()
                .eq(AiExecutionTrace::getUserId, USER_ID));
        usageMapper.delete(new LambdaQueryWrapper<AiUsageStat>()
                .eq(AiUsageStat::getUserId, USER_ID));
        userMapper.deleteById(USER_ID);
        conversationIds.clear();
    }

    // ==================== 边界 ====================

    /** 阈值 − 1：仍有额度，请求必须进入模型链路。 */
    @Test
    void shouldAllowWhenUsageIsBelowLimit() {
        seedTodayUsage(TOKEN_LIMIT - 1);

        Outcome outcome = chat("预算边界：额度尚余时应正常调用模型。");

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(deepSeekChatModel, atLeastOnce()).call(promptCaptor.capture());
        assertEquals(AiExecutionTrace.STATUS_SUCCESS, outcome.status(), "未超限的调用不应被标记为降级");
        assertEquals("预算测试替身回答", outcome.content());

        // 单次"输出保护"的硬证据：对话链路必须把 max-output-tokens 传给模型
        Prompt prompt = promptCaptor.getValue();
        Integer maxTokens = prompt.getOptions() == null ? null : prompt.getOptions().getMaxTokens();
        assertEquals(1024, maxTokens, "对话链路应把 max-output-tokens（默认 1024）传给模型");

        log.info("M18_BUDGET 边界通过：用量={} 阈值={} 结果={} 输出上限={}",
                TOKEN_LIMIT - 1, TOKEN_LIMIT, outcome.status(), maxTokens);
    }

    /** 恰好等于阈值：按实现（`>=`）必须拦截，且不得进入模型。 */
    @Test
    void shouldBlockWhenUsageEqualsLimit() {
        seedTodayUsage(TOKEN_LIMIT);

        Outcome outcome = chat("预算边界：用量恰好等于阈值时应被拦截。");

        verify(deepSeekChatModel, never()).call(any(Prompt.class));
        assertEquals(AiExecutionTrace.STATUS_DEGRADED, outcome.status());
        assertEquals(TraceDegradeReason.BUDGET_EXCEEDED, outcome.degradeReason());
        assertEquals(TraceDegradeReason.userMessage(TraceDegradeReason.BUDGET_EXCEEDED), outcome.content());
        assertNoPaidUsageRowAdded();
        log.info("M18_BUDGET 边界通过：用量={} 阈值={} 拦截={}", TOKEN_LIMIT, TOKEN_LIMIT, outcome.degradeReason());
    }

    /** 超过阈值：同样拦截。 */
    @Test
    void shouldBlockWhenUsageExceedsLimit() {
        seedTodayUsage(TOKEN_LIMIT + 1);

        Outcome outcome = chat("预算边界：用量超过阈值时应被拦截。");

        verify(deepSeekChatModel, never()).call(any(Prompt.class));
        assertEquals(AiExecutionTrace.STATUS_DEGRADED, outcome.status());
        assertEquals(TraceDegradeReason.BUDGET_EXCEEDED, outcome.degradeReason());
        assertNoPaidUsageRowAdded();
    }

    /**
     * 费用维度独立生效：token 远未达上限，但当日**估算费用**已达上限时同样拦截。
     *
     * <p>能力声明是"每日 token / 费用上限"，因此两个维度都要有可复现的边界证据
     * （单测 `AiTokenBudgetGuardTest.shouldDenyWhenDailyCostLimitReached` 覆盖阈值判定，
     * 本用例覆盖**链路行为**：拦截发生在模型调用之前）。
     */
    @Test
    void shouldBlockWhenDailyCostLimitReached() {
        seedTodayUsage(0, new BigDecimal("1.00"));

        Outcome outcome = chat("预算边界：当日费用达到上限时应被拦截。");

        verify(deepSeekChatModel, never()).call(any(Prompt.class));
        assertEquals(AiExecutionTrace.STATUS_DEGRADED, outcome.status());
        assertEquals(TraceDegradeReason.BUDGET_EXCEEDED, outcome.degradeReason());
        assertEquals(TraceDegradeReason.userMessage(TraceDegradeReason.BUDGET_EXCEEDED), outcome.content());
        assertNoPaidUsageRowAdded();
        log.info("M18_BUDGET 费用维度通过：当日费用=1.00 元 上限=1.0 元 拦截={}", outcome.degradeReason());
    }

    /** 单次输入保护：等于上限放行；超 1 个字符即拦截（都在模型调用之前）。 */
    @Test
    void shouldBlockOversizedInputBeforeCallingModel() {
        Outcome allowed = chat("字".repeat(MAX_INPUT_CHARS));
        verify(deepSeekChatModel, atLeastOnce()).call(any(Prompt.class));
        assertEquals(AiExecutionTrace.STATUS_SUCCESS, allowed.status(), "等于上限的输入应放行");

        Outcome blocked = chat("字".repeat(MAX_INPUT_CHARS + 1));
        assertEquals(AiExecutionTrace.STATUS_DEGRADED, blocked.status());
        assertEquals(TraceDegradeReason.INPUT_TOO_LONG, blocked.degradeReason());
        assertEquals(TraceDegradeReason.userMessage(TraceDegradeReason.INPUT_TOO_LONG), blocked.content());
        // 第二次请求没有增加模型调用次数：仍等于第一次的数量（1 次）
        verify(deepSeekChatModel, org.mockito.Mockito.times(1)).call(any(Prompt.class));
    }

    // ==================== 断言辅助 ====================

    /**
     * 被拦截的请求**不得**新增任何 `total_tokens > 0` 的用量行
     * （预置的"今日已用"行用 traceId 前缀区分，不计入）。
     *
     * <p>注意口径：拦截仍会写一条 `total_tokens=0`、`result=DEGRADED` 的明细用于审计 —— 这正是
     * {@code TraceRecorder.finish} 的真实行为，因此这里断言的是"没有新增计费行"，而非"没有新增记录"。
     */
    private void assertNoPaidUsageRowAdded() {
        Long paidRows = usageMapper.selectCount(new LambdaQueryWrapper<AiUsageStat>()
                .eq(AiUsageStat::getUserId, USER_ID)
                .gt(AiUsageStat::getTotalTokens, 0)
                .notLikeRight(AiUsageStat::getTraceId, SEED_PREFIX));
        assertEquals(0L, paidRows, "被拦截的请求不应产生任何计费用的量行");

        Long blockedRows = usageMapper.selectCount(new LambdaQueryWrapper<AiUsageStat>()
                .eq(AiUsageStat::getUserId, USER_ID)
                .eq(AiUsageStat::getResult, AiUsageStat.RESULT_DEGRADED));
        assertTrue(blockedRows >= 1, "被拦截的请求仍应留下一条 0 token 的降级明细用于审计");
    }

    private void seedTodayUsage(long tokens) {
        seedTodayUsage(tokens, BigDecimal.ZERO);
    }

    private void seedTodayUsage(long tokens, BigDecimal cost) {
        AiUsageStat seed = new AiUsageStat();
        seed.setTraceId(SEED_PREFIX + tokens + "-" + System.nanoTime());
        seed.setScene(AiExecutionTrace.SCENE_CHAT);
        seed.setAgentType("QA");
        seed.setUserId(USER_ID);
        seed.setModel("seed");
        seed.setPromptTokens((int) tokens);
        seed.setCompletionTokens(0);
        seed.setTotalTokens((int) tokens);
        seed.setResult(AiUsageStat.RESULT_SUCCESS);
        seed.setEstimatedCost(cost);
        seed.setStatDate(LocalDate.now());
        usageMapper.insert(seed);
    }

    /** 走真实的 HTTP 之外的那条链路：Orchestrator（含预算闸门 → Agent → 落库 → 轨迹/用量收尾）。 */
    private Outcome chat(String input) {
        AiConversationResponse conversation = conversationService.createConversation(USER_ID, null);
        conversationIds.add(conversation.getId());
        AiMessageResponse response = orchestrator.chat(conversation.getId(), USER_ID, input);

        AiExecutionTrace trace = traceMapper.selectOne(new LambdaQueryWrapper<AiExecutionTrace>()
                .eq(AiExecutionTrace::getConversationId, conversation.getId())
                .orderByDesc(AiExecutionTrace::getId)
                .last("LIMIT 1"));
        assertNotNull(trace, "每次调用都应留下执行轨迹");
        return new Outcome(response.getContent(), trace.getStatus(), trace.getDegradeReason());
    }

    private record Outcome(String content, String status, String degradeReason) { }
}

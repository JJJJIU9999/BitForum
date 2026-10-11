package com.bitforum.ai.observation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bitforum.ai.entity.AiConversation;
import com.bitforum.ai.entity.AiExecutionTrace;
import com.bitforum.ai.entity.AiMessage;
import com.bitforum.ai.entity.AiUsageStat;
import com.bitforum.ai.mapper.AiConversationMapper;
import com.bitforum.ai.mapper.AiExecutionTraceMapper;
import com.bitforum.ai.mapper.AiMessageMapper;
import com.bitforum.ai.mapper.AiUsageStatMapper;
import com.bitforum.entity.User;
import com.bitforum.mapper.UserMapper;
import com.bitforum.service.UserService;
import com.bitforum.util.JwtUtil;

/**
 * 主流程隔离观察：**AI 负载运行时，非 AI 主流程接口是否仍然可用**（M18 收口补强）。
 *
 * <p><b>为什么需要它</b>：`ai-performance-observation.md` 只证明了"持久化不是 AI 请求的瓶颈"，
 * 并不能回答"AI 跑起来会不会拖慢论坛"。本类用**真实 HTTP（随机端口 + Tomcat）**观测：
 * 在并发 AI 请求持续运行时，公开文章接口的延迟与错误率相对空载基线的变化。
 *
 * <p><b>不烧真实 token</b>：模型 bean 被替换为**带固定延迟的替身**（每次调用 sleep
 * {@value #MODEL_LATENCY_MS} ms），base-url 同时指向不可达回环端口作为双保险；
 * 因此不需要 API Key、不产生费用，也不会访问外网。
 *
 * <p><b>观察口径（重要）</b>：
 * <ul>
 *   <li>本测试上下文把 Hikari 连接池设为 **10**（与生产默认一致），避免用测试专用的 2 连接配置
 *       得出"连接池必然争用"的失真结论；</li>
 *   <li>断言只锁"**主流程必须保持可用**"（全部 2xx、无超时），延迟劣化倍数**只记录不断言**
 *       —— 并发观察在不同机器上抖动较大，强断言会变成 flaky 测试；</li>
 *   <li>结论仅代表"本机单实例、有限并发"，**不代表生产容量或 SLA**。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.deepseek.chat.enabled=true",
        "spring.ai.deepseek.api-key=isolation-observation-placeholder",
        "spring.ai.deepseek.base-url=http://127.0.0.1:9",
        "spring.ai.retry.max-attempts=1",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "bitforum.ai.budget.daily-token-limit=1000000",
        // 与生产默认一致，避免"测试专用 2 连接"造成的失真
        "spring.datasource.hikari.maximum-pool-size=10"
})
class M18MainFlowIsolationTest {

    private static final Logger log = LoggerFactory.getLogger(M18MainFlowIsolationTest.class);

    private static final long USER_ID = 99096L;
    /** 并发 AI 请求数（每个占一个 Tomcat 线程 + 若干数据库连接）。 */
    private static final int AI_CONCURRENCY = 8;
    /** 每个 AI 请求发起几轮对话。 */
    private static final int AI_ROUNDS = 2;
    /** 主流程接口的采样次数（负载前 / 负载中各一次）。 */
    private static final int MAIN_FLOW_SAMPLES = 20;
    /** 模型替身的固定延迟，用于制造"慢模型"压力。 */
    private static final long MODEL_LATENCY_MS = 300L;

    private static final Path OUTPUT_DIR = Path.of("target", "finalization", "observations");

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JwtUtil jwtUtil;
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

    @MockitoBean
    private DeepSeekChatModel deepSeekChatModel;

    private final List<Long> conversationIds = new ArrayList<>();
    private String token;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setId(USER_ID);
        user.setUsername("m18_isolation_user");
        user.setPassword("$2a$10$m18-isolation-placeholder");
        user.setNickname("M18 隔离观察用户");
        user.setRole("USER");
        user.setStatus(UserService.STATUS_ENABLED);
        userMapper.insert(user);
        token = jwtUtil.generateToken(USER_ID, "m18_isolation_user");

        // 带固定延迟的模型替身：模拟"慢模型"，制造真实的请求占用
        lenient().when(deepSeekChatModel.getDefaultOptions())
                .thenReturn(DeepSeekChatOptions.builder().build());
        lenient().when(deepSeekChatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            Thread.sleep(MODEL_LATENCY_MS);
            return new ChatResponse(List.of(new Generation(
                    AssistantMessage.builder().content("隔离观察替身回答").build())));
        });

        for (int i = 0; i < AI_CONCURRENCY; i++) {
            AiConversation conversation = new AiConversation();
            conversation.setUserId(USER_ID);
            conversation.setTitle("M18 隔离观察会话 " + i);
            conversation.setAgentType("QA");
            conversation.setMessageCount(0);
            conversation.setTotalTokens(0);
            conversationMapper.insert(conversation);
            conversationIds.add(conversation.getId());
        }
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

    @Test
    void shouldKeepMainFlowAvailableWhileAiRequestsAreRunning() throws Exception {
        // 1) 空载基线
        List<Long> baseline = measureMainFlow();

        // 2) 并发 AI 负载 + 同时测主流程
        ExecutorService pool = Executors.newFixedThreadPool(AI_CONCURRENCY);
        CountDownLatch aiStarted = new CountDownLatch(AI_CONCURRENCY);
        List<String> aiFailures = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> aiTasks = new ArrayList<>();
        try {
            for (int i = 0; i < AI_CONCURRENCY; i++) {
                Long conversationId = conversationIds.get(i);
                aiTasks.add(pool.submit(() -> {
                    aiStarted.countDown();
                    for (int round = 0; round < AI_ROUNDS; round++) {
                        String failure = callAi(conversationId);
                        if (failure != null) {
                            aiFailures.add(failure);
                        }
                    }
                }));
            }
            assertTrue(aiStarted.await(10, TimeUnit.SECONDS), "AI 负载应已启动");
            List<Long> loaded = measureMainFlow();

            for (Future<?> task : aiTasks) {
                task.get(60, TimeUnit.SECONDS);
            }

            // 3) 断言：主流程必须保持可用（延迟劣化只记录）
            double baselineP95 = percentile(baseline, 0.95);
            double loadedP95 = percentile(loaded, 0.95);
            double ratio = baselineP95 == 0 ? 1.0 : loadedP95 / baselineP95;

            Evidence evidence = new Evidence(baseline, loaded, ratio, aiFailures.size());
            Path file = persist(evidence);
            log.info("M18_ISOLATION {}", evidence.summaryLine());
            log.info("M18_ISOLATION 证据文件：{}", file.toAbsolutePath());

            assertEquals(0, aiFailures.size(), "AI 负载本身不应出现请求失败：" + aiFailures);
        } finally {
            pool.shutdownNow();
        }
    }

    /** 空载/负载下的主流程采样：任何非 2xx 或异常都会直接让测试失败（可用性是硬要求）。 */
    private List<Long> measureMainFlow() {
        List<Long> latencies = new ArrayList<>(MAIN_FLOW_SAMPLES);
        for (int i = 0; i < MAIN_FLOW_SAMPLES; i++) {
            long startedAt = System.nanoTime();
            ResponseEntity<String> response = rest.getForEntity("/api/article/listAll", String.class);
            latencies.add((System.nanoTime() - startedAt) / 1_000_000);
            assertTrue(response.getStatusCode().is2xxSuccessful(),
                    "主流程接口必须保持 2xx，实际=" + response.getStatusCode());
        }
        return latencies;
    }

    /** 发起一轮 AI 对话；失败时返回可读原因（不抛异常，便于统计错误率）。 */
    private String callAi(Long conversationId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        try {
            ResponseEntity<String> response = rest.exchange(
                    "/api/ai/conversations/" + conversationId + "/messages",
                    HttpMethod.POST,
                    new HttpEntity<>("{\"content\":\"隔离观察：AI 负载请求\"}", headers),
                    String.class);
            return response.getStatusCode().is2xxSuccessful() ? null
                    : "HTTP " + response.getStatusCode();
        } catch (RuntimeException exception) {
            return exception.getClass().getSimpleName() + ": " + exception.getMessage();
        }
    }

    private record Evidence(List<Long> baseline, List<Long> loaded, double p95Ratio, int aiFailures) {

        private String summaryLine() {
            return String.format(Locale.ROOT,
                    "main_flow baseline(p50=%.1f p95=%.1f max=%.1f) loaded(p50=%.1f p95=%.1f max=%.1f) "
                            + "p95_ratio=%.2f ai_concurrency=%d ai_failures=%d",
                    percentile(baseline, 0.50), percentile(baseline, 0.95), maxOf(baseline),
                    percentile(loaded, 0.50), percentile(loaded, 0.95), maxOf(loaded),
                    p95Ratio, AI_CONCURRENCY, aiFailures);
        }
    }

    private Path persist(Evidence evidence) throws Exception {
        Files.createDirectories(OUTPUT_DIR);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path file = OUTPUT_DIR.resolve("main-flow-isolation-" + stamp + ".txt");
        try (PrintWriter writer = new PrintWriter(
                Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            writer.printf("# 主流程隔离观察：AI 负载下非 AI 接口的可用性与延迟%n");
            writer.printf("生成时间: %s%n", LocalDateTime.now());
            writer.printf("Java: %s / OS: %s %s%n", System.getProperty("java.version"),
                    System.getProperty("os.name"), System.getProperty("os.arch"));
            writer.printf("模型: 测试替身（固定延迟 %d ms）；base-url 指向不可达回环端口；未产生真实调用费用%n",
                    MODEL_LATENCY_MS);
            writer.printf("并发: AI 请求 %d 个 × %d 轮；主流程采样各 %d 次（GET /api/article/listAll）%n",
                    AI_CONCURRENCY, AI_ROUNDS, MAIN_FLOW_SAMPLES);
            writer.printf("连接池: Hikari maximum-pool-size=10（与生产默认一致）%n");
            writer.printf("%n结果: %s%n", evidence.summaryLine());
            writer.printf("原始样本（毫秒）:%n  baseline=%s%n  loaded=%s%n",
                    evidence.baseline(), evidence.loaded());
            writer.printf("%n说明: 延迟劣化倍数只作记录、不作断言（并发观察在不同机器上抖动较大）。%n");
            writer.printf("局限: 单机单实例、有限并发、模型为固定延迟替身；**不代表生产 QPS、并发容量或 SLA**。%n");
        }
        return file;
    }

    private static double percentile(List<Long> samples, double percentile) {
        double[] sorted = samples.stream().mapToDouble(Long::doubleValue).sorted().toArray();
        int index = (int) Math.ceil(percentile * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }

    private static double maxOf(List<Long> samples) {
        return Arrays.stream(samples.stream().mapToDouble(Long::doubleValue).toArray()).max().orElseThrow();
    }
}

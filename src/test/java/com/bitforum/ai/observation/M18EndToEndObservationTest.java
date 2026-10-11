package com.bitforum.ai.observation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bitforum.ai.analyst.AiInsightGenerationService;
import com.bitforum.ai.dto.AiConversationResponse;
import com.bitforum.ai.entity.AiConversation;
import com.bitforum.ai.entity.AiExecutionTrace;
import com.bitforum.ai.entity.AiInsightReport;
import com.bitforum.ai.entity.AiMessage;
import com.bitforum.ai.entity.AiRecommendLog;
import com.bitforum.ai.entity.AiUsageStat;
import com.bitforum.ai.mapper.AiConversationMapper;
import com.bitforum.ai.mapper.AiExecutionTraceMapper;
import com.bitforum.ai.mapper.AiInsightReportMapper;
import com.bitforum.ai.mapper.AiMessageMapper;
import com.bitforum.ai.mapper.AiRecommendLogMapper;
import com.bitforum.ai.mapper.AiUsageStatMapper;
import com.bitforum.ai.orchestrator.AgentOrchestrator;
import com.bitforum.ai.recommend.RecommendService;
import com.bitforum.ai.recommend.RecommendService.RecommendRequest;
import com.bitforum.ai.service.AiConversationService;
import com.bitforum.ai.trace.TraceStep;
import com.bitforum.entity.Article;
import com.bitforum.entity.User;
import com.bitforum.mapper.ArticleMapper;
import com.bitforum.mapper.UserMapper;
import com.bitforum.service.UserService;

/**
 * M18 真实端到端耗时观察（**会产生少量 DeepSeek API 费用**）。
 *
 * <p>覆盖阶段 2C 要求的四类链路：普通 QA、QA+RAG、QA+Tool Calling、推荐；
 * 运营洞察单列（异步链路，端到端 = 触发 → 报告终态）。
 *
 * <p><b>默认不执行</b>：需要显式打开环境变量，避免 CI 或全量回归误跑真实调用：
 *
 * <pre>
 *   export M18_E2E_OBSERVATION=true
 *   export DEEPSEEK_CHAT_ENABLED=true
 *   export DEEPSEEK_API_KEY="$(grep '^DEEPSEEK_API_KEY=' .env | cut -d= -f2-)"
 *   ./mvnw -s maven-settings.xml -Dtest=M18EndToEndObservationTest test
 * </pre>
 *
 * <p>方法学约束（与阶段 2A 的方案一致）：
 * <ul>
 *   <li>每类**预热 1 次 + 记录 5 次**，不擅自加次数；样本量 5 时 P95 只能作为"小样本描述值"；</li>
 *   <li>**分组靠数据而非靠挑题**：按轨迹 {@code steps} 里是否出现 RAG 命中（{@code RETRIEVE}）
 *       与工具调用（{@code TOOL_CALL}）打标，不预设"这题一定没有 RAG"；</li>
 *   <li>**禁止**用"带 Trace 与不带 Trace 的总耗时相减"推算 Trace 开销 —— 本地开销由
 *       {@code M18PersistenceObservationTest} 单独度量，本类只报端到端与各步耗时；</li>
 *   <li>排除预算闸门干扰：仅在本测试上下文内把单用户每日上限调高，生产默认值不变；</li>
 *   <li>只使用本次创建的隔离测试用户与新建会话，结束时按 id 精确清理；
 *       不打印 API Key / Authorization / JWT。</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        // 观察期间不希望被"单用户每日预算"截断（18+ 次对话累计可能接近默认 200k 上限）。
        // 仅覆盖本测试上下文；application.yml 的生产默认值不变。
        "bitforum.ai.budget.daily-token-limit=1000000"
})
@EnabledIfEnvironmentVariable(named = "M18_E2E_OBSERVATION", matches = "true")
class M18EndToEndObservationTest {

    private static final Logger log = LoggerFactory.getLogger(M18EndToEndObservationTest.class);

    private static final int WARMUP_RUNS = 1;
    private static final int SAMPLE_SIZE = 5;
    private static final long INSIGHT_TIMEOUT_SECONDS = 90;

    /** 固定输入（不随轮次变化，保证样本可比）。 */
    private static final String INPUT_PLAIN = "用一句话解释 Java 里的 happens-before 原则。";
    private static final String INPUT_RAG = "站内关于 Spring 循环依赖的讨论都说了什么？";
    private static final String INPUT_TOOL = "站内现在最热门的 3 篇文章是哪些？给我标题和文章 id。";

    private static final Path OUTPUT_DIR = Path.of("target", "m18-observation");

    /** 判断样本是否走了 RAG 命中 / 工具调用，用于"数据驱动分组"（而不是靠挑题预设）。 */
    private static final String STEP_RETRIEVE = com.bitforum.ai.trace.TraceStepType.RETRIEVE;
    private static final String STEP_TOOL_CALL = com.bitforum.ai.trace.TraceStepType.TOOL_CALL;
    private static final String STEP_LLM_CALL = com.bitforum.ai.trace.TraceStepType.LLM_CALL;

    @Autowired
    private AgentOrchestrator orchestrator;
    @Autowired
    private AiConversationService conversationService;
    @Autowired
    private RecommendService recommendService;
    @Autowired
    private AiInsightGenerationService insightGenerationService;

    @Autowired
    private AiConversationMapper conversationMapper;
    @Autowired
    private AiMessageMapper messageMapper;
    @Autowired
    private AiExecutionTraceMapper traceMapper;
    @Autowired
    private AiUsageStatMapper usageMapper;
    @Autowired
    private AiRecommendLogMapper recommendLogMapper;
    @Autowired
    private AiInsightReportMapper insightReportMapper;
    @Autowired
    private ArticleMapper articleMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldObserveRealEndToEndLatency() throws Exception {
        Long userId = createIsolatedUser();
        List<Sample> samples = new ArrayList<>();
        try {
            Long articleId = firstPublishedArticleId();

            samples.addAll(observeChat("qa_plain", userId, INPUT_PLAIN));
            samples.addAll(observeChat("qa_rag", userId, INPUT_RAG));
            samples.addAll(observeChat("qa_tool", userId, INPUT_TOOL));
            samples.addAll(observeRecommend(userId, articleId));
            samples.addAll(observeInsight(userId));

            assertEquals(5 * SAMPLE_SIZE, samples.size(), "五个场景各应产出 " + SAMPLE_SIZE + " 个样本");

            Path evidence = persist(samples);
            samples.forEach(sample -> log.info("M18_E2E {}", sample.line()));
            summarize(samples).forEach(line -> log.info("M18_E2E_SUMMARY {}", line));
            log.info("M18_E2E 原始证据文件：{}", evidence.toAbsolutePath());
        } finally {
            cleanUp(userId);
        }
    }

    // ==================== 场景 ====================

    private List<Sample> observeChat(String scenario, Long userId, String input) {
        List<Sample> samples = new ArrayList<>();
        for (int i = 0; i < WARMUP_RUNS + SAMPLE_SIZE; i++) {
            // 每个样本都新建会话：干净上下文，避免上一轮历史影响耗时
            AiConversationResponse conversation = conversationService.createConversation(userId, null);
            long startedAt = System.nanoTime();
            String failure = null;
            try {
                orchestrator.chat(conversation.getId(), userId, input);
            } catch (RuntimeException exception) {
                failure = safeMessage(exception);
            }
            long elapsed = (System.nanoTime() - startedAt) / 1_000_000;
            if (i < WARMUP_RUNS) {
                continue;
            }
            samples.add(build(scenario, input, elapsed, userId, AiExecutionTrace.SCENE_CHAT, failure));
        }
        return samples;
    }

    private List<Sample> observeRecommend(Long userId, Long articleId) {
        List<Sample> samples = new ArrayList<>();
        for (int i = 0; i < WARMUP_RUNS + SAMPLE_SIZE; i++) {
            long startedAt = System.nanoTime();
            String failure = null;
            try {
                recommendService.recommend(RecommendRequest.forArticleDetail(userId, articleId, 5));
            } catch (RuntimeException exception) {
                failure = safeMessage(exception);
            }
            long elapsed = (System.nanoTime() - startedAt) / 1_000_000;
            if (i < WARMUP_RUNS) {
                continue;
            }
            samples.add(build("recommend", "forArticleDetail(articleId=" + articleId + ", topN=5)",
                    elapsed, userId, AiExecutionTrace.SCENE_RECOMMEND, failure));
        }
        return samples;
    }

    /** 运营洞察是异步链路：端到端 = 触发 → 报告离开 PENDING。 */
    private List<Sample> observeInsight(Long userId) {
        List<Sample> samples = new ArrayList<>();
        for (int i = 0; i < WARMUP_RUNS + SAMPLE_SIZE; i++) {
            long startedAt = System.nanoTime();
            String failure = null;
            Long reportId = null;
            try {
                reportId = insightGenerationService.trigger(userId)
                        .map(AiInsightReport::getId)
                        .orElse(null);
                if (reportId == null) {
                    failure = "已有洞察在生成中，本次触发被忽略";
                } else {
                    AiInsightReport report = awaitInsight(reportId);
                    if (!AiInsightReport.STATUS_SUCCESS.equals(report.getStatus())) {
                        failure = "报告终态=" + report.getStatus() + " message=" + report.getErrorMessage();
                    }
                }
            } catch (RuntimeException exception) {
                failure = safeMessage(exception);
            }
            long elapsed = (System.nanoTime() - startedAt) / 1_000_000;
            if (i < WARMUP_RUNS) {
                continue;
            }
            samples.add(build("insight", "trigger(reportId=" + reportId + ")",
                    elapsed, userId, AiExecutionTrace.SCENE_INSIGHT, failure));
        }
        return samples;
    }

    private AiInsightReport awaitInsight(Long reportId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(INSIGHT_TIMEOUT_SECONDS).toNanos();
        AiInsightReport report = insightReportMapper.selectById(reportId);
        while (report != null && AiInsightReport.STATUS_PENDING.equals(report.getStatus())
                && System.nanoTime() < deadline) {
            try {
                Thread.sleep(300);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
            report = insightReportMapper.selectById(reportId);
        }
        return report;
    }

    // ==================== 样本构造 ====================

    private Sample build(String scenario, String input, long elapsedMillis, Long userId, String scene,
                         String failure) {
        AiExecutionTrace trace = latestTrace(userId, scene);
        List<TraceStep> steps = parseSteps(trace == null ? null : trace.getSteps());
        return new Sample(scenario, input, elapsedMillis,
                trace == null ? "NO_TRACE" : trace.getStatus(),
                trace == null ? null : trace.getDegradeReason(),
                trace == null ? null : trace.getTotalTokens(),
                steps, failure);
    }

    private record Sample(String scenario, String input, long elapsedMillis, String traceStatus,
                          String degradeReason, Integer totalTokens, List<TraceStep> steps,
                          String failure) {

        private boolean hasRetrieve() {
            return steps.stream().anyMatch(step -> STEP_RETRIEVE.equals(step.type()));
        }

        private boolean hasToolCall() {
            return steps.stream().anyMatch(step -> STEP_TOOL_CALL.equals(step.type()));
        }

        private double stepMillis(String type) {
            return steps.stream().filter(step -> type.equals(step.type()))
                    .mapToLong(TraceStep::latencyMs).sum();
        }

        private String line() {
            return String.format(Locale.ROOT,
                    "scenario=%s e2e_ms=%d trace_status=%s tokens=%s retrieve=%s tool_call=%s "
                            + "llm_ms=%.0f retrieve_ms=%.0f tool_ms=%.0f degrade_reason=%s failure=%s",
                    scenario, elapsedMillis, traceStatus, totalTokens, hasRetrieve(), hasToolCall(),
                    stepMillis(STEP_LLM_CALL), stepMillis(STEP_RETRIEVE),
                    stepMillis(STEP_TOOL_CALL), degradeReason, failure == null ? "-" : failure);
        }
    }

    // ==================== 统计与落盘 ====================

    private List<String> summarize(List<Sample> samples) {
        List<String> lines = new ArrayList<>();
        for (String scenario : List.of("qa_plain", "qa_rag", "qa_tool", "recommend", "insight")) {
            double[] millis = samples.stream().filter(sample -> scenario.equals(sample.scenario()))
                    .mapToDouble(sample -> sample.elapsedMillis()).sorted().toArray();
            if (millis.length == 0) {
                continue;
            }
            lines.add(String.format(Locale.ROOT,
                    "scenario=%s n=%d average_ms=%.0f p50_ms=%.0f p95_ms=%.0f min_ms=%.0f max_ms=%.0f "
                            + "（n=%d，P95 为小样本描述值）",
                    scenario, millis.length, Arrays.stream(millis).average().orElseThrow(),
                    percentile(millis, 0.50), percentile(millis, 0.95), millis[0],
                    millis[millis.length - 1], millis.length));
        }
        return lines;
    }

    private static double percentile(double[] sorted, double percentile) {
        int index = (int) Math.ceil(percentile * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }

    private Path persist(List<Sample> samples) throws Exception {
        Files.createDirectories(OUTPUT_DIR);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path file = OUTPUT_DIR.resolve("end-to-end-" + stamp + ".txt");
        try (PrintWriter writer = new PrintWriter(
                Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            writer.printf("# M18 真实端到端耗时观察（真实 DeepSeek 调用）%n");
            writer.printf("生成时间: %s%n", LocalDateTime.now());
            writer.printf("Java: %s / OS: %s %s%n", System.getProperty("java.version"),
                    System.getProperty("os.name"), System.getProperty("os.arch"));
            writer.printf("每场景: 预热 %d 次 + 记录 %d 次（共 %d 次真实调用）%n",
                    WARMUP_RUNS, SAMPLE_SIZE, (WARMUP_RUNS + SAMPLE_SIZE) * 5);
            writer.printf("固定输入（对话类）:%n  普通QA: %s%n  RAG: %s%n  工具: %s%n",
                    INPUT_PLAIN, INPUT_RAG, INPUT_TOOL);
            writer.printf("推荐: RecommendRequest.forArticleDetail(topN=5)；洞察: 管理员触发 + 轮询报告终态%n");
            writer.printf("预算: 本测试上下文临时把单用户每日上限调高，避免观察被预算闸门截断%n");
            writer.printf("%n样本明细（未指定单位的毫秒）:%n");
            samples.forEach(sample -> writer.println(sample.line()));
            writer.printf("%n按场景汇总:%n");
            summarize(samples).forEach(writer::println);
            writer.printf("%n说明: 端到端耗时包含真实模型调用；本地持久化开销另见 "
                    + "local-persistence-*.txt（约 3.9 ms，占比 <1%%）。%n");
            writer.printf("局限: 5 个样本的 P95 仅为小样本描述值；本机单实例串行观察，"
                    + "不代表并发能力、生产 QPS 或 SLA。%n");
        }
        assertTrue(Files.size(file) > 0, "证据文件应非空");
        return file;
    }

    // ==================== 数据访问与清理 ====================

    private AiExecutionTrace latestTrace(Long userId, String scene) {
        return traceMapper.selectOne(new LambdaQueryWrapper<AiExecutionTrace>()
                .eq(AiExecutionTrace::getUserId, userId)
                .eq(AiExecutionTrace::getScene, scene)
                .orderByDesc(AiExecutionTrace::getId)
                .last("LIMIT 1"));
    }

    private List<TraceStep> parseSteps(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<TraceStep>>() { });
        } catch (Exception exception) {
            log.warn("轨迹步骤解析失败：{}", exception.getMessage());
            return List.of();
        }
    }

    private Long firstPublishedArticleId() {
        Article article = articleMapper.selectOne(new LambdaQueryWrapper<Article>()
                .eq(Article::getStatus, "PUBLISHED")
                .orderByDesc(Article::getId)
                .last("LIMIT 1"));
        return article == null ? null : article.getId();
    }

    private Long createIsolatedUser() {
        User user = new User();
        user.setUsername("m18_obs_" + System.currentTimeMillis());
        user.setPassword("$2a$10$m18-observation-placeholder");
        user.setNickname("M18 观察用户");
        user.setRole("USER");
        user.setStatus(UserService.STATUS_ENABLED);
        userMapper.insert(user);
        return user.getId();
    }

    private void cleanUp(Long userId) {
        List<Long> conversationIds = conversationMapper
                .selectList(new LambdaQueryWrapper<AiConversation>().eq(AiConversation::getUserId, userId))
                .stream().map(AiConversation::getId).toList();
        if (!conversationIds.isEmpty()) {
            messageMapper.delete(new LambdaQueryWrapper<AiMessage>()
                    .in(AiMessage::getConversationId, conversationIds));
        }
        conversationMapper.delete(new LambdaQueryWrapper<AiConversation>()
                .eq(AiConversation::getUserId, userId));
        traceMapper.delete(new LambdaQueryWrapper<AiExecutionTrace>()
                .eq(AiExecutionTrace::getUserId, userId));
        usageMapper.delete(new LambdaQueryWrapper<AiUsageStat>().eq(AiUsageStat::getUserId, userId));
        recommendLogMapper.delete(new LambdaQueryWrapper<AiRecommendLog>().eq(AiRecommendLog::getUserId, userId));
        insightReportMapper.delete(new LambdaQueryWrapper<AiInsightReport>()
                .eq(AiInsightReport::getRequestedBy, userId));
        userMapper.deleteById(userId);
        log.info("M18_E2E 观察数据已清理：userId={}", userId);
    }

    /** 只保留可读的失败原因，并截断到固定长度（不包含任何凭据信息）。 */
    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null) {
            return exception.getClass().getSimpleName();
        }
        String normalized = message.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 160 ? normalized : normalized.substring(0, 160);
    }
}

package com.bitforum.ai.observation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
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
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bitforum.ai.entity.AiExecutionTrace;
import com.bitforum.ai.entity.AiUsageStat;
import com.bitforum.ai.mapper.AiExecutionTraceMapper;
import com.bitforum.ai.mapper.AiUsageStatMapper;
import com.bitforum.ai.trace.TraceRecorder;
import com.bitforum.ai.trace.TraceStepType;
import com.bitforum.ai.usage.AiTokenBudgetGuard;
import com.bitforum.ai.usage.AiUsageRecorder;

/**
 * M18 本地持久化开销观察（确定性；**不调用任何模型**）。
 *
 * <p>度量"一次 AI 调用里纯本地的那部分"：预算查询（SELECT）、轨迹创建（INSERT）、
 * 轨迹收尾（UPDATE + 用量 INSERT）、用量写入（INSERT），以及它们的合计。
 * 真实端到端耗时（含 DeepSeek）属阶段 2C，不在本类范围。
 *
 * <p><b>为什么可以只测主线程等待</b>：阶段 2A 已核实 AI 域的 {@code @Transactional} 只出现在
 * {@code AiConversationService}，轨迹/用量/预算的 Mapper 调用都不在长事务内（写入即提交），
 * 且 AI 域没有任何 {@code @Async} —— 因此这些写入就发生在调用线程上，
 * 不存在需要额外记录的"异步完成时间"。
 *
 * <p><b>刻意不做的事</b>：不为了对照而关闭轨迹/用量写入（那会改动生产路由，任务书第 7 条禁止），
 * 也不引入任何性能测试平台（JMeter/K6/Prometheus 均在禁项内）。
 *
 * <p>每轮：预热 {@value #WARMUP_RUNS} 次 → 采集 {@value #SAMPLE_SIZE} 个样本 →
 * 输出 avg/P50/P95/min/max 与**原始样本数组**，并落盘到
 * {@code target/m18-observation/local-persistence-<时间戳>.txt}（{@code target/} 不入 Git）。
 * 造出的数据按 traceId 精确清理，只使用测试用户 {@value #CHAT_USER_ID} / {@value #SEEDED_USER_ID}。
 *
 * <pre>
 *   ./mvnw -s maven-settings.xml -Dtest=M18PersistenceObservationTest test
 * </pre>
 */
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
class M18PersistenceObservationTest {

    private static final Logger log = LoggerFactory.getLogger(M18PersistenceObservationTest.class);

    private static final int WARMUP_RUNS = 5;
    private static final int SAMPLE_SIZE = 35;
    /** "当日无用量"的预算查询用户。 */
    private static final long CHAT_USER_ID = 99091L;
    /** "当日已有用量"的预算查询用户（预置 {@link #SEEDED_ROWS} 行）。 */
    private static final long SEEDED_USER_ID = 99092L;
    private static final int SEEDED_ROWS = 50;
    private static final String OBSERVATION_MODEL = "local-observation";
    private static final Path OUTPUT_DIR = Path.of("target", "m18-observation");

    @Autowired
    private AiTokenBudgetGuard budgetGuard;
    @Autowired
    private TraceRecorder traceRecorder;
    @Autowired
    private AiUsageRecorder usageRecorder;
    @Autowired
    private AiExecutionTraceMapper traceMapper;
    @Autowired
    private AiUsageStatMapper usageMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 本类造出的所有 traceId（轨迹行与用量行都按它清理）。 */
    private final List<String> createdTraceIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        // 度量 trace_start 时会让 ThreadLocal 上留一个未收尾的会话，这里统一清掉，
        // 避免污染同一 JVM 里随后运行的其它测试
        traceRecorder.clear();
        if (createdTraceIds.isEmpty()) {
            return;
        }
        traceMapper.delete(new LambdaQueryWrapper<AiExecutionTrace>()
                .in(AiExecutionTrace::getTraceId, createdTraceIds));
        usageMapper.delete(new LambdaQueryWrapper<AiUsageStat>()
                .in(AiUsageStat::getTraceId, createdTraceIds));
        createdTraceIds.clear();
    }

    @Test
    void shouldRecordLocalPersistenceObservation() throws IOException {
        seedSeededUserUsage();

        warmUp(() -> budgetGuard.check(CHAT_USER_ID));
        warmUp(() -> budgetGuard.check(SEEDED_USER_ID));
        warmUp(this::traceStart);
        warmUp(this::traceStartAndFinish);
        warmUp(this::writeUsage);
        warmUp(this::fullLocalCycle);

        List<Report> reports = new ArrayList<>();
        // 预算查询：空聚合 vs 当日已有 50 行的聚合
        reports.add(measure("budget_query_empty", () -> { }, () -> budgetGuard.check(CHAT_USER_ID)));
        reports.add(measure("budget_query_with_rows", () -> { }, () -> budgetGuard.check(SEEDED_USER_ID)));
        // 轨迹创建：只有 INSERT
        reports.add(measure("trace_start", () -> { }, this::traceStart));
        // 轨迹收尾：UPDATE + 用量 INSERT（这是实现的真实行为，故指标说明里注明"含 usage"）
        reports.add(measure("trace_finish", this::traceStart,
                () -> traceRecorder.finish(OBSERVATION_MODEL, 12, 3, 15)));
        // 用量写入：单独 INSERT（与 trace_finish 相减即得 UPDATE 的净代价）
        reports.add(measure("usage_insert", () -> { }, this::writeUsage));
        // 一次对话的"纯本地"合计
        reports.add(measure("local_overhead_total", () -> { }, this::fullLocalCycle));

        Path evidence = persist(reports);
        reports.forEach(report -> log.info("M18_OBSERVATION {}", report.line()));
        log.info("M18_OBSERVATION 原始证据文件：{}", evidence.toAbsolutePath());
    }

    // ==================== 被测动作 ====================

    /**
     * 仅创建轨迹（INSERT `ai_execution_trace`）。
     *
     * <p><b>刻意不清 ThreadLocal</b>：{@code finish} 依赖当前线程的轨迹会话，
     * 若在这里 clear，紧随其后的 finish 会因上下文为空而退化成空操作 —— 那样测出来的
     * "收尾耗时"其实是"什么都没做"的耗时。
     */
    private void traceStart() {
        var session = traceRecorder.start(AiExecutionTrace.SCENE_CHAT, "OBSERVATION", CHAT_USER_ID,
                null, null, null);
        createdTraceIds.add(session.traceId());
    }

    /** 创建 + 收尾（用于预热收尾路径）。 */
    private void traceStartAndFinish() {
        traceStart();
        traceRecorder.finish(OBSERVATION_MODEL, 12, 3, 15);
    }

    /** 仅写入一条用量明细（INSERT `ai_usage_stat`）。 */
    private void writeUsage() {
        String traceId = "observation-usage-" + UUID.randomUUID();
        createdTraceIds.add(traceId);
        usageRecorder.record(traceId, AiExecutionTrace.SCENE_CHAT, "OBSERVATION", CHAT_USER_ID,
                OBSERVATION_MODEL, 12, 3, 15, 0, AiUsageStat.RESULT_SUCCESS);
    }

    /**
     * 一次对话里"模型调用之外"的全部本地开销：
     * 输入保护 → 预算查询 → 轨迹创建 → 3 个步骤 → 收尾（含用量写入）。
     */
    private void fullLocalCycle() {
        budgetGuard.checkInputLength("M18 本地开销观察：这是一条固定长度的输入，用于模拟用户提问的长度量级。");
        budgetGuard.check(CHAT_USER_ID);
        var session = traceRecorder.start(AiExecutionTrace.SCENE_CHAT, "OBSERVATION", CHAT_USER_ID,
                null, null, null);
        createdTraceIds.add(session.traceId());
        traceRecorder.step(TraceStepType.ROUTE, "OBSERVATION", "本地观察：路由步骤");
        traceRecorder.step(TraceStepType.RETRIEVE, "知识库检索", "命中 0 个片段");
        traceRecorder.step(TraceStepType.PERSIST, "会话落库", "本地观察：落库步骤");
        traceRecorder.finish(OBSERVATION_MODEL, 12, 3, 15);
    }

    /** 给"当日已有用量"的预算场景预置数据。 */
    private void seedSeededUserUsage() {
        for (int i = 0; i < SEEDED_ROWS; i++) {
            String traceId = "observation-seed-" + UUID.randomUUID();
            createdTraceIds.add(traceId);
            usageRecorder.record(traceId, AiExecutionTrace.SCENE_CHAT, "OBSERVATION", SEEDED_USER_ID,
                    OBSERVATION_MODEL, 10, 5, 15, 0, AiUsageStat.RESULT_SUCCESS);
        }
        assertEquals(SEEDED_ROWS, createdTraceIds.size());
    }

    // ==================== 度量与统计 ====================

    private void warmUp(Runnable action) {
        for (int i = 0; i < WARMUP_RUNS; i++) {
            action.run();
        }
    }

    /**
     * 采集样本：{@code setup} 不计时（用于把"准备一个待收尾的轨迹"排除在计时之外），
     * 只对 {@code action} 计时。
     */
    private Report measure(String operation, Runnable setup, Runnable action) {
        List<Long> samples = new ArrayList<>(SAMPLE_SIZE);
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            setup.run();
            long startedAt = System.nanoTime();
            action.run();
            samples.add(System.nanoTime() - startedAt);
        }
        assertEquals(SAMPLE_SIZE, samples.size(), operation + " 样本数不足");
        return new Report(operation, samples);
    }

    /** 一个场景的原始样本与统计值；统计可由 samples 复算。 */
    private record Report(String operation, List<Long> samples) {

        private double[] millisSorted() {
            double[] millis = samples.stream().mapToDouble(nanos -> nanos / 1_000_000.0).toArray();
            Arrays.sort(millis);
            return millis;
        }

        private String line() {
            double[] sorted = millisSorted();
            double average = Arrays.stream(sorted).average().orElseThrow();
            return String.format(Locale.ROOT,
                    "operation=%s n=%d average_ms=%.3f p50_ms=%.3f p95_ms=%.3f min_ms=%.3f max_ms=%.3f",
                    operation, samples.size(), average, percentile(sorted, 0.50), percentile(sorted, 0.95),
                    sorted[0], sorted[sorted.length - 1]);
        }

        private static double percentile(double[] sorted, double percentile) {
            int index = (int) Math.ceil(percentile * sorted.length) - 1;
            return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
        }
    }

    // ==================== 落盘 ====================

    private Path persist(List<Report> reports) throws IOException {
        Files.createDirectories(OUTPUT_DIR);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path file = OUTPUT_DIR.resolve("local-persistence-" + stamp + ".txt");
        try (PrintWriter writer = new PrintWriter(
                Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            writer.printf("# M18 本地持久化开销观察（确定性；不调用任何模型）%n");
            writer.printf("生成时间: %s%n", LocalDateTime.now());
            writer.printf("Java: %s (%s)%n", System.getProperty("java.version"),
                    System.getProperty("java.vendor"));
            writer.printf("OS: %s %s%n", System.getProperty("os.name"), System.getProperty("os.arch"));
            writer.printf("MySQL: %s%n", jdbcTemplate.queryForObject("SELECT VERSION()", String.class));
            writer.printf("ai_execution_trace 行数: %s（含本次观察写入的行）%n",
                    jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_execution_trace", Long.class));
            writer.printf("ai_usage_stat 行数: %s（含本次观察写入的行）%n",
                    jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_usage_stat", Long.class));
            writer.printf("写入线程: 调用线程（AI 域无 @Async；轨迹/用量/预算的 Mapper 不在长事务内）%n");
            writer.printf("预热次数: %d；样本量: %d%n", WARMUP_RUNS, SAMPLE_SIZE);
            writer.printf("预置数据: 预算有数据场景预置 %d 行当日 usage（用户 %d）%n", SEEDED_ROWS,
                    SEEDED_USER_ID);
            writer.printf("%n场景说明:%n");
            writer.printf("  budget_query_empty    AiTokenBudgetGuard.check —— 该用户当日无用量（空聚合）%n");
            writer.printf("  budget_query_with_rows AiTokenBudgetGuard.check —— 该用户当日已有 %d 行%n",
                    SEEDED_ROWS);
            writer.printf("  trace_start           TraceRecorder.start —— INSERT ai_execution_trace%n");
            writer.printf("  trace_finish          TraceRecorder.finish —— UPDATE 轨迹 + INSERT 用量（含 usage）%n");
            writer.printf("  usage_insert          AiUsageRecorder.record —— 单独 INSERT ai_usage_stat%n");
            writer.printf("  local_overhead_total  输入保护 + 预算查询 + 轨迹创建 + 3 步 + 收尾%n");
            writer.printf("%n结果:%n");
            reports.forEach(report -> writer.println(report.line()));
            writer.printf("%n原始样本（纳秒）:%n");
            for (Report report : reports) {
                writer.printf("%s=%s%n", report.operation(), report.samples());
            }
            writer.printf("%n说明: 统计值可由上述原始样本复算；P95 使用 ceil 取整的百分位；"
                    + "本机单实例串行观察，不代表并发能力或生产 QPS。%n");
        }
        assertTrue(Files.size(file) > 0, "证据文件应非空");
        return file;
    }
}

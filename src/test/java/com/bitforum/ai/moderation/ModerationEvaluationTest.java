package com.bitforum.ai.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 审核效果评测（M16），指标口径严格遵循评测规范 v1
 * （`docs/graduation/ai-agent-upgrade/m16-eval-protocol.md`）。
 *
 * <p><b>规范要点（本类只实现，不重新定义）</b>：
 *
 * <ul>
 *   <li><b>一级指标（安全，主指标）</b>：漏放率、误伤率、安全召回率。
 *       其中漏放率必须为 0，是本类的硬断言；</li>
 *   <li><b>二级指标（质量，参考）</b>：严格三分类准确率、REJECT 精确率、REJECT 召回率；</li>
 *   <li><b>必报分布</b>：标注 REVIEW 的样本落在 PASS/REVIEW/REJECT 各多少条、分类混淆矩阵；</li>
 *   <li><b>数据边界</b>：本测试只跑**开发集**；测试集在阈值冻结前不得查看输出。</li>
 * </ul>
 *
 * <p>样本来源：`scripts/ai-eval/moderation-dev-samples.json`（**人工构造的合成评测集**，
 * 不是线上真实分布），每条标注都引用规范条款。
 *
 * <p>默认不执行（真实 API 调用）：
 *
 * <pre>
 *   export DEEPSEEK_CHAT_ENABLED=true
 *   export DEEPSEEK_API_KEY="$(grep '^DEEPSEEK_API_KEY=' .env | cut -d= -f2-)"
 *   ./mvnw -Dtest=ModerationEvaluationTest test
 * </pre>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_CHAT_ENABLED", matches = "true")
class ModerationEvaluationTest {

    private static final Logger log = LoggerFactory.getLogger(ModerationEvaluationTest.class);

    /**
     * 开发集样本路径，支持逗号分隔的多个文件（便于分批维护样本）。
     *
     * <p>可用 {@code -Deval.samples=...} 覆盖；**测试集在阈值冻结前不应通过这里指定**。
     */
    private static final String SAMPLES_PROPERTY = resolveSetting("EVAL_SAMPLES", "eval.samples",
            "scripts/ai-eval/moderation-dev-samples.json,scripts/ai-eval/moderation-dev-samples-extra.json");
    private static final Path REPORT_PATH = Path.of(
            resolveSetting("EVAL_REPORT", "eval.report", "target/moderation-eval-report.md"));

    /**
     * 取值优先级：环境变量 &gt; JVM 系统属性 &gt; 默认值。
     *
     * <p>环境变量优先，是因为 Maven 的 {@code -D} 只作用于 Maven 自身的 JVM，
     * 不会传递给 surefire 派生的测试 JVM；指定测试集时用环境变量最可靠。
     */
    private static String resolveSetting(String envKey, String propertyKey, String defaultValue) {
        String fromEnv = System.getenv(envKey);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        return System.getProperty(propertyKey, defaultValue);
    }

    private static final String LABEL_REJECT = "REJECT";
    private static final String LABEL_PASS = "PASS";
    private static final String LABEL_REVIEW = "REVIEW";

    /** 一条标注样本（labelReason 必须引用评测规范条款） */
    private record Sample(String id, String targetType, String category, String label,
                          String labelReason, String content) {
    }

    /** 一条评测结果 */
    private record Outcome(Sample sample, ModerationDecision decision, double confidence,
                           double riskScore, String maxDimension, boolean degraded) {
    }

    @Autowired
    private ModerationAgent moderationAgent;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void evaluateDevSamples() throws IOException {
        List<Sample> samples = loadSamples();
        assertFalse(samples.isEmpty(), "样本文件为空：" + SAMPLES_PROPERTY);

        List<Outcome> outcomes = new ArrayList<>();
        for (Sample sample : samples) {
            ModerationTargetType type = ModerationTargetType.valueOf(sample.targetType());
            ModerationAgent.ModerationOutcome result = moderationAgent.analyze(type, sample.content());
            if (result.degraded() || result.assessment() == null) {
                outcomes.add(new Outcome(sample, ModerationDecision.REVIEW, 0d, 0d, "none", true));
                log.error(">>> [{}] 分析失败：{}", sample.id(), result.errorMessage());
                continue;
            }
            ModerationAssessment assessment = result.assessment();
            outcomes.add(new Outcome(sample, assessment.decisionEnum(), assessment.confidence(),
                    assessment.maxScore(), assessment.maxDimension(), false));
        }

        logByCategory(outcomes);

        String report = buildReport(outcomes);
        Files.createDirectories(REPORT_PATH.getParent());
        Files.writeString(REPORT_PATH, report);
        log.info(">>> 评测报告已写入：{}", REPORT_PATH.toAbsolutePath());

        // 硬约束 1（规范 §4.4）：结构化输出必须稳定
        long degraded = outcomes.stream().filter(Outcome::degraded).count();
        assertEquals(0, degraded, "存在分析失败的样本，评测结果不可用");

        // 硬约束 2（规范 §4.4）：漏放率必须为 0 —— 违规内容绝不能被判成 PASS
        List<String> missed = outcomes.stream()
                .filter(outcome -> LABEL_REJECT.equals(outcome.sample().label()))
                .filter(outcome -> outcome.decision() == ModerationDecision.PASS)
                .map(outcome -> outcome.sample().id() + "（" + outcome.sample().labelReason() + "）")
                .toList();
        assertTrue(missed.isEmpty(), "存在漏放（应拒绝却判放行），违反评测规范 §4.4：" + missed);
    }

    private List<Sample> loadSamples() throws IOException {
        List<Sample> all = new ArrayList<>();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (String pathText : SAMPLES_PROPERTY.split(",")) {
            Path path = Path.of(pathText.trim());
            assertTrue(Files.exists(path), "样本文件不存在：" + path.toAbsolutePath());
            List<Sample> batch = objectMapper.readValue(
                    Files.readString(path), new TypeReference<List<Sample>>() {
                    });
            for (Sample sample : batch) {
                assertTrue(ids.add(sample.id()), "样本 id 重复：" + sample.id());
                all.add(sample);
            }
        }
        return all;
    }

    private String buildReport(List<Outcome> outcomes) {
        int total = outcomes.size();
        List<Outcome> rejectSamples = filterByLabel(outcomes, LABEL_REJECT);
        List<Outcome> passSamples = filterByLabel(outcomes, LABEL_PASS);
        List<Outcome> reviewSamples = filterByLabel(outcomes, LABEL_REVIEW);

        // ===== 一级指标：安全（规范 §4.1）=====
        long missed = countDecision(rejectSamples, ModerationDecision.PASS);
        long safeCaught = rejectSamples.size() - missed;
        long falseAlarm = countDecision(passSamples, ModerationDecision.REJECT);

        // ===== 二级指标：质量（规范 §4.2）=====
        long exact = outcomes.stream()
                .filter(outcome -> outcome.decision().name().equals(outcome.sample().label()))
                .count();
        long rejectHit = countDecision(rejectSamples, ModerationDecision.REJECT);
        long predictedReject = countDecision(outcomes, ModerationDecision.REJECT);

        StringBuilder report = new StringBuilder();
        report.append("# M16 审核效果评测报告\n\n");
        report.append("> 样本集合：`%s`\n".formatted(SAMPLES_PROPERTY));
        report.append("> 样本来源：**人工构造的合成评测集**，非线上真实分布；每条标注引用评测规范 v1 条款\n");
        report.append("> 指标口径：`docs/graduation/ai-agent-upgrade/m16-eval-protocol.md` §4\n\n");

        report.append("## 一、一级指标（安全，主指标）\n\n");
        report.append("| 指标 | 数值 | 口径 |\n| --- | --- | --- |\n");
        report.append(metricRow("**漏放率**（必须为 0）", missed, rejectSamples.size(),
                "标注 REJECT 却判 PASS —— 违规内容被放过"));
        report.append(metricRow("**误伤率**", falseAlarm, passSamples.size(),
                "标注 PASS 却判 REJECT —— 正常内容被拒绝"));
        report.append(metricRow("**安全召回率**", safeCaught, rejectSamples.size(),
                "标注 REJECT 且判 REJECT 或 REVIEW（REVIEW 由人工兜底）"));
        report.append('\n');

        report.append("## 二、二级指标（质量，参考）\n\n");
        report.append("| 指标 | 数值 | 口径 |\n| --- | --- | --- |\n");
        report.append(metricRow("严格三分类准确率", exact, total, "三档判断与标注完全一致"));
        report.append(metricRow("REJECT 精确率", rejectHit, predictedReject, "判 REJECT 中真正该拒绝的比例"));
        report.append(metricRow("REJECT 召回率", rejectHit, rejectSamples.size(), "该拒绝的内容被判 REJECT 的比例"));
        report.append('\n');

        report.append("## 三、REVIEW 样本落点分布（规范 §4.3 必报）\n\n");
        if (reviewSamples.isEmpty()) {
            report.append("本批次无标注为 REVIEW 的样本。\n\n");
        } else {
            report.append("标注 REVIEW 的样本共 %d 条，模型落点如下：\n\n".formatted(reviewSamples.size()));
            report.append("| 落点 | 条数 |\n| --- | --- |\n");
            report.append("| 判 PASS（偏宽松） | %d |\n".formatted(countDecision(reviewSamples, ModerationDecision.PASS)));
            report.append("| 判 REVIEW（一致） | %d |\n".formatted(countDecision(reviewSamples, ModerationDecision.REVIEW)));
            report.append("| 判 REJECT（偏严格） | %d |\n\n".formatted(countDecision(reviewSamples, ModerationDecision.REJECT)));
        }

        report.append("## 四、分类混淆矩阵（规范 §4.3 必报）\n\n");
        report.append("| 类别 | 样本数 | 一致 | 判 PASS | 判 REVIEW | 判 REJECT |\n");
        report.append("| --- | --- | --- | --- | --- | --- |\n");
        Map<String, List<Outcome>> byCategory = outcomes.stream()
                .collect(Collectors.groupingBy(outcome -> outcome.sample().category(),
                        LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<String, List<Outcome>> entry : byCategory.entrySet()) {
            List<Outcome> list = entry.getValue();
            long same = list.stream()
                    .filter(outcome -> outcome.decision().name().equals(outcome.sample().label()))
                    .count();
            report.append("| %s | %d | %d | %d | %d | %d |\n".formatted(
                    entry.getKey(), list.size(), same,
                    countDecision(list, ModerationDecision.PASS),
                    countDecision(list, ModerationDecision.REVIEW),
                    countDecision(list, ModerationDecision.REJECT)));
        }
        report.append('\n');

        report.append("## 五、逐条结果\n\n");
        report.append("| ID | 类别 | 标注 | AI 判断 | 置信 | 风险分 | 最高维度 | 一致 |\n");
        report.append("| --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (Outcome outcome : outcomes) {
            report.append("| %s | %s | %s | %s | %.2f | %.2f | %s | %s |\n".formatted(
                    outcome.sample().id(),
                    outcome.sample().category(),
                    outcome.sample().label(),
                    outcome.degraded() ? "FAILED" : outcome.decision(),
                    outcome.confidence(),
                    outcome.riskScore(),
                    outcome.maxDimension(),
                    outcome.decision().name().equals(outcome.sample().label()) ? "✅" : "❌"));
        }
        report.append('\n');

        report.append("## 六、不一致明细\n\n");
        List<Outcome> mismatches = outcomes.stream()
                .filter(outcome -> !outcome.decision().name().equals(outcome.sample().label()))
                .toList();
        if (mismatches.isEmpty()) {
            report.append("无。\n\n");
        } else {
            for (Outcome outcome : mismatches) {
                report.append("- **%s**（%s）：标注 %s / AI %s —— %s\n".formatted(
                        outcome.sample().id(), outcome.sample().category(),
                        outcome.sample().label(), outcome.decision(), outcome.sample().labelReason()));
            }
            report.append('\n');
        }

        report.append("## 七、自动放行阈值扫描（规范 §6）\n\n");
        appendThresholdScan(report, outcomes);

        report.append("## 八、结论与限制\n\n");
        report.append("- 本报告基于**合成样本**，不能等同于线上表现；\n");
        report.append("- 阈值必须按规范 §6 在开发集扫描、冻结后，再在**独立测试集**上一次性验证；\n");
        report.append("- REVIEW 计入「未放过」是按「人在回路兜底」口径；严格准确率仅作二级参考。\n");

        return report.toString();
    }

    /** 阈值扫描网格（规范 §6） */
    private static final double[] CONFIDENCE_GRID = {0.80, 0.85, 0.90, 0.95, 0.99};
    private static final double[] MAX_RISK_GRID = {0.01, 0.05, 0.10, 0.20, 0.30};

    /**
     * 阈值扫描（规范 §6）。
     *
     * <p>在候选网格上枚举 `(confidenceThreshold, maxRisk)`，只保留
     * 「自动放行的样本中不含任何非 PASS 标注」的组合，并在其中选放行条数最多的一组。
     * 扫描结果必须在**独立测试集**上验证通过后，才可写入配置并冻结。
     */
    private void appendThresholdScan(StringBuilder report, List<Outcome> outcomes) {
        long passTotal = outcomes.stream()
                .filter(outcome -> LABEL_PASS.equals(outcome.sample().label()))
                .count();

        report.append("候选网格：confidence ∈ %s，maxRisk ∈ %s；\n\n"
                .formatted(java.util.Arrays.toString(CONFIDENCE_GRID), java.util.Arrays.toString(MAX_RISK_GRID)));
        report.append("筛选规则（规范 §6）：自动放行集合中不得出现任何非 PASS 标注，在此前提下取放行条数最多的一组。\n\n");
        report.append("| confidence ≥ | risk < | 自动放行 | 占标注 PASS | 其中非 PASS | 结论 |\n");
        report.append("| --- | --- | --- | --- | --- | --- |\n");

        double bestConfidence = -1d;
        double bestRisk = -1d;
        int bestCount = -1;

        for (double confidence : CONFIDENCE_GRID) {
            for (double risk : MAX_RISK_GRID) {
                List<Outcome> autoApproved = outcomes.stream()
                        .filter(outcome -> outcome.decision() == ModerationDecision.PASS)
                        .filter(outcome -> outcome.confidence() >= confidence)
                        .filter(outcome -> outcome.riskScore() < risk)
                        .toList();
                long nonPass = autoApproved.stream()
                        .filter(outcome -> !LABEL_PASS.equals(outcome.sample().label()))
                        .count();
                boolean usable = nonPass == 0 && !autoApproved.isEmpty();
                double coverage = passTotal == 0 ? 0d : (double) autoApproved.size() / passTotal;

                report.append("| %.2f | %.2f | %d | %.1f%% | %d | %s |\n".formatted(
                        confidence, risk, autoApproved.size(), coverage * 100, nonPass,
                        usable ? "可用" : (nonPass > 0 ? "**会放行非 PASS**" : "无样本可放行")));

                if (usable && autoApproved.size() > bestCount) {
                    bestCount = autoApproved.size();
                    bestConfidence = confidence;
                    bestRisk = risk;
                }
            }
        }
        report.append('\n');

        if (bestCount < 0) {
            report.append("**没有任何组合满足「零非 PASS 放行」**，说明当前 PASS 判定还不可靠，"
                    + "应先调整提示词再重跑扫描。\n\n");
            return;
        }
        report.append("**扫描结论**：满足「零非 PASS 放行」的组合中，放行条数最多的是 "
                + "`confidence ≥ %.2f` 且 `risk < %.2f`（放行 %d 条，占标注 PASS 的 %.1f%%）。\n\n"
                        .formatted(bestConfidence, bestRisk, bestCount,
                                passTotal == 0 ? 0d : bestCount * 100.0 / passTotal));
        report.append("> 该组合还需在**独立测试集**上验证（规范 §6 步骤 7），"
                + "通过后才写入 `application.yml` 并冻结为正式阈值。\n\n");
    }

    private List<Outcome> filterByLabel(List<Outcome> outcomes, String label) {
        return outcomes.stream().filter(outcome -> label.equals(outcome.sample().label())).toList();
    }

    private long countDecision(List<Outcome> outcomes, ModerationDecision decision) {
        return outcomes.stream().filter(outcome -> outcome.decision() == decision).count();
    }

    private String metricRow(String name, long numerator, long denominator, String note) {
        double value = denominator == 0 ? 0d : (double) numerator / denominator;
        return "| %s | **%.1f%%**（%d/%d） | %s |\n".formatted(name, value * 100, numerator, denominator, note);
    }

    /** 便于人工比对：把结果按类别分组打印到日志。 */
    private void logByCategory(List<Outcome> outcomes) {
        Map<String, List<Outcome>> byCategory = outcomes.stream()
                .collect(Collectors.groupingBy(outcome -> outcome.sample().category(),
                        LinkedHashMap::new, Collectors.toList()));
        byCategory.forEach((category, list) -> log.info(">>> 类别 {}：{}", category, list.stream()
                .map(outcome -> outcome.sample().id() + "(" + outcome.sample().label() + ")=" + outcome.decision())
                .toList()));
    }
}

package com.bitforum.ai.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 审核 Agent 真实调用冒烟测试（M16）。
 *
 * <p>它是 findings.md 待验证事项 T7 的**正式实现版本**：T7 起初用临时探针验证了
 * 「结构化输出能被稳定解析」这一通用能力（结论与对照数据保留在 findings.md 6.12），
 * 实现定型为五维固定字段后，改由本测试对**真实实现** {@link ModerationAgent} 复验。
 *
 * <p>验证两件事：
 *
 * <ol>
 *   <li><b>硬指标</b>：8 条样本全部成功返回、不降级，五维分与置信度都在 0~1、档位合法
 *       （固定字段设计天然保证五维齐全，见 {@link ModerationAssessment}）；</li>
 *   <li><b>决策分布</b>：打印每条样本的判定结果，供人工与 T7 的对照数据比较 ——
 *       **不对判定结果做强断言**，因为"应该判什么"属于产品策略（已由 M16 决策确定），
 *       其量化评估由带人工标注的评测集完成，而不是靠这个冒烟测试。</li>
 * </ol>
 *
 * <p>默认不执行（消耗真实 API 额度）：
 *
 * <pre>
 *   export DEEPSEEK_CHAT_ENABLED=true
 *   export DEEPSEEK_API_KEY="$(grep '^DEEPSEEK_API_KEY=' .env | cut -d= -f2-)"
 *   ./mvnw -Dtest=ModerationAgentSmokeTest test
 * </pre>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_CHAT_ENABLED", matches = "true")
class ModerationAgentSmokeTest {

    private static final Logger log = LoggerFactory.getLogger(ModerationAgentSmokeTest.class);

    private record Sample(String name, ModerationTargetType type, String content) {
    }

    private static final List<Sample> SAMPLES = List.of(
            new Sample("正常技术文章", ModerationTargetType.ARTICLE,
                    "Spring Boot 的自动配置通过 @ConditionalOnClass 等条件注解决定是否创建 bean，"
                            + "排查冲突时可以先看 ConditionEvaluationReport。"),
            new Sample("明显广告", ModerationTargetType.ARTICLE,
                    "全网最低价！加微信 abc123 领取 Java 全套课程，前 50 名送大厂内推名额，速度联系！"),
            new Sample("灌水内容", ModerationTargetType.COMMENT,
                    "顶顶顶顶顶顶顶顶顶顶顶顶顶顶顶顶顶顶顶顶"),
            new Sample("人身攻击", ModerationTargetType.COMMENT,
                    "楼主就是个傻子，写的什么垃圾东西，建议趁早别在这丢人现眼。"),
            new Sample("正常评论", ModerationTargetType.COMMENT,
                    "这个思路可以，不过要注意 Redis 和 MySQL 的数据一致性，最好加个定时补偿任务。"),
            new Sample("软性引流", ModerationTargetType.COMMENT,
                    "这种问题我一般用某工具一键解决，具体方法放在我主页了，感兴趣可以看看。"),
            new Sample("疑似诈骗", ModerationTargetType.ARTICLE,
                    "点击链接填写身份证和银行卡信息，即可领取国家专项创业补贴 5000 元，名额有限。"),
            new Sample("涉政影射", ModerationTargetType.COMMENT,
                    "这个问题涉及面太广，建议私下讨论，公开场合不方便展开。"));

    @Autowired
    private ModerationAgent moderationAgent;

    @Test
    void shouldReturnCompleteAssessmentForEverySample() {
        int success = 0;
        List<String> failures = new ArrayList<>();
        List<String> distribution = new ArrayList<>();

        for (Sample sample : SAMPLES) {
            ModerationAgent.ModerationOutcome outcome = moderationAgent.analyze(sample.type(), sample.content());

            if (outcome.degraded() || outcome.assessment() == null) {
                failures.add(sample.name() + " → " + outcome.errorMessage());
                log.error(">>> [{}] 分析失败：{}", sample.name(), outcome.errorMessage());
                continue;
            }

            ModerationAssessment assessment = outcome.assessment();
            assertValidRange(assessment, sample.name());
            success++;

            distribution.add(assessment.decisionEnum().name());
            log.info(">>> [{}] decision={} confidence={} riskScore={} 最高维度={} | {}",
                    sample.name(),
                    assessment.decisionEnum(),
                    String.format("%.2f", assessment.confidence()),
                    String.format("%.2f", assessment.maxScore()),
                    assessment.maxDimension(),
                    assessment.summary());
            log.info("      五维：harmful={} promotion={} fraud={} spam={} sensitive={}",
                    String.format("%.2f", assessment.harmfulScore()),
                    String.format("%.2f", assessment.promotionScore()),
                    String.format("%.2f", assessment.fraudScore()),
                    String.format("%.2f", assessment.spamScore()),
                    String.format("%.2f", assessment.sensitiveScore()));
        }

        log.info(">>> 冒烟汇总：{} 条样本，成功 {} 条；决策分布 = {}", SAMPLES.size(), success, distribution);

        assertEquals(SAMPLES.size(), success, "存在分析失败的样本：" + failures);
        assertFalse(distribution.isEmpty());
        // 至少要有内容被判为 PASS 而不是"一律转人工"——T7 的 A 版提示词正是这个毛病
        assertTrue(distribution.contains(ModerationDecision.PASS.name()),
                "所有样本都没有判 PASS，可能存在'一律转人工'倾向：" + distribution);
    }

    /** 校验模型返回的分值都在 0~1、档位在允许集合内。 */
    private void assertValidRange(ModerationAssessment assessment, String sampleName) {
        assertNotNull(assessment.decision(), sampleName + " 缺少 decision");
        assertTrue(ModerationDecision.fromName(assessment.decision()) != null, sampleName + " 档位非法");

        for (double score : new double[] {
                assessment.confidence(), assessment.harmfulScore(), assessment.promotionScore(),
                assessment.fraudScore(), assessment.spamScore(), assessment.sensitiveScore() }) {
            assertTrue(score >= 0d && score <= 1d,
                    sampleName + " 存在越界分值：" + score + "（ModerationAgent 应已钳制到 0~1）");
        }
    }
}

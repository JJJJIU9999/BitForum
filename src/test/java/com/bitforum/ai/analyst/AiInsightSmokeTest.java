package com.bitforum.ai.analyst;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.bitforum.ai.entity.AiInsightReport;

/**
 * 运营洞察的端到端冒烟测试（M17）：真实模型调用 + 真实异步时序 + 真实落库。
 *
 * <p>覆盖的东西是单元测试覆盖不到的：单线程执行器真的把任务跑起来了吗？
 * 生成完成后那条 PENDING 记录真的被写回了吗？报告正文与统计快照都存下来了吗？
 *
 * <p>默认不执行（消耗真实 API 额度）：
 *
 * <pre>
 *   export DEEPSEEK_CHAT_ENABLED=true
 *   export DEEPSEEK_API_KEY="$(grep '^DEEPSEEK_API_KEY=' .env | cut -d= -f2-)"
 *   ./mvnw -s maven-settings.xml -Dtest=AiInsightSmokeTest test
 * </pre>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_CHAT_ENABLED", matches = "true")
class AiInsightSmokeTest {

    private static final Logger log = LoggerFactory.getLogger(AiInsightSmokeTest.class);

    /** 异步生成的等待上限（秒）：单线程池 + 一次模型调用，通常几秒内完成。 */
    private static final int WAIT_SECONDS = 90;

    @Autowired
    private AiInsightGenerationService generationService;

    @Autowired
    private AiInsightService insightService;

    @Test
    void shouldGenerateInsightAsynchronouslyAndPersistIt() throws Exception {
        Optional<AiInsightReport> accepted = generationService.trigger(null);
        Assumptions.assumeTrue(accepted.isPresent(),
                "已有洞察正在生成（可能是并发跑的其它任务），本次跳过");

        Long reportId = accepted.get().getId();
        assertEquals(AiInsightReport.STATUS_PENDING, accepted.get().getStatus(),
                "触发后应立刻返回一条 PENDING 记录（不让 HTTP 等模型）");
        log.info(">>> 已受理洞察生成：reportId={}", reportId);

        AiInsightReport finished = waitForCompletion(reportId);

        assertNotNull(finished, "等待 " + WAIT_SECONDS + " 秒后任务仍未结束");
        assertFalse(AiInsightReport.STATUS_PENDING.equals(finished.getStatus()),
                "记录不能停留在 PENDING —— 否则前端会永远转圈");
        assertEquals(AiInsightReport.STATUS_SUCCESS, finished.getStatus(),
                "生成应成功（失败原因：" + finished.getErrorMessage() + "）");
        assertNotNull(finished.getContent(), "报告正文不能为空");
        assertNotNull(finished.getDataSnapshot(), "统计快照必须同时留存，否则报告里的数字无法对账");
        assertTrue(finished.getDataSnapshot().contains("userStats"), "快照应包含真实统计");
        assertNotNull(finished.getLatencyMs());
        assertNotNull(finished.getTotalTokens(), "应记录 token 用量（M18 的成本管控要用）");

        log.info("""

                ================= 运营洞察端到端结果 =================
                reportId：{}，状态：{}，模型：{}，耗时：{} ms，tokens：{}
                报告正文：
                {}
                ======================================================
                """,
                finished.getId(), finished.getStatus(), finished.getModel(),
                finished.getLatencyMs(), finished.getTotalTokens(), finished.getContent());
    }

    private AiInsightReport waitForCompletion(Long reportId) throws InterruptedException {
        for (int i = 0; i < WAIT_SECONDS; i++) {
            Thread.sleep(1000);
            Optional<AiInsightReport> latest = insightService.recent(5).stream()
                    .filter(report -> reportId.equals(report.getId()))
                    .findFirst();
            if (latest.isPresent() && !AiInsightReport.STATUS_PENDING.equals(latest.get().getStatus())) {
                return latest.get();
            }
        }
        return null;
    }
}

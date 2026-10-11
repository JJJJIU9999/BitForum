package com.bitforum.ai.analyst;

import java.util.Optional;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.bitforum.ai.agent.AgentType;
import com.bitforum.ai.entity.AiExecutionTrace;
import com.bitforum.ai.entity.AiInsightReport;
import com.bitforum.ai.trace.TraceDegradeReason;
import com.bitforum.ai.trace.TraceRecorder;
import com.bitforum.ai.trace.TraceSession;
import com.bitforum.ai.trace.TraceStepType;

/**
 * 运营洞察的触发与异步编排（M17）。
 *
 * <p>落地产品决策第 4 条：**管理员主动触发 + 异步生成 + 结果落库**。
 * 生成要调用大模型（实测数秒），因此绝不让 HTTP 请求同步等待 ——
 * 触发接口立刻返回一条 PENDING 记录，前端凭它轮询状态。
 *
 * <p><b>时序</b>：
 * <ol>
 *   <li>校验当前没有生成中的报告（防连点重复烧额度）；</li>
 *   <li>**先**写入 PENDING 记录，再提交异步任务 —— 顺序不能反，
 *       否则前端在任务提交后、记录落库前查询会什么都看不到；</li>
 *   <li>异步任务调用 {@link AnalystAgent} 生成，把结果写回同一条记录。</li>
 * </ol>
 *
 * <p><b>执行器是注入的</b>（{@code Executor}）：生产用单线程池，
 * 测试可以注入同步执行器（{@code Runnable::run}）来避免等待与不确定性。
 */
@Service
public class AiInsightGenerationService {

    private static final Logger log = LoggerFactory.getLogger(AiInsightGenerationService.class);

    private final AiInsightService insightService;
    private final AnalystAgent analystAgent;
    private final Executor insightExecutor;
    private final TraceRecorder traceRecorder;

    public AiInsightGenerationService(AiInsightService insightService,
                                      AnalystAgent analystAgent,
                                      @Qualifier("insightExecutor") Executor insightExecutor,
                                      TraceRecorder traceRecorder) {
        this.insightService = insightService;
        this.analystAgent = analystAgent;
        this.insightExecutor = insightExecutor;
        this.traceRecorder = traceRecorder;
    }

    /**
     * 受理一次生成请求并立即返回。
     *
     * @return 新写入的 PENDING 报告；若已有生成中的报告则返回空（调用方提示"正在生成"）
     */
    public Optional<AiInsightReport> trigger(Long requestedBy) {
        Optional<AiInsightReport> running = insightService.pending();
        if (running.isPresent()) {
            log.info("已有洞察正在生成（id={}），本次触发被忽略", running.get().getId());
            return Optional.empty();
        }

        AiInsightReport report = insightService.createPending(
                AiInsightReport.TRIGGER_MANUAL, requestedBy);

        // M18：轨迹在这里"开个头"，由线程池里的异步任务"接着写"。
        // 之所以能接上：traceId 随任务闭包显式传下去，子线程 attach 后 UPDATE 同一行
        // （T13 实测：ThreadLocal 不包装必然丢失，所以必须是显式传递）
        TraceSession trace = traceRecorder.start(AiExecutionTrace.SCENE_INSIGHT,
                AgentType.ANALYST.name(), requestedBy, null, null, report.getId());
        traceRecorder.step(TraceStepType.ASYNC, "提交异步生成",
                "reportId=" + report.getId() + "，已交给洞察生成线程池（单线程，串行执行）");

        insightExecutor.execute(() -> generate(report.getId(), requestedBy,
                trace == null ? null : trace.traceId()));
        // 请求线程到此为止：清掉上下文，避免后续操作误挂到这条尚未完成的轨迹上。
        // 注意这里**不 finish** —— 轨迹要等异步段跑完才由子线程收尾（状态仍是 RUNNING）
        traceRecorder.clear();
        return Optional.of(report);
    }

    /** 异步任务体：生成并写回结果。任何异常都要让记录离开 PENDING 状态。 */
    private void generate(Long reportId, Long requestedBy, String traceId) {
        long startedAt = System.currentTimeMillis();
        // 在子线程里重新挂载这条轨迹（父线程的 ThreadLocal 不会自动传过来）
        traceRecorder.attachOrStart(traceId, AiExecutionTrace.SCENE_INSIGHT,
                AgentType.ANALYST.name(), requestedBy, null, null, reportId);
        try {
            AnalystAgent.InsightOutcome outcome = analystAgent.analyze();
            insightService.complete(reportId, outcome);
            traceRecorder.step(TraceStepType.LLM_CALL, outcome.model(),
                    "prompt=" + outcome.promptTokens() + ", completion=" + outcome.completionTokens()
                            + "，工具调用 " + outcome.toolCallCount() + " 次",
                    outcome.latencyMillis(), outcome.totalTokens());
            // 降级原因由 AnalystAgent 内部的 AiDegradeGuard 统一记录（M18 模块 3），
            // 这里不再重复记 —— 降级记录入口收敛到 Guard 一处
            traceRecorder.finish(outcome.model(), outcome.promptTokens(), outcome.completionTokens(),
                    outcome.totalTokens());
        } catch (RuntimeException exception) {
            // AnalystAgent 本身不抛异常（失败返回降级结果），这里是防御性兜底：
            // 记录绝不能永远停在 PENDING，否则前端会一直转圈、也再无法触发新的生成
            log.error("运营洞察异步生成异常：reportId={}", reportId, exception);
            insightService.markFailed(reportId, exception.getMessage());
            traceRecorder.finishFailed(exception.getMessage());
        }
        log.debug("运营洞察异步任务结束：reportId={}，耗时 {} ms",
                reportId, System.currentTimeMillis() - startedAt);
    }
}

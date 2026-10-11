package com.bitforum.ai.analyst;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.bitforum.ai.entity.AiInsightReport;
import com.bitforum.ai.trace.TraceRecorder;

/**
 * 运营洞察异步触发的单元测试（M17）。
 *
 * <p>执行器注入为**同步执行器**（{@code Runnable::run}）：这样不需要等待、也不存在
 * "任务还没跑完断言就执行了"的不确定性，测试依然覆盖了完整的触发 → 生成 → 写回时序。
 *
 * <p>重点验证三件事：
 * <ol>
 *   <li>先落 PENDING 再执行 —— 顺序反了前端会什么都查不到；</li>
 *   <li>已有生成中任务时拒绝新触发 —— 防止管理员连点把额度烧光；</li>
 *   <li>异常也绝不让记录停在 PENDING —— 否则前端会永远转圈且再也无法触发。</li>
 * </ol>
 */
class AiInsightGenerationServiceTest {

    private AiInsightService insightService;
    private AnalystAgent analystAgent;
    private AiInsightGenerationService service;

    @BeforeEach
    void setUp() {
        insightService = mock(AiInsightService.class);
        analystAgent = mock(AnalystAgent.class);
        // M18：轨迹记录器在这里只作为依赖存在（mock 的 start 返回 null，轨迹相关调用全部静默），
        // 本测试关心的是"触发 → 生成 → 写回"的时序，与轨迹无关
        service = new AiInsightGenerationService(insightService, analystAgent, Runnable::run,
                mock(TraceRecorder.class));
    }

    @Test
    void shouldCreatePendingThenCompleteSynchronously() {
        AiInsightReport pending = new AiInsightReport();
        pending.setId(7L);
        when(insightService.pending()).thenReturn(Optional.empty());
        when(insightService.createPending(any(), any())).thenReturn(pending);

        AnalystAgent.InsightOutcome outcome = AnalystAgent.InsightOutcome.of(
                "社区处于起步阶段……", "{\"userStats\":{\"total\":5}}",
                LocalDateTime.now(), "deepseek-flash", 1553, 715, 2268, 4780L, 1);
        when(analystAgent.analyze()).thenReturn(outcome);

        Optional<AiInsightReport> result = service.trigger(13L);

        assertTrue(result.isPresent());
        assertEquals(7L, result.get().getId());
        verify(insightService).createPending(AiInsightReport.TRIGGER_MANUAL, 13L);
        verify(insightService).complete(7L, outcome);
    }

    @Test
    void shouldRejectTriggerWhenAlreadyGenerating() {
        AiInsightReport running = new AiInsightReport();
        running.setId(9L);
        when(insightService.pending()).thenReturn(Optional.of(running));

        assertTrue(service.trigger(13L).isEmpty(), "已有生成中任务时应拒绝新的触发");

        verify(insightService, never()).createPending(any(), any());
        verify(analystAgent, never()).analyze();
    }

    @Test
    void shouldMarkFailedWhenGenerationThrows() {
        AiInsightReport pending = new AiInsightReport();
        pending.setId(7L);
        when(insightService.pending()).thenReturn(Optional.empty());
        when(insightService.createPending(any(), any())).thenReturn(pending);
        when(analystAgent.analyze()).thenThrow(new IllegalStateException("模型网关 500"));

        service.trigger(13L);

        // 记录绝不能停在 PENDING
        verify(insightService).markFailed(7L, "模型网关 500");
        verify(insightService, never()).complete(any(), any());
    }
}

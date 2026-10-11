package com.bitforum.ai.degrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.bitforum.ai.trace.TraceDegradeReason;
import com.bitforum.ai.trace.TraceRecorder;

/**
 * 统一降级守卫的单元测试（M18）。
 *
 * <p>验证三件"统一"：异常归类成有限原因码、同一原因码给出同一句话、降级一定被记录。
 * 这三件正是 M18 验收第 3 条（"全站统一降级表现 + 用户看懂为什么降级"）的可测形式。
 */
class AiDegradeGuardTest {

    private TraceRecorder traceRecorder;
    private AiDegradeGuard guard;

    @BeforeEach
    void setUp() {
        traceRecorder = mock(TraceRecorder.class);
        guard = new AiDegradeGuard(traceRecorder);
    }

    /** 超时要被单独识别出来：它的用户文案与"服务出错"不同（前者建议稍后重试）。 */
    @Test
    void shouldClassifyTimeoutFromExceptionChain() {
        assertEquals(TraceDegradeReason.LLM_TIMEOUT,
                guard.classify(new SocketTimeoutException("read timed out")));
        assertEquals(TraceDegradeReason.LLM_TIMEOUT,
                guard.classify(new TimeoutException("timeout")));
        // 真实链路里超时通常被 Spring AI / RetryTemplate 包了好几层
        assertEquals(TraceDegradeReason.LLM_TIMEOUT,
                guard.classify(new RuntimeException("调用失败", new IllegalStateException(
                        new SocketTimeoutException("connect timed out")))));
        // 只靠类名/消息也能兜住别家实现的超时异常
        assertEquals(TraceDegradeReason.LLM_TIMEOUT,
                guard.classify(new RuntimeException("Request timed out after 60000ms")));
    }

    @Test
    void shouldFallBackToGenericErrorReason() {
        assertEquals(TraceDegradeReason.LLM_ERROR, guard.classify(new IllegalStateException("401 Unauthorized")));
        assertEquals(TraceDegradeReason.LLM_ERROR, guard.classify(null));
    }

    @Test
    void shouldRecordDegradeAndReturnUnifiedMessage() {
        String message = guard.degrade(TraceDegradeReason.EMPTY_RESPONSE, "模型返回空内容");

        verify(traceRecorder).degrade(eq(TraceDegradeReason.EMPTY_RESPONSE), eq("模型返回空内容"));
        assertEquals(TraceDegradeReason.userMessage(TraceDegradeReason.EMPTY_RESPONSE), message);
        // 文案里不应出现内部细节（"模型返回空内容"只进轨迹），用户看到的是可理解的一句话
        assertEquals("AI 没有返回有效内容，请换一种问法或稍后重试。", message);
    }

    /** 同一原因码在任何场景下必须是同一句话 —— 这正是"统一"的含义。 */
    @Test
    void shouldReturnSameMessageForSameReason() {
        assertEquals(guard.message(TraceDegradeReason.LLM_TIMEOUT),
                TraceDegradeReason.userMessage(TraceDegradeReason.LLM_TIMEOUT));
        assertEquals("AI 响应超时，已使用降级结果，请稍后重试。", guard.message(TraceDegradeReason.LLM_TIMEOUT));
    }

    @Test
    void shouldReturnActionResultWhenCallSucceeds() {
        String result = guard.call(() -> "正常结果", reason -> "降级结果");

        assertEquals("正常结果", result);
        verifyNoInteractions(traceRecorder);
    }

    @Test
    void shouldInvokeFallbackWithClassifiedReasonOnFailure() {
        String result = guard.call(() -> {
            throw new RuntimeException(new SocketTimeoutException("read timed out"));
        }, reason -> "降级：" + reason);

        assertEquals("降级：" + TraceDegradeReason.LLM_TIMEOUT, result);
        // 原始异常信息（含类名）作为 detail 进轨迹，便于排查；用户看到的是统一文案
        verify(traceRecorder).degrade(eq(TraceDegradeReason.LLM_TIMEOUT), contains("read timed out"));
    }
}

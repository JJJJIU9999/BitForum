package com.bitforum.ai.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bitforum.ai.entity.AiExecutionTrace;
import com.bitforum.ai.mapper.AiExecutionTraceMapper;
import com.bitforum.ai.rag.RagService;
import com.bitforum.ai.trace.TraceDegradeReason;
import com.bitforum.ai.trace.TraceRecorder;
import com.bitforum.ai.trace.TraceSession;

/**
 * 运行时模型故障注入：应用上下文已成功启动后，将 DeepSeek 请求显式导向不可达的回环端口。
 * 不使用真实凭据，也不会连接外网；测试锁定 QA 的统一降级与持久化 Trace 契约。
 */
@SpringBootTest(properties = {
        "spring.ai.deepseek.chat.enabled=true",
        "spring.ai.deepseek.api-key=fault-injection-placeholder",
        "spring.ai.deepseek.base-url=http://127.0.0.1:9",
        "spring.ai.retry.max-attempts=1",
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
class QaAgentFaultInjectionIntegrationTest {

    @Autowired
    private QaAgent qaAgent;
    @Autowired
    private TraceRecorder traceRecorder;
    @Autowired
    private AiExecutionTraceMapper traceMapper;

    @MockitoBean
    private RagService ragService;

    private String traceId;

    @AfterEach
    void cleanUp() {
        if (traceId != null) {
            traceMapper.delete(new LambdaQueryWrapper<AiExecutionTrace>()
                    .eq(AiExecutionTrace::getTraceId, traceId));
        }
    }

    @Test
    void shouldDegradeAndPersistTraceWhenModelEndpointIsUnavailable() {
        when(ragService.retrieve(anyString())).thenReturn(new RagService.RetrievalResult(List.of(), List.of(), 0));
        TraceSession session = traceRecorder.start(AiExecutionTrace.SCENE_CHAT, AgentType.QA.name(), 99092L,
                99092L, AiExecutionTrace.REF_CONVERSATION, 99092L);
        traceId = session.traceId();

        AgentResponse response = qaAgent.execute(
                new AgentContext(99092L, 99092L, "故障注入：模型端点不可达时应如何处理？"), List.of());
        traceRecorder.finish(null, response.promptTokens(), response.completionTokens(), response.totalTokens());

        assertTrue(response.degraded(), "不可达模型端点必须返回降级结果，而不是向上抛出连接异常");
        assertEquals(TraceDegradeReason.userMessage(TraceDegradeReason.LLM_ERROR), response.content());

        AiExecutionTrace trace = traceMapper.selectOne(new LambdaQueryWrapper<AiExecutionTrace>()
                .eq(AiExecutionTrace::getTraceId, traceId).last("LIMIT 1"));
        assertEquals(AiExecutionTrace.STATUS_DEGRADED, trace.getStatus());
        assertEquals(TraceDegradeReason.LLM_ERROR, trace.getDegradeReason());
        assertEquals(TraceDegradeReason.userMessage(TraceDegradeReason.LLM_ERROR), trace.getMessage());
    }
}

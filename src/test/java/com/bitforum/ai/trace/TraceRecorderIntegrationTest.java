package com.bitforum.ai.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bitforum.ai.entity.AiExecutionTrace;
import com.bitforum.ai.mapper.AiExecutionTraceMapper;

/**
 * 轨迹记录器的集成测试（M18）。
 *
 * <p>不启动事务（刻意）：跨线程续写要读另一条线程写入的行，而事务未提交的数据
 * 对其它连接不可见 —— 用 {@code @Transactional} 会让"异步续写"这个核心场景测不出来。
 * 改为每个用例结束按 traceId 精确清理自己造的数据。
 *
 * <p>覆盖四件事：
 * <ol>
 *   <li>开始 → 记步骤 → 收尾，落库为一行且 <b>route 由 ROUTE 步骤派生</b>；</li>
 *   <li>降级标记会落成 {@code DEGRADED} + 原因码 + 用户可读文案；</li>
 *   <li><b>跨线程续写</b>：另外一条线程 attach 同一 traceId，最终仍是同一行
 *       （而不是多出一条孤立记录）；</li>
 *   <li>工具包装：装饰后的回调被调用时，工具名/入参/返回值会进入轨迹步骤。</li>
 * </ol>
 */
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
class TraceRecorderIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(TraceRecorderIntegrationTest.class);

    @Autowired
    private TraceRecorder traceRecorder;
    @Autowired
    private AiExecutionTraceMapper traceMapper;
    @Autowired
    private com.bitforum.ai.mapper.AiUsageStatMapper usageMapper;

    private final List<String> createdTraceIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        if (!createdTraceIds.isEmpty()) {
            traceMapper.delete(new LambdaQueryWrapper<AiExecutionTrace>()
                    .in(AiExecutionTrace::getTraceId, createdTraceIds));
            // M18：轨迹收尾会顺带写一行用量明细，测试同样要清理，避免污染用量聚合
            usageMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<
                    com.bitforum.ai.entity.AiUsageStat>()
                    .in(com.bitforum.ai.entity.AiUsageStat::getTraceId, createdTraceIds));
            createdTraceIds.clear();
        }
    }

    @Test
    void shouldPersistStepsAndDeriveRoute() {
        TraceSession session = traceRecorder.start(AiExecutionTrace.SCENE_CHAT, "QA", 99001L,
                null, AiExecutionTrace.REF_CONVERSATION, 123L);
        createdTraceIds.add(session.traceId());

        traceRecorder.step(TraceStepType.ROUTE, "QA", "按会话 agent_type 路由到「问答助手」");
        traceRecorder.step(TraceStepType.RETRIEVE, "知识库检索", "命中 3 个片段", 42L, null);
        traceRecorder.step(TraceStepType.LLM_CALL, "deepseek-chat", "prompt=900, completion=120",
                1500L, 1020);
        traceRecorder.finish("deepseek-chat", 900, 120, 1020);

        AiExecutionTrace trace = find(session.traceId());
        assertEquals(AiExecutionTrace.STATUS_SUCCESS, trace.getStatus());
        assertEquals(3, trace.getStepCount());
        assertEquals(1020, trace.getTotalTokens());
        assertEquals("deepseek-chat", trace.getModel());
        assertEquals("按会话 agent_type 路由到「问答助手」", trace.getRoute(),
                "route 字段应由第一条 ROUTE 步骤派生");
        assertTrue(trace.getSteps().contains("命中 3 个片段"), "步骤明细应落库：" + trace.getSteps());
        assertTrue(trace.getLatencyMs() != null && trace.getLatencyMs() >= 0, "应记录端到端耗时");

        // 收尾后当前线程的上下文应被清理，避免后续代码误挂到已结束的轨迹上
        assertNull(TraceContext.current(), "收尾后必须清理 ThreadLocal");

        // M18：轨迹收尾同时写一行用量明细（统一埋点）—— 四个 Agent 都走 complete，
        // 因此审核与推荐理由的 token 缺口也一并被补上
        List<com.bitforum.ai.entity.AiUsageStat> usages = usageMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<
                        com.bitforum.ai.entity.AiUsageStat>()
                        .eq(com.bitforum.ai.entity.AiUsageStat::getTraceId, session.traceId()));
        assertEquals(1, usages.size(), "一次调用应产生且只产生一条用量明细");
        assertEquals(1020, usages.get(0).getTotalTokens());
        assertEquals("QA", usages.get(0).getAgentType());
        assertEquals(99001L, usages.get(0).getUserId());
        assertEquals(com.bitforum.ai.entity.AiUsageStat.RESULT_SUCCESS, usages.get(0).getResult());
        assertTrue(usages.get(0).getEstimatedCost().signum() > 0, "应按单价算出成本估算");
    }

    @Test
    void shouldMarkDegradedWithReasonAndUserMessage() {
        TraceSession session = traceRecorder.start(AiExecutionTrace.SCENE_CHAT, "QA", 99002L,
                null, null, null);
        createdTraceIds.add(session.traceId());

        traceRecorder.step(TraceStepType.ROUTE, "QA", "路由到问答助手");
        traceRecorder.degrade(TraceDegradeReason.RETRIEVE_FAILED,
                "provider connection reset: http://internal.example");
        traceRecorder.finish(null, null, null, null);

        AiExecutionTrace trace = find(session.traceId());
        assertEquals(AiExecutionTrace.STATUS_DEGRADED, trace.getStatus());
        assertEquals(TraceDegradeReason.RETRIEVE_FAILED, trace.getDegradeReason());
        assertEquals(TraceDegradeReason.userMessage(TraceDegradeReason.RETRIEVE_FAILED),
                trace.getMessage(), "降级必须带一句用户看得懂的说明");
        assertTrue(!trace.getSteps().contains("internal.example"),
                "轨迹也会被管理端展示，不能落入底层连接详情：" + trace.getSteps());
    }

    /** 异步链路的核心验证：另一条线程接着写，最终仍是同一行。 */
    @Test
    void shouldContinueSameTraceInAnotherThread() throws Exception {
        // 父线程只负责"开个头"（detached：不把上下文留在请求线程上）
        TraceSession detached = traceRecorder.startDetached(AiExecutionTrace.SCENE_INSIGHT,
                "ANALYST", 99003L, null, null, 55L);
        String traceId = detached.traceId();
        createdTraceIds.add(traceId);
        assertNull(TraceContext.current(), "startDetached 不应把上下文绑定到父线程");

        ExecutorService pool = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "m18-trace-test-worker");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<Boolean> future = pool.submit(() -> {
                TraceSession attached = traceRecorder.attach(traceId);
                assertNotNull(attached, "子线程应能按 traceId 挂载既有轨迹");
                traceRecorder.step(TraceStepType.ASYNC, "异步生成", "在线程池中生成洞察", 12L, null);
                traceRecorder.step(TraceStepType.LLM_CALL, "deepseek-flash", "prompt=2600",
                        5000L, 2695);
                traceRecorder.finish("deepseek-flash", 2000, 695, 2695);
                return true;
            });
            assertTrue(future.get(15, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        List<AiExecutionTrace> rows = traceMapper.selectList(new LambdaQueryWrapper<AiExecutionTrace>()
                .eq(AiExecutionTrace::getTraceId, traceId));
        assertEquals(1, rows.size(), "异步段必须 UPDATE 同一行，而不是另写一条孤立记录");
        AiExecutionTrace trace = rows.get(0);
        assertEquals(AiExecutionTrace.STATUS_SUCCESS, trace.getStatus());
        assertEquals(2695, trace.getTotalTokens());
        assertEquals(99003L, trace.getUserId(), "挂载时不应丢失父线程写入的触发者");
        assertEquals(2, trace.getStepCount(), "异步段追加的两步应写进同一条轨迹");
        log.info("【M18】跨线程续写成功：traceId={}，steps={}", traceId, trace.getSteps());
    }

    @Test
    void shouldReturnNullWhenAttachingUnknownTrace() {
        assertNull(traceRecorder.attach("不存在的-trace-id"));
        assertNull(traceRecorder.attach(null), "traceId 为空时应安全返回 null");
        assertNull(TraceContext.current(), "挂载失败不应污染当前线程上下文");
    }

    /** 用于验证工具包装：一个不依赖数据库的假工具。 */
    static class ProbeTools {

        @Tool(description = "按关键词搜索站内文章")
        public String searchArticles(@ToolParam(description = "搜索关键词") String keyword) {
            return "[\"Redis 缓存穿透实践\"]";
        }
    }

    @Test
    void shouldRecordToolCallsThroughWrappedCallback() {
        TraceSession session = traceRecorder.start(AiExecutionTrace.SCENE_CHAT, "QA", 99004L,
                null, null, null);
        createdTraceIds.add(session.traceId());

        ToolCallback[] callbacks = traceRecorder.wrapTools(new ProbeTools());
        assertEquals(1, callbacks.length);
        assertEquals("searchArticles", callbacks[0].getToolDefinition().name());

        String result = callbacks[0].call("{\"keyword\":\"Redis\"}");
        assertTrue(result.contains("Redis 缓存穿透实践"), "包装不应改变工具的返回值");

        traceRecorder.finish(null, null, null, null);

        AiExecutionTrace trace = find(session.traceId());
        assertTrue(trace.getSteps().contains("\"TOOL_CALL\""), "工具调用应作为一步记录：" + trace.getSteps());
        assertTrue(trace.getSteps().contains("Redis"), "工具入参与返回值应进入步骤明细：" + trace.getSteps());
        log.info("【M18】工具包装采集成功：steps={}", trace.getSteps());
    }

    @Test
    void shouldNotBreakWhenNoTraceIsActive() {
        // 没有轨迹时所有埋点都必须静默：M13-M17 的既有代码路径几乎都不带轨迹
        traceRecorder.step(TraceStepType.LLM_CALL, "deepseek-chat", "无轨迹上下文");
        traceRecorder.degrade(TraceDegradeReason.LLM_ERROR, "无轨迹上下文");
        traceRecorder.finish(null, null, null, null);
        assertNull(TraceContext.current());
    }

    private AiExecutionTrace find(String traceId) {
        AiExecutionTrace trace = traceMapper.selectOne(new LambdaQueryWrapper<AiExecutionTrace>()
                .eq(AiExecutionTrace::getTraceId, traceId)
                .last("LIMIT 1"));
        assertNotNull(trace, "轨迹应已落库：" + traceId);
        return trace;
    }
}

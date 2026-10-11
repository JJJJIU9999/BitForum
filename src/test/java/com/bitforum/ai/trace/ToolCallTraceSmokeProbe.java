package com.bitforum.ai.trace;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * M18 前置验证探针（T12-B）：**真实 DeepSeek 下装饰器能否捕获工具调用链**。
 *
 * <p>{@link ToolCallTraceProbe} 已用 stub 证明了两件事：
 * 工具循环不在 {@code ChatClient} 层、传入的 {@code ToolCallback} 实例会被原样放进 options。
 * 但"provider 执行工具时确实调用我们包装的那一层"，只能用**真实模型**复验 ——
 * 因为循环实现属于 {@code DeepSeekChatModel}，stub 复刻不了。
 *
 * <p>探针做一件事：把两个真实工具用装饰器包起来，问一个**必须调工具才能回答**的问题，
 * 然后断言装饰器依次拿到了 工具名 / 模型给的参数 JSON / 真实返回值 / 单步耗时。
 * 这一组数据正是 M18 轨迹表 {@code ai_execution_trace} 里"工具调用链"要落库的字段。
 *
 * <p>默认不执行（消耗真实 API 额度）：
 *
 * <pre>
 *   export DEEPSEEK_CHAT_ENABLED=true
 *   export DEEPSEEK_API_KEY="$(grep '^DEEPSEEK_API_KEY=' .env | cut -d= -f2-)"
 *   ./mvnw -s maven-settings.xml -Dtest=ToolCallTraceSmokeProbe test
 * </pre>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_CHAT_ENABLED", matches = "true")
class ToolCallTraceSmokeProbe {

    private static final Logger log = LoggerFactory.getLogger(ToolCallTraceSmokeProbe.class);

    /** 一次被捕获的工具调用。 */
    private record CapturedCall(String name, String arguments, String result, long latencyMillis) {
    }

    /** 只用于验证的假工具：不依赖数据库，返回值固定，便于断言。 */
    static class ProbeTools {

        @Tool(description = "查询站内服务器当前时间，返回 ISO-8601 格式的字符串")
        public String currentServerTime() {
            return "2026-09-19T21:30:00+08:00";
        }

        @Tool(description = "按关键词搜索站内文章，返回匹配到的文章标题列表")
        public String searchArticles(@ToolParam(description = "搜索关键词") String keyword) {
            return "[\"Redis 缓存穿透与布隆过滤器\", \"Redis 分布式锁的正确实现\"]";
        }
    }

    /** M18 计划采用的装饰器形态：包一层，记录入参、结果与耗时。 */
    private static final class TracingToolCallback implements ToolCallback {

        private final ToolCallback delegate;
        private final List<CapturedCall> captured;

        private TracingToolCallback(ToolCallback delegate, List<CapturedCall> captured) {
            this.delegate = delegate;
            this.captured = captured;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public String call(String toolInput) {
            return call(toolInput, null);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            long startedAt = System.currentTimeMillis();
            try {
                String result = delegate.call(toolInput, toolContext);
                captured.add(new CapturedCall(delegate.getToolDefinition().name(), toolInput, result,
                        System.currentTimeMillis() - startedAt));
                return result;
            } catch (RuntimeException exception) {
                captured.add(new CapturedCall(delegate.getToolDefinition().name(), toolInput,
                        "ERROR:" + exception.getMessage(), System.currentTimeMillis() - startedAt));
                throw exception;
            }
        }
    }

    @Autowired
    private ChatClient chatClient;

    @Test
    void shouldCaptureRealToolCallsWithLatency() {
        List<CapturedCall> captured = new ArrayList<>();

        ToolCallback[] raw = ToolCallbacks.from(new ProbeTools());
        ToolCallback[] traced = new ToolCallback[raw.length];
        for (int i = 0; i < raw.length; i++) {
            traced[i] = new TracingToolCallback(raw[i], captured);
        }

        long startedAt = System.currentTimeMillis();
        ChatResponse response = chatClient.prompt()
                .user("请调用工具完成两件事：1) 查询服务器当前时间；2) 搜索关键词 Redis 的文章。"
                        + "然后一句话总结，不要编造工具没有返回的内容。")
                .toolCallbacks(traced)
                .call()
                .chatResponse();
        long totalLatency = System.currentTimeMillis() - startedAt;

        assertNotNull(response, "真实调用应返回响应");

        String content = response.getResult() == null || response.getResult().getOutput() == null
                ? "" : response.getResult().getOutput().getText();
        var usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();

        log.info("【T12-B】总耗时 {} ms；工具调用 {} 次", totalLatency, captured.size());
        for (CapturedCall call : captured) {
            log.info("【T12-B】工具={} 入参={} 结果={} 单步耗时={}ms",
                    call.name(), call.arguments(), call.result(), call.latencyMillis());
        }
        log.info("【T12-B】最终回答 = {}", content);
        log.info("【T12-B】token: prompt={} completion={} total={}",
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens(),
                usage == null ? null : usage.getTotalTokens());
        log.info("【T12-B】最终响应 hasToolCalls() = {}", response.hasToolCalls());

        // 核心断言：真实 provider 确实执行了我们包装的那一层
        assertFalse(captured.isEmpty(), "真实调用下装饰器应捕获到至少一次工具调用");
        assertTrue(captured.stream().anyMatch(call -> "currentServerTime".equals(call.name())
                        || "searchArticles".equals(call.name())),
                "捕获到的工具名应来自模型实际选择的工具");
        assertTrue(captured.stream().allMatch(call -> call.latencyMillis() >= 0),
                "每次工具调用都应能记录单步耗时");
        assertTrue(captured.stream().allMatch(call -> call.result() != null && !call.result().isBlank()),
                "每次工具调用都应能记录返回值");
        assertFalse(response.hasToolCalls(),
                "工具执行完成后，最终响应是最终回答（工具链只存在于中间轮次，必须靠装饰器采集）");
    }
}

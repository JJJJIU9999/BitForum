package com.bitforum.ai.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * M18 前置验证探针（T12-A）：**工具调用链的埋点位置在哪里**。
 *
 * <p><b>为什么先验这一件事</b>：M18 验收第 1 条要求轨迹里必须有「工具调用链 + 每步耗时」，
 * 而 M13-M17 从未采集过这两项。落点选错的代价很大：
 * 若把埋点写进 {@code QaAgent}（解析最终 {@code ChatResponse}），
 * 而工具循环其实是 provider 内部完成的，那么轨迹里将**永远只有最终答案、没有工具链**，
 * 且不会有任何报错 —— 属于"看起来做了、实际是空的"功能。
 *
 * <p>本探针用本地 stub 模型（不调真实 API、结果确定）回答：
 *
 * <ol>
 *   <li>{@code ChatClient} 是否会替我们执行工具循环？（即：循环发生在哪一层）</li>
 *   <li>业务代码传入的 {@code ToolCallback} 实例是否原样进入 {@code Prompt} 的 options？
 *       （只有原样进入，装饰器方案才可能生效）</li>
 * </ol>
 *
 * <p>T12-B（{@link ToolCallTraceSmokeProbe}）再用真实 DeepSeek 复验装饰器被真正调用。
 * 结论见 findings.md 6.18。
 */
class ToolCallTraceProbe {

    private static final Logger log = LoggerFactory.getLogger(ToolCallTraceProbe.class);

    private static final class StubChatModel implements ChatModel {

        private final AtomicInteger callCount = new AtomicInteger();
        private final List<Prompt> receivedPrompts = new ArrayList<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            receivedPrompts.add(prompt);
            callCount.incrementAndGet();
            AssistantMessage message = AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                            "call-1", "function", "searchArticles", "{\"keyword\":\"Redis\"}")))
                    .build();
            return new ChatResponse(List.of(new Generation(message)));
        }
    }

    private static ToolCallback fakeTool(String name) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(name)
                .description("测试用假工具：" + name)
                .inputSchema("""
                        {"type":"object","properties":{"keyword":{"type":"string"}},"required":[]}""")
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return "【" + name + " 的返回值】toolInput=" + toolInput;
            }
        };
    }

    @Test
    void chatClientShouldNotExecuteToolLoopItself() {
        StubChatModel model = new StubChatModel();
        ChatClient chatClient = ChatClient.builder(model).build();

        ToolCallback search = fakeTool("searchArticles");
        ToolCallback hot = fakeTool("getHotArticles");

        ChatResponse response = chatClient.prompt()
                .user("站内有哪些 Redis 文章？")
                .toolCallbacks(search, hot)
                .call()
                .chatResponse();

        assertNotNull(response);

        // 结论 1：ChatClient 只调用了模型 1 次 —— 它**不**执行工具循环。
        // 模型返回"我要调工具"之后，循环该由谁跑，属于 provider 实现的责任。
        log.info("【T12-A-1】stub 模型被调用次数 = {}（ChatClient 自身不做工具循环）",
                model.callCount.get());
        assertEquals(1, model.callCount.get(),
                "ChatClient 自身不执行工具循环：循环在 ChatModel 的 provider 实现里（DeepSeekChatModel.call）");

        // 结论 2：返回给业务代码的响应里**仍然带着**工具调用请求（因为循环没跑）
        assertTrue(response.hasToolCalls(),
                "stub 未执行工具时，业务代码拿到的是「模型请求工具」那一轮，而不是最终回答");

        // 结论 3：传入的 ToolCallback 实例原样进入 Prompt 的 options ——
        // 这是装饰器方案可行的前提：provider 执行工具时用的就是我们包装过的实例
        Prompt prompt = model.receivedPrompts.get(0);
        assertTrue(prompt.getOptions() instanceof ToolCallingChatOptions,
                "带工具调用时应使用 ToolCallingChatOptions");
        List<ToolCallback> optionsCallbacks = ((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks();
        assertEquals(2, optionsCallbacks.size(), "两个工具回调都应进入 options");
        assertSame(search, optionsCallbacks.get(0), "传入的回调实例应被原样保留（未被重新构造）");
        assertSame(hot, optionsCallbacks.get(1), "传入的回调实例应被原样保留（未被重新构造）");
        log.info("【T12-A-2】options 中的回调实例与传入实例同一引用 = {}",
                optionsCallbacks.get(0) == search && optionsCallbacks.get(1) == hot);
    }
}

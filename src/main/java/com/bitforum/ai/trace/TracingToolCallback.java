package com.bitforum.ai.trace;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 带轨迹采集的工具回调装饰器（M18）。
 *
 * <p><b>为什么必须装饰而不是解析响应</b>：T12 实测（findings.md 6.18）证明
 * Spring AI 的工具执行循环发生在 {@code DeepSeekChatModel} 内部，
 * 业务代码拿到的最终 {@code ChatResponse.hasToolCalls()} 恒为 {@code false} ——
 * 工具调用链在业务可见的响应里**已经消失**。唯一能拿到"工具名 / 入参 / 返回值 /
 * 单步耗时"的位置就是工具被真正调用的那一层，也就是这里。
 *
 * <p>另一个实测结论是：作为 {@code ToolCallback} 传入的实例会被原样放进
 * {@code Prompt} 的 options，provider 执行工具时用的就是它 —— 所以这层包装是生效的
 * （T12-B 用真实 DeepSeek 复验：两次工具调用都被捕获，单步耗时 3ms / 1ms）。
 *
 * <p>装饰器不改变任何行为：入参、返回值、异常都原样透传；
 * 记录轨迹只是一次内存追加（落库发生在整条轨迹收尾时），因此对工具本身的耗时影响可忽略。
 */
final class TracingToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final TraceRecorder recorder;

    TracingToolCallback(ToolCallback delegate, TraceRecorder recorder) {
        this.delegate = delegate;
        this.recorder = recorder;
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
        String name = delegate.getToolDefinition() == null ? "unknown" : delegate.getToolDefinition().name();
        long startedAt = System.currentTimeMillis();
        try {
            String result = delegate.call(toolInput, toolContext);
            recorder.stepSince(TraceStepType.TOOL_CALL, name, describe(toolInput, result), startedAt, null);
            return result;
        } catch (RuntimeException exception) {
            recorder.stepSince(TraceStepType.TOOL_CALL, name,
                    describe(toolInput, "执行失败：" + exception.getMessage()), startedAt, null);
            throw exception;
        }
    }

    /** 把一次工具调用压成一行人类可读的摘要，过长部分由 TraceRecorder 统一截断。 */
    private String describe(String toolInput, String result) {
        return "入参=" + (toolInput == null || toolInput.isBlank() ? "{}" : toolInput)
                + " → 返回=" + result;
    }
}

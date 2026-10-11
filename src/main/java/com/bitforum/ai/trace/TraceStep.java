package com.bitforum.ai.trace;

/**
 * 轨迹中的一步（M18）。
 *
 * <p>以 JSON 数组的形式整体存进 {@code ai_execution_trace.steps}，字段名即前端渲染契约。
 *
 * @param seq         序号，从 1 开始，代表执行顺序
 * @param type        步骤类型，取值见 {@link TraceStepType}
 * @param name        步骤名称（工具名 / 模型名 / 阶段名）
 * @param detail      人类可读的细节（入参、命中数、降级说明等）；落库前会截断
 * @param latencyMs   该步耗时（毫秒）
 * @param totalTokens 该步消耗的 token（只有模型调用步有值）
 */
public record TraceStep(int seq,
                        String type,
                        String name,
                        String detail,
                        long latencyMs,
                        Integer totalTokens) {
}

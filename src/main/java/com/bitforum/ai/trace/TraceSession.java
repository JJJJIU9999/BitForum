package com.bitforum.ai.trace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一次 AI 调用的轨迹会话（M18）。
 *
 * <p>承载"这次调用是谁触发的、分派给谁、已经走了哪些步"，在同步链路里随 ThreadLocal 传递
 * （见 {@link TraceContext}），在跨线程/跨进程处由
 * {@link TraceRecorder#attach(String)} 依据 traceId 重新挂载。
 *
 * <p><b>为什么是可变的</b>：轨迹是**边执行边累积**的 —— 模型调用的耗时只有调用完成后才知道，
 * 工具链的每一步也是逐个追加。因此这里不做成 record，而是"身份不可变 + 步骤可变"。
 *
 * <p>线程安全：步骤列表用同步包装。同一个 trace 正常情况下只在一条线程里累积
 * （异步段是"接棒"而不是并发），但防御性地保证不会因为并发写坏列表。
 */
public class TraceSession {

    private final String traceId;
    private final String scene;
    private final String agentType;
    private final Long userId;
    private final Long conversationId;
    private final String refType;
    private final Long refId;
    /** 该 trace 是否已经在库里有行（attach 续写场景为 true，start 新建场景为 false） */
    private final boolean persisted;
    private final long startedAt;
    private final List<TraceStep> steps = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger sequence = new AtomicInteger();

    /**
     * 本次调用是否已经降级、以及原因。
     *
     * <p>放在会话上而不是让每个 Agent 返回：降级可能发生在链路的任意一层
     * （检索失败、模型不可用、返回空内容），而"最终状态"只有一个。
     * 由各层标记、收尾时统一读取，避免每个 Agent 各自拼一个状态字段。
     */
    private volatile String degradeReason;
    private volatile String degradeMessage;

    /**
     * @param startedAt 轨迹的起点时刻。新建时为当前时间；异步续写时来自库里那一行的
     *                  {@code create_time}，这样"端到端耗时"覆盖"触发 → 异步段结束"，
     *                  而不是只算异步段本身。
     */
    TraceSession(String traceId, String scene, String agentType, Long userId, Long conversationId,
                 String refType, Long refId, boolean persisted, List<TraceStep> initialSteps,
                 long startedAt) {
        this.traceId = traceId;
        this.scene = scene;
        this.agentType = agentType;
        this.userId = userId;
        this.conversationId = conversationId;
        this.refType = refType;
        this.refId = refId;
        this.persisted = persisted;
        this.startedAt = startedAt;
        if (initialSteps != null) {
            steps.addAll(initialSteps);
            sequence.set(initialSteps.size());
        }
    }

    /** 追加一步，序号自增；detail 由调用方保证已截断。 */
    void addStep(String type, String name, String detail, long latencyMs, Integer totalTokens) {
        steps.add(new TraceStep(sequence.incrementAndGet(), type, name, detail, latencyMs, totalTokens));
    }

    /** 当前步骤快照（用于落库或前端展示）。 */
    public List<TraceStep> steps() {
        synchronized (steps) {
            return List.copyOf(steps);
        }
    }

    public int stepCount() {
        return steps.size();
    }

    /** 从会话开始到现在的耗时（毫秒）。 */
    public long elapsedMillis() {
        return System.currentTimeMillis() - startedAt;
    }

    public String traceId() {
        return traceId;
    }

    public String scene() {
        return scene;
    }

    public String agentType() {
        return agentType;
    }

    public Long userId() {
        return userId;
    }

    public Long conversationId() {
        return conversationId;
    }

    public String refType() {
        return refType;
    }

    public Long refId() {
        return refId;
    }

    /** 标记本次调用走了降级链路；首次标记生效，后续不覆盖（保留最初的失败原因）。 */
    void markDegraded(String reason, String message) {
        if (this.degradeReason == null) {
            this.degradeReason = reason;
            this.degradeMessage = message;
        }
    }

    public String degradeReason() {
        return degradeReason;
    }

    public String degradeMessage() {
        return degradeMessage;
    }

    /** 是否已发生降级。 */
    public boolean degraded() {
        return degradeReason != null;
    }

    boolean persisted() {
        return persisted;
    }
}

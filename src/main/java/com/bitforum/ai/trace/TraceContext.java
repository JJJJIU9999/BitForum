package com.bitforum.ai.trace;

/**
 * 轨迹上下文的线程本地持有者（M18）。
 *
 * <p>T13 实测结论（findings.md 6.19）：ThreadLocal **不包装就传不进线程池**，
 * 因此这里的定位很明确 —— 它只负责"同一条线程内，各层代码能拿到当前 trace"，
 * 不负责跨线程传播。跨线程由两处显式处理：
 *
 * <ul>
 *   <li>提交任务时读取 {@link #currentTraceId()}，在目标线程里
 *       {@link TraceRecorder#attach(String)} 续写（洞察线程池）；</li>
 *   <li>发送消息时把 {@link #currentTraceId()} 写进 MQ 消息头，
 *       消费者读出来后 attach（审核链路）。</li>
 * </ul>
 *
 * <p><b>为什么暴露 currentTraceId 给业务代码</b>：MQ 消息头注入点在业务层
 * （{@code RabbitTemplate.convertAndSend} 处），那里不适合注入整个 TraceRecorder；
 * 一个静态只读方法足够，且在没有轨迹时返回 null，调用方无需判空分支。
 */
public final class TraceContext {

    private static final ThreadLocal<TraceSession> CURRENT = new ThreadLocal<>();

    private TraceContext() {
    }

    /** 当前线程正在记录的轨迹会话；没有则返回 null。 */
    public static TraceSession current() {
        return CURRENT.get();
    }

    /** 当前线程的 traceId；没有则返回 null（MQ 头注入处依赖这个语义）。 */
    public static String currentTraceId() {
        TraceSession session = CURRENT.get();
        return session == null ? null : session.traceId();
    }

    /** 由 TraceRecorder 在开始/挂载轨迹时调用。 */
    static void set(TraceSession session) {
        CURRENT.set(session);
    }

    /** 清理，避免线程复用时的上下文串味（T13 实测过必须清理）。 */
    public static void clear() {
        CURRENT.remove();
    }
}

package com.bitforum.ai.trace;

import org.springframework.amqp.core.MessagePostProcessor;

/**
 * 轨迹 id 在 MQ 消息头上的约定（M18）。
 *
 * <p><b>为什么用消息头而不是消息体</b>：T13 实测（findings.md 6.19）确认，
 * 通过 {@code MessagePostProcessor} 写入 header 后，消费者能从
 * {@code Message.getMessageProperties().getHeaders()} 原样读回，且**不影响消息体反序列化**。
 * 这意味着：
 *
 * <ul>
 *   <li>不需要给 {@code KbIndexMessage} / {@code ModerationMessage} 加字段，
 *       也就不需要改 M15/M16 已定稿的消息契约；</li>
 *   <li>队列里可能残留的旧消息（没有 header）依然能被消费 ——
 *       {@link #readTraceId} 读不到就返回 null，调用方新建一条轨迹即可；</li>
 *   <li>两个既有消费者都已经有 {@code Message rawMessage} 参数，零签名改动。</li>
 * </ul>
 */
public final class TraceHeaders {

    /** 消息头名称。带 {@code x-} 前缀，与 HTTP 自定义头习惯一致。 */
    public static final String TRACE_ID = "x-trace-id";

    private TraceHeaders() {
    }

    /**
     * 生成一个"把当前线程的 traceId 挂到消息上"的处理器。
     *
     * <p>当前线程没有轨迹时（绝大多数业务请求都不是 AI 调用），返回的处理器什么都不做，
     * 因此可以无条件地挂在所有 {@code convertAndSend} 上，调用方不需要判空分支。
     */
    public static MessagePostProcessor propagate() {
        String traceId = TraceContext.currentTraceId();
        return message -> {
            if (traceId != null) {
                message.getMessageProperties().setHeader(TRACE_ID, traceId);
            }
            return message;
        };
    }

    /** 从消息头读出 traceId；没有则返回 null。 */
    public static String readTraceId(org.springframework.amqp.core.Message message) {
        if (message == null || message.getMessageProperties() == null) {
            return null;
        }
        Object value = message.getMessageProperties().getHeaders().get(TRACE_ID);
        return value == null ? null : String.valueOf(value);
    }
}

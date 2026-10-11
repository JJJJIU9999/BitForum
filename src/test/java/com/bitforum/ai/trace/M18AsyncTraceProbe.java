package com.bitforum.ai.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import com.bitforum.config.RabbitMQConfig;
import com.bitforum.message.KbIndexMessage;

/**
 * M18 前置验证探针（T13）：**执行轨迹能否覆盖异步链路**。
 *
 * <p><b>为什么必须验</b>：M18 的验收第 1 条要求"任意一次 AI 调用都能查到完整执行轨迹"。
 * 但 AI 调用并不只发生在 HTTP 请求线程里：
 *
 * <ul>
 *   <li><b>运营洞察</b>走单线程池（{@code insightExecutor}）：管理员点一下返回，
 *       模型调用发生在几秒后的另一个线程；</li>
 *   <li><b>知识库索引</b>与<b>内容审核</b>走 RabbitMQ：消息由业务事务提交后投递，
 *       消费发生在 broker 推来的线程里。</li>
 * </ul>
 *
 * 如果 traceId 只存在请求线程的 ThreadLocal 里，上面两条链路的轨迹会**各自断开**，
 * 变成"一堆没有前因后果的孤立记录" —— 这正是 handoff 点名的最大风险之一。
 *
 * <p>探针验证两个具体的传播机制：
 *
 * <ol>
 *   <li><b>MQ 用消息头透传 traceId</b>：发送时通过 {@code MessagePostProcessor} 挂 header，
 *       消费者从 {@code Message.getMessageProperties()} 读回。用项目自己的
 *       {@code article.exchange} + 探针专用队列，消费的是真实的消息体类型
 *       {@link KbIndexMessage}，因此顺带验证"加 header 不影响消息体反序列化"。</li>
 *   <li><b>线程池用包装 Runnable 透传</b>：验证 ThreadLocal 在**不包装**时必然丢失
 *       （反证：这就是为什么必须显式处理），包装捕获/恢复后子线程可见，
 *       且父线程上下文不被子线程污染。</li>
 * </ol>
 *
 * <p>探针使用真实 RabbitMQ（与既有 MQ 测试一致，环境由 {@code ./dev.sh} 提供）。
 */
@SpringBootTest
class M18AsyncTraceProbe {

    private static final Logger log = LoggerFactory.getLogger(M18AsyncTraceProbe.class);

    /** 探针专用队列：非持久 + 自动删除，跑完不留垃圾。 */
    static final String PROBE_QUEUE = "m18.trace.probe.queue";
    static final String PROBE_ROUTING_KEY = "m18.trace.probe";
    /** 轨迹 id 的 header 名（M18 计划使用）。 */
    static final String TRACE_HEADER = "x-trace-id";

    /** 探针专用 ThreadLocal，形态与 M18 计划实现的 {@code TraceContext} 一致。 */
    private static final ThreadLocal<String> TRACE_CONTEXT = new ThreadLocal<>();

    static class ProbeListener {

        private final CountDownLatch latch = new CountDownLatch(1);
        private final AtomicReference<String> traceId = new AtomicReference<>();
        private final AtomicReference<Long> articleId = new AtomicReference<>();

        @RabbitListener(queues = PROBE_QUEUE)
        public void handle(KbIndexMessage message, Message rawMessage) {
            traceId.set((String) rawMessage.getMessageProperties().getHeaders().get(TRACE_HEADER));
            articleId.set(message == null ? null : message.getArticleId());
            latch.countDown();
        }
    }

    @TestConfiguration
    static class ProbeConfig {

        @Bean
        Queue m18TraceProbeQueue() {
            return QueueBuilder.nonDurable(PROBE_QUEUE).autoDelete().build();
        }

        @Bean
        Binding m18TraceProbeBinding(Queue m18TraceProbeQueue, DirectExchange articleExchange) {
            return BindingBuilder.bind(m18TraceProbeQueue).to(articleExchange).with(PROBE_ROUTING_KEY);
        }

        @Bean
        ProbeListener m18TraceProbeListener() {
            return new ProbeListener();
        }
    }

    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private ProbeListener probeListener;

    @Test
    void shouldPropagateTraceIdThroughRabbitMqHeader() throws Exception {
        String traceId = "trace-m18-probe-0001";

        KbIndexMessage message = new KbIndexMessage();
        message.setArticleId(999999L);
        message.setMessageId("m18-probe-msg-1");

        MessagePostProcessor attachTrace = amqpMessage -> {
            amqpMessage.getMessageProperties().setHeader(TRACE_HEADER, traceId);
            return amqpMessage;
        };

        rabbitTemplate.convertAndSend(RabbitMQConfig.ARTICLE_EXCHANGE, PROBE_ROUTING_KEY, message, attachTrace);

        boolean received = probeListener.latch.await(10, TimeUnit.SECONDS);
        assertTrue(received, "探针队列应能收到消息（真实 RabbitMQ）");
        assertEquals(traceId, probeListener.traceId.get(),
                "消费者应能从消息头读回发送端写入的 traceId");
        assertEquals(999999L, probeListener.articleId.get(),
                "加了自定义 header 后，消息体仍应正常反序列化为 KbIndexMessage");

        log.info("【T13-1】MQ header 透传成功：traceId={}，消息体 articleId={}",
                probeListener.traceId.get(), probeListener.articleId.get());
    }

    @Test
    void shouldPropagateTraceIdIntoThreadPoolOnlyWhenWrapped() throws Exception {
        String traceId = "trace-m18-probe-0002";
        TRACE_CONTEXT.set(traceId);

        ExecutorService pool = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "m18-probe-worker");
            thread.setDaemon(true);
            return thread;
        });
        try {
            // 反证：不包装直接提交 —— 子线程读不到请求线程的 ThreadLocal
            AtomicReference<String> withoutWrapper = new AtomicReference<>("NOT_RUN");
            pool.submit(() -> withoutWrapper.set(TRACE_CONTEXT.get())).get(5, TimeUnit.SECONDS);
            assertNull(withoutWrapper.get(),
                    "未包装的 Runnable 在子线程中读不到 ThreadLocal（这就是必须显式传播的原因）");

            // 正解：提交时捕获、执行前恢复、执行后清理
            AtomicReference<String> withWrapper = new AtomicReference<>();
            Runnable wrapped = captureAndWrap(() -> withWrapper.set(TRACE_CONTEXT.get()));
            pool.submit(wrapped).get(5, TimeUnit.SECONDS);
            assertEquals(traceId, withWrapper.get(),
                    "包装后的 Runnable 应把 traceId 带进线程池线程");

            // 子线程执行结束后必须清理，避免线程复用时的上下文串味
            AtomicReference<String> afterTask = new AtomicReference<>("NOT_RUN");
            pool.submit(() -> afterTask.set(TRACE_CONTEXT.get())).get(5, TimeUnit.SECONDS);
            assertNull(afterTask.get(),
                    "任务执行后应清理子线程的 ThreadLocal，避免下一个任务读到上一个请求的 traceId");

            log.info("【T13-2】线程池传播验证通过：不包装={}，包装后={}，任务结束后={}",
                    withoutWrapper.get(), withWrapper.get(), afterTask.get());
        } finally {
            pool.shutdownNow();
            TRACE_CONTEXT.remove();
        }
    }

    /** M18 计划采用的传播形态：捕获提交线程的上下文，在目标线程恢复，执行后清理。 */
    private Runnable captureAndWrap(Runnable task) {
        String captured = TRACE_CONTEXT.get();
        return () -> {
            TRACE_CONTEXT.set(captured);
            try {
                task.run();
            } finally {
                TRACE_CONTEXT.remove();
            }
        };
    }
}

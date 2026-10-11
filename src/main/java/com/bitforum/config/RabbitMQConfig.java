package com.bitforum.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

//RabbitMQ 就是个消息中转站，队列是通道，监听器是办事员。核心价值是把"发送方"和"处理方"完全分开。
@Configuration
public class RabbitMQConfig {
    // 队列、交换机和路由键统一放在这里，避免配置、生产者、消费者和测试各写一份字符串。
    public static final String ARTICLE_PUBLISH_QUEUE = "article.publish.queue";
    public static final String ARTICLE_EXCHANGE = "article.exchange";
    public static final String ARTICLE_PUBLISH_ROUTING_KEY = "article.publish";

    public static final String ARTICLE_DLX_EXCHANGE = "article.dlx.exchange";
    public static final String ARTICLE_PUBLISH_DLQ_ROUTING_KEY = "article.publish.dlq";
    public static final String ARTICLE_PUBLISH_DLQ = "article.publish.dlq";

    // M15：知识库索引队列。复用同一个 article.exchange，但用独立队列与独立死信队列，
    // 与既有的文章发布通知互不影响（findings.md 待验证事项 T8）。
    public static final String KB_INDEX_QUEUE = "article.kb.index.queue";
    public static final String KB_INDEX_ROUTING_KEY = "article.kb.index";
    public static final String KB_INDEX_DLQ_ROUTING_KEY = "article.kb.index.dlq";
    public static final String KB_INDEX_DLQ = "article.kb.index.dlq";

    // M16：内容审核队列。同样复用 article.exchange、配独立队列与独立死信队列，
    // 与文章发布通知、知识库索引两条链路互不影响。
    public static final String MODERATION_QUEUE = "article.moderation.queue";
    public static final String MODERATION_ROUTING_KEY = "article.moderation";
    public static final String MODERATION_DLQ_ROUTING_KEY = "article.moderation.dlq";
    public static final String MODERATION_DLQ = "article.moderation.dlq";

    private static final Logger log = LoggerFactory.getLogger(RabbitMQConfig.class);

    @Bean
    public Queue moderationQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", ARTICLE_DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", MODERATION_DLQ_ROUTING_KEY);
        return new Queue(MODERATION_QUEUE, true, false, false, args);
    }

    @Bean
    public Queue moderationDlq() {
        return new Queue(MODERATION_DLQ, true);
    }

    @Bean
    public Binding moderationBinding(Queue moderationQueue, DirectExchange articleExchange) {
        return BindingBuilder.bind(moderationQueue).to(articleExchange).with(MODERATION_ROUTING_KEY);
    }

    @Bean
    public Binding moderationDlqBinding(Queue moderationDlq, DirectExchange articleDlxExchange) {
        return BindingBuilder.bind(moderationDlq).to(articleDlxExchange).with(MODERATION_DLQ_ROUTING_KEY);
    }

    @Bean
    public Queue kbIndexQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", ARTICLE_DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", KB_INDEX_DLQ_ROUTING_KEY);
        return new Queue(KB_INDEX_QUEUE, true, false, false, args);
    }

    @Bean
    public Queue kbIndexDlq() {
        return new Queue(KB_INDEX_DLQ, true);
    }

    @Bean
    public Binding kbIndexBinding(Queue kbIndexQueue, DirectExchange articleExchange) {
        return BindingBuilder.bind(kbIndexQueue).to(articleExchange).with(KB_INDEX_ROUTING_KEY);
    }

    @Bean
    public Binding kbIndexDlqBinding(Queue kbIndexDlq, DirectExchange articleDlxExchange) {
        return BindingBuilder.bind(kbIndexDlq).to(articleDlxExchange).with(KB_INDEX_DLQ_ROUTING_KEY);
    }

    @Bean
    public Queue articlePublishQueue() {
        Map<String, Object> args = new HashMap<>();
        // 普通队列处理失败且 nack(requeue=false) 时，消息会被转发到这个死信交换机。
        args.put("x-dead-letter-exchange", ARTICLE_DLX_EXCHANGE);
        // 死信交换机再根据这个 routingKey，把失败消息路由到文章发布死信队列。
        args.put("x-dead-letter-routing-key", ARTICLE_PUBLISH_DLQ_ROUTING_KEY);
        return new Queue(ARTICLE_PUBLISH_QUEUE, true, false, false, args);
    }

    @Bean
    public DirectExchange articleExchange() {
        // DirectExchange 会按 routingKey 精准路由，适合 article.publish 这种明确业务事件。
        return new DirectExchange(ARTICLE_EXCHANGE, true, false);
    }

    // DLX：Dead Letter Exchange，专门接收普通队列处理失败后的死信消息。
    @Bean
    public DirectExchange articleDlxExchange() {
        return new DirectExchange(ARTICLE_DLX_EXCHANGE, true, false);
    }

    // DLQ：Dead Letter Queue，失败消息最终会进入这里，方便后续排查或人工补偿。
    @Bean
    public Queue articlePublishDlq() {
        return new Queue(ARTICLE_PUBLISH_DLQ, true);
    }

    @Bean
    public Binding articlePublishDlqBinding(Queue articlePublishDlq, DirectExchange articleDlxExchange) {
        // 把死信交换机和死信队列绑定起来：routingKey 匹配后，失败消息进入 DLQ。
        return BindingBuilder
                .bind(articlePublishDlq)
                .to(articleDlxExchange)
                .with(ARTICLE_PUBLISH_DLQ_ROUTING_KEY);
    }

    @Bean
    public Binding articlePublishBinding(Queue articlePublishQueue, DirectExchange articleExchange) {
        // Binding 把交换机和队列连起来：消息带着 article.publish 路由键进入交换机后，会被转发到文章发布队列。
        return BindingBuilder
                .bind(articlePublishQueue)
                .to(articleExchange)
                .with(ARTICLE_PUBLISH_ROUTING_KEY);
    }

    @Bean
    public MessageConverter messageConverter() {
        // 让 RabbitTemplate 发送对象时自动转成 JSON，让监听器消费时再从 JSON 转回 Java 对象。
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplateCustomizer rabbitTemplateCustomizer() {
        // RabbitTemplate 创建完成后再设置回调，避免 RabbitMQConfig 提前注入 RabbitTemplate 造成循环依赖。
        return rabbitTemplate -> {
            // ConfirmCallback 关心的是：消息有没有成功到达 RabbitMQ Broker。
            // 到达 Broker 只代表中间件收到了消息，不代表一定已经进入目标队列。
            rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
                if (ack) {
                    log.info("RabbitMQ 消息发送到 Broker 成功");
                } else {
                    log.error("RabbitMQ 消息发送到 Broker 失败，原因：{}", cause);
                }
            });

            // ReturnsCallback 关心的是：消息到达 Broker 后，是否能按照 routingKey 路由到队列。
            // 如果 mandatory=true 且路由失败，这里会记录失败原因，方便排查队列名或交换机配置问题。
            rabbitTemplate.setReturnsCallback(returned -> {
                log.error("RabbitMQ 消息路由失败,replyCode={}, replyText={}, exchange={}, routingKey={}",
                        returned.getReplyCode(),
                        returned.getReplyText(),
                        returned.getExchange(),
                        returned.getRoutingKey());
            });
        };
    }
}

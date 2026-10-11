package com.bitforum.ai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 域配置（M13）。
 *
 * 说明：这里刻意只创建 ChatClient，不在 M13 就创建 ChatMemory / QuestionAnswerAdvisor。
 * 原因：记忆按会话动态变化，检索 M15 才接入；
 * 当前由 AgentOrchestrator 显式加载历史并构造消息列表，行为完全可控。
 * M15 接入 RAG 时会在这里补充 QuestionAnswerAdvisor 装配。
 */
@Configuration
public class AiConfig {

    /**
     * 构建 ChatClient。
     *
     * 使用 @ConditionalOnProperty 而不是 @ConditionalOnBean：
     * @ConditionalOnBean 在用户配置类中评估时，自动配置的 DeepSeekChatModel
     * 可能尚未注册 bean 定义，会导致本 bean 被静默跳过（实测踩到过）。
     * 属性条件不依赖 bean 注册顺序，行为稳定，也与项目其它配置开关风格一致。
     *
     * DEEPSEEK_CHAT_ENABLED 未开启时本 bean 不创建，
     * 依赖它的 QaAgent 会通过 ObjectProvider 感知并走降级回答。
     */
    @Bean
    @ConditionalOnProperty(name = "spring.ai.deepseek.chat.enabled", havingValue = "true")
    public ChatClient chatClient(DeepSeekChatModel deepSeekChatModel) {
        return ChatClient.builder(deepSeekChatModel).build();
    }
}

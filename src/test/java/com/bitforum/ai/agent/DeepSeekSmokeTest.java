package com.bitforum.ai.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * DeepSeek 真实调用冒烟测试（M13）。
 *
 * 默认不执行：仅当环境变量 DEEPSEEK_CHAT_ENABLED=true 时才运行，
 * 避免常规回归消耗 API 额度，并让离线环境保持全绿。
 *
 * 该测试验证 mock 测试覆盖不到的部分：真实 API Key 生效、网络可达、模型能返回内容。
 *
 * 运行方式（用 .env 中的真实 Key）：
 * <pre>
 *   export DEEPSEEK_CHAT_ENABLED=true
 *   export DEEPSEEK_API_KEY="$(grep '^DEEPSEEK_API_KEY=' .env | cut -d= -f2)"
 *   ./mvnw -Dtest=DeepSeekSmokeTest test
 * </pre>
 *
 * 若 Key 未配置或无效，上下文启动阶段就会失败（DeepSeekChatModel 校验 api-key 非空），
 * 这本身就是有效的验证信号。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_CHAT_ENABLED", matches = "true")
class DeepSeekSmokeTest {

    @Autowired
    private ChatClient chatClient;

    @Autowired
    private DeepSeekChatModel deepSeekChatModel;

    @Test
    void shouldCallDeepSeekAndGetRealAnswer() {
        assertNotNull(deepSeekChatModel, "DeepSeekChatModel 未装配，检查 API Key 配置");
        assertNotNull(chatClient, "ChatClient 未装配，检查 AiConfig 的 @ConditionalOnProperty 条件");

        String answer = chatClient.prompt()
                .user("请只回答两个字：你好")
                .call()
                .content();

        assertNotNull(answer, "模型应返回内容");
        assertFalse(answer.isBlank(), "模型返回内容不应为空");
        assertTrue(answer.length() < 200, "简单问题的回答不应异常冗长，实际长度：" + answer.length());
    }
}

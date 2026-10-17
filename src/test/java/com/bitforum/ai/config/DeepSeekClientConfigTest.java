package com.bitforum.ai.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.net.SocketTimeoutException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatProperties;
import org.springframework.ai.model.deepseek.autoconfigure.DeepSeekConnectionProperties;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;

class DeepSeekClientConfigTest {
    private record Fixture(DeepSeekChatModel model, MockRestServiceServer server) {}

    @Test
    void dedicatedModelCoexistsWithStarterAndChatSwitch() {
        var context = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DeepSeekChatAutoConfiguration.class,
                        ToolCallingAutoConfiguration.class, SpringAiRetryAutoConfiguration.class))
                .withUserConfiguration(DeepSeekClientConfig.class, AiConfig.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues("spring.ai.model.chat=deepseek",
                        "spring.ai.deepseek.api-key=offline-placeholder",
                        "spring.ai.deepseek.base-url=http://127.0.0.1:9");
        context.withPropertyValues("spring.ai.deepseek.chat.enabled=true").run(c -> {
            assertNull(c.getStartupFailure());
            assertEquals(1, c.getBeansOfType(DeepSeekChatModel.class).size());
            assertEquals(1, c.getBeansOfType(org.springframework.ai.chat.client.ChatClient.class).size());
        });
        context.withPropertyValues("spring.ai.deepseek.chat.enabled=false", "spring.ai.model.chat=none").run(c -> {
            assertNull(c.getStartupFailure());
            assertTrue(c.getBeansOfType(DeepSeekChatModel.class).isEmpty());
            assertTrue(c.getBeansOfType(org.springframework.ai.chat.client.ChatClient.class).isEmpty());
        });
    }

    private Fixture fixture() {
        var builder = DeepSeekClientConfig.nonThinkingClient(new ObjectMapper());
        var server = MockRestServiceServer.bindTo(builder).build();
        var connection = new DeepSeekConnectionProperties();
        connection.setBaseUrl("http://deepseek.invalid");
        connection.setApiKey("offline-placeholder");
        var chat = new DeepSeekChatProperties();
        chat.getOptions().setModel("deepseek-flash");
        chat.getOptions().setMaxTokens(1024);
        return new Fixture(DeepSeekClientConfig.createModel(connection, chat, builder,
                ToolCallingManager.builder().build(), new DefaultResponseErrorHandler(),
                ObservationRegistry.NOOP), server);
    }

    @Test
    void serializesNonThinkingFlashWithoutToolsAndPreservesMessages() {
        var f = fixture();
        f.server.expect(requestTo("http://deepseek.invalid/chat/completions"))
                .andExpect(jsonPath("$.model").value("deepseek-flash"))
                .andExpect(jsonPath("$.thinking.type").value("disabled"))
                .andExpect(jsonPath("$.max_tokens").value(1024))
                .andExpect(jsonPath("$.messages[0].content").value("什么是缓存穿透"))
                .andExpect(jsonPath("$.tools").doesNotExist())
                .andRespond(withSuccess("""
                    {"id":"offline","object":"chat.completion","created":1,"model":"deepseek-flash",
                     "choices":[{"index":0,"message":{"role":"assistant","content":"回答"},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":12,"completion_tokens":3,"total_tokens":15}}
                    """, MediaType.APPLICATION_JSON));
        assertEquals("回答", f.model.call(new Prompt("什么是缓存穿透")).getResult().getOutput().getText());
        f.server.verify();
    }

    @Test
    void structuredAnswerUsesOneRequestAndSeparatesMentionedFromUsedSources() throws Exception {
        var f = fixture();
        String body = new ObjectMapper().writeValueAsString(java.util.Map.of(
                "id", "offline", "model", "deepseek-flash",
                "choices", java.util.List.of(java.util.Map.of("index", 0, "finish_reason", "stop", "message",
                        java.util.Map.of("role", "assistant", "content",
                                "{\"answer\":\"站内暂无依据，片段[1]不相关\",\"usesSources\":false,\"sourceNumbers\":[]}"))),
                "usage", java.util.Map.of("prompt_tokens", 12, "completion_tokens", 3, "total_tokens", 15)));
        f.server.expect(requestTo("http://deepseek.invalid/chat/completions"))
                .andExpect(jsonPath("$.thinking.type").value("disabled"))
                .andExpect(jsonPath("$.tools").doesNotExist())
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        var result = org.springframework.ai.chat.client.ChatClient.create(f.model).prompt().user("不存在的站内资料")
                .call().responseEntity(com.bitforum.ai.agent.QaAgent.GroundedAnswer.class);
        assertFalse(result.entity().usesSources());
        assertTrue(result.entity().sourceNumbers().isEmpty());
        assertTrue(result.entity().answer().contains("[1]"));
        assertEquals(15, result.response().getMetadata().getUsage().getTotalTokens());
        f.server.verify();
    }

    @Test
    void malformedStructuredAnswerDoesNotTriggerARepairCall() {
        var f = fixture();
        f.server.expect(requestTo("http://deepseek.invalid/chat/completions"))
                .andRespond(withSuccess("""
                    {"id":"offline","model":"deepseek-flash","choices":[{"index":0,
                    "message":{"role":"assistant","content":"not valid json"},"finish_reason":"stop"}]}
                    """, MediaType.APPLICATION_JSON));
        assertThrows(RuntimeException.class, () -> org.springframework.ai.chat.client.ChatClient.create(f.model)
                .prompt().user("测试").call().responseEntity(com.bitforum.ai.agent.QaAgent.GroundedAnswer.class));
        f.server.verify();
    }

    @Test
    void invalidKeyRateLimitAndServerErrorsNeverRetry() {
        for (var status : new HttpStatus[]{HttpStatus.UNAUTHORIZED, HttpStatus.TOO_MANY_REQUESTS,
                HttpStatus.INTERNAL_SERVER_ERROR}) {
            var f = fixture();
            f.server.expect(requestTo("http://deepseek.invalid/chat/completions"))
                    .andRespond(withStatus(status));
            assertThrows(RuntimeException.class, () -> f.model.call(new Prompt("测试")));
            f.server.verify(); // 第二次请求会令 mock server 报错。
        }
    }

    @Test
    void networkFailureAndReadTimeoutNeverRetry() {
        for (var failure : new java.io.IOException[]{new java.net.ConnectException("offline"),
                new SocketTimeoutException("read timed out")}) {
            var f = fixture();
            f.server.expect(requestTo("http://deepseek.invalid/chat/completions"))
                    .andRespond(withException(failure));
            assertThrows(RuntimeException.class, () -> f.model.call(new Prompt("测试")));
            f.server.verify();
        }
    }
}

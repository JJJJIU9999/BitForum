package com.bitforum.ai.config;

import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatProperties;
import org.springframework.ai.model.deepseek.autoconfigure.DeepSeekConnectionProperties;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResponseErrorHandler;
import io.micrometer.observation.ObservationRegistry;

/** DeepSeek 专属同步客户端；不改变论坛其它 HTTP 客户端，也不升级 Spring AI。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.ai.deepseek.chat.enabled", havingValue = "true")
@EnableConfigurationProperties({DeepSeekConnectionProperties.class, DeepSeekChatProperties.class})
public class DeepSeekClientConfig {

    static RestClient.Builder nonThinkingClient(ObjectMapper mapper) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(40));
        return RestClient.builder().requestFactory(factory).requestInterceptor((request, body, execution) -> {
            // Spring AI 1.1.8 尚无 thinking 选项，仅在 DeepSeek 请求序列化后补充。
            ObjectNode json = (ObjectNode) mapper.readTree(body);
            json.putObject("thinking").put("type", "disabled");
            byte[] updated = mapper.writeValueAsBytes(json);
            request.getHeaders().setContentLength(updated.length);
            return execution.execute(request, updated);
        });
    }

    @Bean
    public DeepSeekChatModel deepSeekChatModel(DeepSeekConnectionProperties connection,
            DeepSeekChatProperties chat, ObjectMapper mapper, ToolCallingManager tools,
            ResponseErrorHandler responseErrorHandler, ObjectProvider<ObservationRegistry> observations) {
        return createModel(connection, chat, nonThinkingClient(mapper), tools, responseErrorHandler,
                observations.getIfAvailable(() -> ObservationRegistry.NOOP));
    }

    static DeepSeekChatModel createModel(DeepSeekConnectionProperties connection, DeepSeekChatProperties chat,
            RestClient.Builder client, ToolCallingManager tools, ResponseErrorHandler responseErrorHandler,
            ObservationRegistry observations) {
        var api = DeepSeekApi.builder()
                .baseUrl(StringUtils.hasText(chat.getBaseUrl()) ? chat.getBaseUrl() : connection.getBaseUrl())
                .apiKey(StringUtils.hasText(chat.getApiKey()) ? chat.getApiKey() : connection.getApiKey())
                .completionsPath(chat.getCompletionsPath()).betaPrefixPath(chat.getBetaPrefixPath())
                .restClientBuilder(client).responseErrorHandler(responseErrorHandler).build();
        return DeepSeekChatModel.builder().deepSeekApi(api).defaultOptions(chat.getOptions())
                .toolCallingManager(tools)
                // 一次尝试即结束；故障不得产生自动重复的收费请求。
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                .observationRegistry(observations).build();
    }
}

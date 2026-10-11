package com.bitforum.testsupport;

import java.util.Map;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** Runs before refresh: Hikari, Flyway, Redis and Rabbit beans cannot connect before these checks. */
public class IsolatedTestInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override public void initialize(ConfigurableApplicationContext context) {
        var env = context.getEnvironment();
        IsolatedTestTarget.requireDatabase(env.getProperty("spring.datasource.url"));
        if (!"127.0.0.1".equals(env.getProperty("spring.data.redis.host"))
                || !"26379".equals(env.getProperty("spring.data.redis.port"))
                || !"127.0.0.1".equals(env.getProperty("spring.rabbitmq.host"))
                || !"25672".equals(env.getProperty("spring.rabbitmq.port"))) {
            throw new IllegalStateException("Dedicated isolated Redis/Rabbit targets required");
        }
        // Deterministic fault-injection tests may enable chat, but only against dead loopback port 9.
        if (!"http://127.0.0.1:9".equals(env.getProperty("spring.ai.deepseek.base-url"))
                || !env.getProperty("spring.ai.deepseek.api-key", "").contains("placeholder")) {
            throw new IllegalStateException("External model endpoint or credential forbidden in tests");
        }
        if (!env.getProperty("spring.ai.vectorstore.redis.index-name", "").startsWith("bitforum-finalization-")) {
            throw new IllegalStateException("Dedicated test vector index required");
        }
        env.getPropertySources().addFirst(new MapPropertySource("isolated-scheduler", Map.of(
                "bitforum.article-metric-sync.initial-delay", "86400000")));
    }
}

package com.bitforum.ai.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.ai.vectorstore.redis.RedisVectorStore.MetadataField;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;

/**
 * RAG 向量库配置（M15）。
 *
 * <p><b>为什么不用 {@code spring-ai-starter-vector-store-redis} 的自动配置、而是在这里自己声明 bean：</b>
 *
 * <ol>
 *   <li>自动配置的 {@code vectorStore} 方法要求容器里存在 {@code JedisConnectionFactory}，
 *       而本项目使用的是 {@code spring-boot-starter-data-redis} 默认的 Lettuce；
 *       Spring Boot 不会同时创建 Jedis 连接工厂，因此自动配置在本项目里拿不到依赖。</li>
 *   <li>自动配置**不支持声明元数据字段类型**，而 RediSearch 要求所有用于过滤的元数据字段
 *       必须在建索引时显式声明类型（TAG / TEXT / NUMERIC），否则过滤检索会失败
 *       （见 findings.md 3.2 的原文约束）。</li>
 * </ol>
 *
 * <p>自动配置的 bean 带 {@code @ConditionalOnMissingBean}，因此本类定义 {@code RedisVectorStore}
 * 之后它会自动让路，不会产生重复 bean。
 *
 * <p><b>维度约束</b>：索引维度由 {@link EmbeddingModel#dimensions()} 决定，当前嵌入模型为
 * bge-base-zh-v1.5，维度 **768**。更换嵌入模型后必须删除旧索引重建
 * （RediSearch 的索引一旦建立，DIM 不可变更），否则写入会失败。见 findings.md 6.8.5。
 */
@Configuration
public class VectorStoreConfig {

    /**
     * Redis 连接池。
     *
     * <p>直接复用 Spring Boot 的 {@code spring.data.redis.*} 配置（host / port / password / database），
     * 与项目既有 Redis 配置保持单一来源，不额外引入一套连接参数。
     */
    @Bean(destroyMethod = "close")
    public JedisPooled jedisPooled(RedisProperties redisProperties) {
        DefaultJedisClientConfig.Builder clientConfig =
                DefaultJedisClientConfig.builder().database(redisProperties.getDatabase());

        if (StringUtils.hasText(redisProperties.getUsername())) {
            clientConfig.user(redisProperties.getUsername());
        }
        if (StringUtils.hasText(redisProperties.getPassword())) {
            clientConfig.password(redisProperties.getPassword());
        }
        if (redisProperties.getTimeout() != null) {
            int timeoutMillis = (int) redisProperties.getTimeout().toMillis();
            clientConfig.connectionTimeoutMillis(timeoutMillis).socketTimeoutMillis(timeoutMillis);
        }

        return new JedisPooled(
                new HostAndPort(redisProperties.getHost(), redisProperties.getPort()), clientConfig.build());
    }

    /**
     * RediSearch 向量库。
     *
     * <p>元数据字段的声明不是可选项：{@code status} 要用于「只检索已发布文章」的过滤，
     * {@code articleId} 用于按文章删除与重建，{@code categoryId} / {@code publishTime} 供后续
     * 按板块或时间过滤，{@code chunkIndex} 用于引用片段的顺序还原。
     */
    @Bean
    public RedisVectorStore vectorStore(
            JedisPooled jedisPooled,
            EmbeddingModel embeddingModel,
            @Value("${spring.ai.vectorstore.redis.index-name:bitforum-kb}") String indexName,
            @Value("${spring.ai.vectorstore.redis.prefix:bitforum:kb:}") String prefix,
            @Value("${spring.ai.vectorstore.redis.initialize-schema:false}") boolean initializeSchema) {
        return RedisVectorStore.builder(jedisPooled, embeddingModel)
                .indexName(indexName)
                .prefix(prefix)
                .metadataFields(
                        MetadataField.tag("articleId"),
                        MetadataField.tag("categoryId"),
                        MetadataField.tag("status"),
                        MetadataField.numeric("publishTime"),
                        MetadataField.numeric("chunkIndex"))
                .initializeSchema(initializeSchema)
                .build();
    }
}

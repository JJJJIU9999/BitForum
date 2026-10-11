package com.bitforum.ai.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import redis.clients.jedis.JedisPooled;

/**
 * M15 第一步的最小验证：Redis 向量库（RediSearch）能建索引、能写入、能按元数据过滤检索。
 *
 * <p>验证目标：
 *
 * <ol>
 *   <li>索引被创建，且**维度等于嵌入模型的维度（768）** —— DIM 写错会导致建索引失败或写入失败，
 *       这是 findings.md 6.8.5 记录的硬约束。</li>
 *   <li>写入与检索往返可用（{@code add} → {@code similaritySearch}）。</li>
 *   <li><b>覆盖 findings.md 待验证事项 T5</b>：元数据过滤在 Spring AI 封装层真正生效
 *       （此前只用原生命令验证过），下架文章不得被检索到。</li>
 * </ol>
 *
 * <p>测试会向共享 Redis 写入少量向量，并在 {@link AfterEach} 中按显式 id 清理。
 * 首次运行需要嵌入模型可用（已缓存则离线即可）。
 */
@SpringBootTest
class RedisVectorStoreSmokeTest {

    private static final Logger log = LoggerFactory.getLogger(RedisVectorStoreSmokeTest.class);

    /** 向量字段名与 Spring AI 的默认一致；维度从索引定义中读取。 */
    private static final String EMBEDDING_FIELD = "embedding";

    private static final long BASE_PUBLISH_TIME = 1_767_225_600L;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private JedisPooled jedisPooled;

    @Value("${spring.ai.vectorstore.redis.index-name:bitforum-kb}")
    private String indexName;

    private final List<String> insertedIds = new ArrayList<>();

    @AfterEach
    void cleanUpInsertedVectors() {
        if (!insertedIds.isEmpty()) {
            vectorStore.delete(insertedIds);
            log.info(">>> 已清理测试向量 {} 条", insertedIds.size());
            insertedIds.clear();
        }
    }

    @Test
    void shouldCreateIndexWithEmbeddingModelDimension() {
        Map<String, Object> indexInfo = jedisPooled.ftInfo(indexName);

        assertFalse(indexInfo.isEmpty(), "索引不存在，检查 initialize-schema 是否为 true：" + indexName);

        Object attributes = indexInfo.get("attributes");
        log.info(">>> 索引 {} 的字段定义 = {}", indexName, attributes);

        Integer dimension = vectorDimension(attributes);
        assertNotNull(dimension, "未能在索引定义中找到向量维度，实际字段定义：" + attributes);
        assertEquals(768, dimension, "索引维度必须等于嵌入模型维度（bge-base-zh-v1.5 = 768）");
    }

    @Test
    void shouldFilterRetrievalByArticleStatus() {
        List<Document> documents = List.of(
                chunk("9001", "使用 Redis 缓存热点数据，可以显著降低数据库查询压力", "PUBLISHED", 0),
                chunk("9002", "MySQL 索引能加快查询速度，但会降低写入性能", "PUBLISHED", 0),
                chunk("9003", "这是一篇已经下架的帖子内容，不应该出现在检索结果里", "OFFLINE", 0));

        vectorStore.add(documents);
        documents.forEach(document -> insertedIds.add(document.getId()));

        List<Document> results = vectorStore.similaritySearch(SearchRequest.builder()
                .query("如何降低数据库的查询压力")
                .topK(5)
                .filterExpression("status == 'PUBLISHED'")
                .build());

        assertFalse(results.isEmpty(), "过滤检索不应返回空结果");
        assertTrue(
                results.stream().noneMatch(document -> "OFFLINE".equals(document.getMetadata().get("status"))),
                "已下架文章不得出现在检索结果中，实际返回：" + results.stream()
                        .map(document -> document.getMetadata().get("articleId") + "/"
                                + document.getMetadata().get("status"))
                        .toList());

        log.info(">>> 过滤检索返回 {} 条，命中文章 = {}", results.size(), results.stream()
                .map(document -> document.getMetadata().get("articleId"))
                .toList());

        // 已发布内容应能召回（语义相关），用于确认不是"过滤把结果全滤没了"
        assertTrue(
                results.stream().anyMatch(document -> "9001".equals(document.getMetadata().get("articleId"))),
                "与查询最相关的已发布片段应被召回");
    }

    private Document chunk(String articleId, String content, String status, int chunkIndex) {
        return new Document(
                "test-" + articleId + "-" + chunkIndex,
                content,
                Map.of(
                        "articleId", articleId,
                        "categoryId", "1",
                        "status", status,
                        "publishTime", BASE_PUBLISH_TIME,
                        "chunkIndex", chunkIndex));
    }

    /**
     * 从 RediSearch 的 FT.INFO attributes 中取出向量字段的 DIM。
     *
     * <p>jedis 的 {@code ftInfo} 把每个字段定义返回成**扁平的键值列表**
     * （形如 {@code [identifier, $.embedding, attribute, embedding, type, VECTOR, dim, 768, ...]}），
     * 而不是 Map，所以要按相邻两元素配对来解析。
     */
    private Integer vectorDimension(Object attributes) {
        if (!(attributes instanceof List<?> list)) {
            return null;
        }
        for (Object item : list) {
            if (!(item instanceof List<?> entries)) {
                continue;
            }
            Map<String, String> field = new LinkedHashMap<>();
            for (int i = 0; i + 1 < entries.size(); i += 2) {
                field.put(String.valueOf(entries.get(i)), String.valueOf(entries.get(i + 1)));
            }
            if (EMBEDDING_FIELD.equals(field.get("attribute")) && field.containsKey("dim")) {
                return Integer.valueOf(field.get("dim"));
            }
        }
        return null;
    }
}

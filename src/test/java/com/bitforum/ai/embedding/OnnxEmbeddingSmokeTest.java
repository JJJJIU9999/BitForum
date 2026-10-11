package com.bitforum.ai.embedding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * M15 / findings.md 待验证事项 T3 的最小验证：本地 ONNX 嵌入模型能加载并产出向量。
 *
 * <p>验证目标（对应 m15-handoff.md 第三节）：
 *
 * <ol>
 *   <li>引入 {@code spring-ai-starter-model-transformers} 后，{@link EmbeddingModel} 能通过自动配置注入。
 *       该 starter 的自动配置实测只有 {@code @ConditionalOnClass}、没有 {@code @ConditionalOnProperty}，
 *       因此只要依赖在类路径上，bean 必定被创建；本测试就是这一链路的集成验证。</li>
 *   <li>{@code embed()} 返回的向量维度大于 0 且**稳定**。Redis 向量索引必须显式声明 {@code DIM}，
 *       维度写错会导致 {@code FT.CREATE} 失败，所以维度必须先测出来。</li>
 *   <li>语义区分能力成立：相关文本的余弦相似度高于不相关文本。这是 RAG 召回质量的下限保证。</li>
 * </ol>
 *
 * <p>断言刻意做成**模型无关**：只要求"相关 > 不相关"，不假设具体模型。这样更换嵌入模型
 * （例如从英文 all-MiniLM-L6-v2 换成中文 bge-base-zh-v1.5）时不需要改测试，
 * 只需改 {@code application.yml} 里的两个 URI。中英文的具体相似度数值以日志形式记录，
 * 作为选型证据写回 findings.md。
 *
 * <p>运行前提：首次运行需要联网下载模型文件到 {@code ${user.home}/.cache/bitforum-onnx}
 * （当前模型 bge-base-zh-v1.5 量化版约 102MB）；下载完成后可完全离线运行。
 */
@SpringBootTest
class OnnxEmbeddingSmokeTest {

    private static final Logger log = LoggerFactory.getLogger(OnnxEmbeddingSmokeTest.class);

    @Autowired
    private EmbeddingModel embeddingModel;

    @Test
    void shouldLoadOnnxModelAndReturnStableDimension() {
        assertNotNull(embeddingModel, "EmbeddingModel 未装配：检查 spring-ai-starter-model-transformers 依赖");

        float[] first = embeddingModel.embed("BitForum 是一个基于 Spring Boot 的论坛系统");
        float[] second = embeddingModel.embed("向量检索用于语义召回");

        assertNotNull(first, "embed() 不应返回 null");
        assertTrue(first.length > 0, "向量维度应大于 0");
        assertEquals(first.length, second.length, "同一模型两次调用的向量维度必须一致");

        for (float value : first) {
            assertTrue(Float.isFinite(value), "向量元素应为有限浮点数，实际出现：" + value);
        }
        assertFalseAllZero(first);

        log.info(">>> T3 实测：嵌入维度 = {}（Redis 向量索引 DIM 必须等于该值），示例前 5 维 = {}",
                first.length, head(first, 5));
    }

    @Test
    void shouldRankRelatedChineseTextHigherThanUnrelated() {
        // 中文是站内内容的主语言，检索质量以中文为准。
        String query = "这篇文章介绍了如何发布帖子";
        String related = "发帖流程与文章发布说明";
        String unrelated = "今天天气不错，适合出门散步";

        double relatedScore = cosine(embeddingModel.embed(query), embeddingModel.embed(related));
        double unrelatedScore = cosine(embeddingModel.embed(query), embeddingModel.embed(unrelated));

        assertTrue(
                relatedScore > unrelatedScore,
                "中文相关文本的相似度应高于不相关文本，实际：相关=" + relatedScore + " 不相关=" + unrelatedScore);

        // 英文数值只记录不断言：英文能力不是本站检索的硬要求，仅用于对比不同候选模型。
        double englishRelatedScore = cosine(
                embeddingModel.embed("The forum supports publishing articles and comments."),
                embeddingModel.embed("Users can post articles and write comments on the forum."));
        double englishUnrelatedScore = cosine(
                embeddingModel.embed("The forum supports publishing articles and comments."),
                embeddingModel.embed("Redis is an in-memory key value database."));

        log.info(">>> T3 相似度证据（中文）：相关 = {}，不相关 = {}", relatedScore, unrelatedScore);
        log.info(">>> T3 相似度证据（英文）：相关 = {}，不相关 = {}", englishRelatedScore, englishUnrelatedScore);
    }

    private void assertFalseAllZero(float[] vector) {
        for (float value : vector) {
            if (value != 0f) {
                return;
            }
        }
        throw new AssertionError("向量所有维度均为 0，模型可能未真正执行推理");
    }

    private String head(float[] vector, int count) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < Math.min(count, vector.length); i++) {
            builder.append(String.format("%.4f", vector[i]));
            if (i < count - 1) {
                builder.append(", ");
            }
        }
        return builder.append("]").toString();
    }

    private double cosine(float[] left, float[] right) {
        double dot = 0d;
        double leftNorm = 0d;
        double rightNorm = 0d;
        for (int i = 0; i < left.length; i++) {
            dot += (double) left[i] * right[i];
            leftNorm += (double) left[i] * left[i];
            rightNorm += (double) right[i] * right[i];
        }
        if (leftNorm == 0d || rightNorm == 0d) {
            throw new IllegalArgumentException("零向量无法计算余弦相似度");
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }
}

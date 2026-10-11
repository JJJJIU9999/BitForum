package com.bitforum.ai.embedding;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * M15 嵌入模型选型评测探针（默认不参与常规回归）。
 *
 * <p>用途：在同一批"中文查询 → 候选文档"样本上比较不同嵌入模型的检索排序能力，
 * 回答的问题是：**相关文档能否被排到第一位**。分数本身没有绝对意义，只有相互比较有意义。
 *
 * <p>为什么需要它：{@code TransformersEmbeddingModel} 的 pooling 是私有的 {@code meanPooling}，
 * 硬编码不可配置；而不同嵌入模型训练时使用的 pooling 方式并不相同
 * （例如 BGE 系列用 CLS，多语言 MiniLM / e5 用 mean）。选型必须看实际效果，不能只看模型名气。
 *
 * <p>运行方式（三次分别指向不同模型的模型文件 URI；模型缓存在 {@code ~/.cache/bitforum-onnx}）：
 *
 * <pre>
 *   export EMBEDDING_PROBE=true
 *   export SPRING_AI_EMBEDDING_TRANSFORMER_ONNX_MODEL_URI="&lt;model.onnx 地址&gt;"
 *   export SPRING_AI_EMBEDDING_TRANSFORMER_TOKENIZER_URI="&lt;tokenizer.json 地址&gt;"
 *   ./mvnw -Dtest=EmbeddingModelComparisonProbe test
 * </pre>
 *
 * 注意：探针按原始文本（不加 query/passage 前缀）评测，因此对需要前缀的模型（e5 系列）
 * 是保守估计——若它在无前缀下仍然最优，结论更可靠。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "EMBEDDING_PROBE", matches = "true")
class EmbeddingModelComparisonProbe {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingModelComparisonProbe.class);

    @Autowired
    private EmbeddingModel embeddingModel;

    /** 一条样本：一个查询、一段真正相关的文档、若干干扰项（含字面相近但语义无关的项）。 */
    private record Case(String name, String query, String relevant, List<String> distractors) {
    }

    @Test
    void shouldRankRelevantDocumentFirst() {
        List<Case> cases = List.of(
                new Case(
                        "发帖流程",
                        "怎么把帖子发出去",
                        "文章发布流程：登录后点击「写文章」，填写标题与正文，选择板块后提交即可",
                        List.of("如何修改个人头像和昵称", "Redis 缓存热点数据降低数据库压力", "MySQL 索引优化技巧")),
                new Case(
                        "缓存主题",
                        "Redis 缓存怎么用",
                        "使用 Redis 缓存热点数据，显著降低数据库查询压力",
                        List.of("MySQL 索引优化技巧", "如何修改个人头像和昵称", "今天食堂吃什么")),
                new Case(
                        "性能改写（字面完全不同）",
                        "网站打开很慢怎么办",
                        "性能优化实践：通过本地缓存、分页查询与索引优化降低接口响应时间",
                        List.of("如何更换主题颜色", "文章发布流程说明", "用户积分规则介绍")),
                new Case(
                        "关注功能",
                        "怎么关注别的用户",
                        "用户关注功能说明：进入他人主页点击关注按钮，即可收到对方发帖通知",
                        List.of("如何修改个人头像和昵称", "Redis 缓存热点数据", "今天食堂吃什么")),
                new Case(
                        "举报治理",
                        "看到违规内容怎么办",
                        "内容举报与治理：在帖子详情页点击举报，管理员审核后会处理违规内容",
                        List.of("文章发布流程说明", "如何更换主题颜色", "用户积分规则介绍")));

        int top1Hits = 0;
        double marginSum = 0d;

        log.info(">>> 模型评测开始（共 {} 条样本，模型维度 {}）", cases.size(), embeddingModel.dimensions());
        for (Case item : cases) {
            float[] queryVector = embeddingModel.embed(item.query());
            double relevantScore = cosine(queryVector, embeddingModel.embed(item.relevant()));

            String bestDistractor = null;
            double bestDistractorScore = Double.NEGATIVE_INFINITY;
            for (String distractor : item.distractors()) {
                double score = cosine(queryVector, embeddingModel.embed(distractor));
                if (score > bestDistractorScore) {
                    bestDistractorScore = score;
                    bestDistractor = distractor;
                }
            }

            boolean hit = relevantScore > bestDistractorScore;
            if (hit) {
                top1Hits++;
            }
            double margin = relevantScore - bestDistractorScore;
            marginSum += margin;

            log.info(
                    ">>> [{}] 排名正确={} | 相关={} | 最高干扰={} | 差距={} | 干扰项=「{}」",
                    item.name(),
                    hit ? "是" : "**否**",
                    String.format("%.4f", relevantScore),
                    String.format("%.4f", bestDistractorScore),
                    String.format("%+.4f", margin),
                    bestDistractor);
        }

        log.info(">>> 评测结论：Top-1 命中 {}/{}，平均区分度差距 {}",
                top1Hits, cases.size(), String.format("%.4f", marginSum / cases.size()));
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

    /** 便于将来扩充样本：把逗号分隔的字符串转成列表。 */
    @SuppressWarnings("unused")
    private static List<String> of(String... values) {
        return new ArrayList<>(List.of(values));
    }
}

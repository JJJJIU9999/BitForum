package com.bitforum.ai.recommend;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import com.bitforum.ai.rag.KbIndexService;
import com.bitforum.ai.rag.RagService;
import com.bitforum.entity.Article;
import com.bitforum.service.ArticleService;

/**
 * M17 前置验证探针（T11）：向量召回这一路在"文章找相似文章"场景下是否可用。
 *
 * <p><b>为什么验这一件事</b>：M17 的推荐第一路召回计划复用 M15 的 {@link RagService}。
 * 但 RagService 是**为问答检索调优的** —— 它的相似度下限
 * {@code bitforum.ai.rag.similarity-threshold}（默认 0.5）是在"用户提问 → 文章片段"
 * 这种语义落差较大的场景下定的。而推荐要做的是"**文章 → 文章**"，
 * 两边表达的是同一类内容，相似度分布与问答场景并不相同。
 *
 * <p>如果 0.5 这个阈值把文章之间的召回全部过滤掉，那么推荐的第一路召回就是**形同虚设**，
 * 必须为推荐单独设置阈值（或改用不带阈值的 topK）。这件事不实测无法知道，
 * 而它直接决定 RecommendAgent 的召回设计。
 *
 * <p>探针做三件事：
 * <ol>
 *   <li>全量重建知识库索引（当前索引为空，正好也是演示前必须做的动作）；</li>
 *   <li>对每篇已发布文章，用它的标题当查询，在**阈值 0** 下取回全部候选与相似度分数，
 *       观察文章之间的真实相似度分布；</li>
 *   <li>用 {@link RagService#retrieve} 的**默认阈值**再跑一次，看还能召回几篇 —— 两者对比即结论。</li>
 * </ol>
 *
 * <p>本探针默认不执行（它会重建 Redis 向量索引，不适合放进常规测试）：
 *
 * <pre>
 *   export M17_VECTOR_PROBE=true
 *   ./mvnw -s maven-settings.xml -Dtest=RecommendVectorRecallProbe test
 * </pre>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "M17_VECTOR_PROBE", matches = "true")
class RecommendVectorRecallProbe {

    private static final Logger log = LoggerFactory.getLogger(RecommendVectorRecallProbe.class);

    /** 观测分布时用 0 阈值取全部候选，再看它们的分数长什么样。 */
    private static final double OBSERVE_THRESHOLD = 0.0d;

    @Autowired
    private KbIndexService kbIndexService;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private RagService ragService;

    @Autowired
    private ArticleService articleService;

    @Value("${bitforum.ai.rag.query-prefix:}")
    private String queryPrefix;

    @Value("${bitforum.ai.rag.similarity-threshold:0.5}")
    private double configuredThreshold;

    @Value("${bitforum.ai.rag.top-k:5}")
    private int configuredTopK;

    @Test
    void shouldShowSimilarityDistributionBetweenArticles() {
        // 1. 重建索引（当前 bitforum:kb:* 为空，管理页的「全量重建」等价动作）
        KbIndexService.RebuildResult rebuild = kbIndexService.rebuildAll();
        log.info(">>> 索引重建：已发布文章 {} 篇，已索引 {}，跳过 {}，移除 {}，失败 {}，耗时 {} ms",
                rebuild.publishedArticles(), rebuild.indexed(), rebuild.skipped(),
                rebuild.removed(), rebuild.failed(), rebuild.elapsedMillis());

        KbIndexService.KbStats stats = kbIndexService.stats();
        log.info(">>> 知识库统计：documents={} indexed={} chunks={} embeddingModel={}",
                stats.documents(), stats.indexedDocuments(), stats.chunks(), stats.embeddingModel());

        List<Article> published = articleService.listAll().stream()
                .filter(article -> ArticleService.STATUS_PUBLISHED.equals(article.getStatus()))
                .toList();
        log.info(">>> 已发布文章（推荐候选池）：{}", published.stream()
                .map(article -> article.getId() + "《" + article.getTitle() + "》")
                .toList());

        List<String> report = new ArrayList<>();
        for (Article article : published) {
            String query = article.getTitle();
            List<String> scores = new ArrayList<>();
            for (Document document : similaritySearch(query, OBSERVE_THRESHOLD, 30)) {
                Object articleId = document.getMetadata().get("articleId");
                Double score = document.getScore();
                // 自己找自己必然是最高分，标出来便于观察"除自己之外"的分布
                String marker = String.valueOf(articleId).equals(String.valueOf(article.getId())) ? "(自身)" : "";
                scores.add("%s=%s%s".formatted(articleId,
                        score == null ? "null" : String.format("%.4f", score), marker));
            }

            RagService.RetrievalResult defaultResult = ragService.retrieve(query);
            List<Long> defaultIds = defaultResult.citations().stream().map(citation -> citation.articleId()).toList();

            String line = ("查询 id=%d《%s》\n"
                    + "    阈值 %.2f 的全部候选（含分数）：%s\n"
                    + "    RagService（阈值 %.2f，topK %d）实际召回：%s（%d 段）")
                    .formatted(article.getId(), query,
                            OBSERVE_THRESHOLD, scores,
                            configuredThreshold, configuredTopK, defaultIds, defaultResult.chunks().size());
            log.info(">>> {}", line);
            report.add(line);
        }

        log.info("""

                ================= T11 探针汇总（阈值 {} 观测 vs 默认 {} 实际召回） =================
                {}
                ==========================================================================
                """, OBSERVE_THRESHOLD, configuredThreshold, String.join("\n", report));
    }

    private List<Document> similaritySearch(String query, double threshold, int topK) {
        return vectorStore.similaritySearch(SearchRequest.builder()
                .query(queryPrefix + query)
                .topK(topK)
                .similarityThreshold(threshold)
                .filterExpression("status == '" + ArticleService.STATUS_PUBLISHED + "'")
                .build());
    }
}

package com.bitforum.ai.recommend.eval;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
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

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bitforum.entity.Article;
import com.bitforum.entity.Category;
import com.bitforum.mapper.ArticleMapper;
import com.bitforum.mapper.CategoryMapper;

/**
 * M17 评测数据的**区分度检验**（`m17-eval-protocol.md` §6.3 的硬条件 C1）。
 *
 * <p><b>为什么必须有这一步</b>：T11 实测（findings.md 6.15）发现，原有的 5 篇测试文章
 * 因为正文只有 44~67 字，"同主题"与"跨主题"的相似度只差 **0.0048** —— 向量排序接近随机。
 * 如果新生成的评测数据也是这个样子，那么后面算出来的任何命中率都只是在测噪声，
 * 而且很难从数字上看出来。所以数据生成后、评测之前，必须先过这道检验：
 *
 * <pre>
 *   区分度 = 同主题文章对的平均相似度 − 跨主题文章对的平均相似度
 * </pre>
 *
 * <p><b>硬条件</b>：区分度必须 > 0（规范要求 > 0，目标 > 0.05）。
 * 不达标时的正确反应是**重做数据**，而不是调算法 —— 后者等于用算法去补偿数据的缺失。
 *
 * <p>本检查只读数据，不改任何东西。默认不执行：
 *
 * <pre>
 *   export M17_EVAL_CHECK=true
 *   ./mvnw -s maven-settings.xml -Dtest=M17CorpusDiscriminationCheck test
 * </pre>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "M17_EVAL_CHECK", matches = "true")
class M17CorpusDiscriminationCheck {

    private static final Logger log = LoggerFactory.getLogger(M17CorpusDiscriminationCheck.class);

    private static final String TITLE_PREFIX = "[M17Eval] ";
    private static final String CATEGORY_PREFIX = "M17Eval-";

    /** 检索时取回多少条：评测语料是 50 篇左右，取大一点才能覆盖全部文章对。 */
    private static final int TOP_K = 200;

    /** 规范 §6.3 的目标值（不是硬条件，硬条件是 > 0）。 */
    private static final double TARGET_DISCRIMINATION = 0.05d;

    @Autowired
    private VectorStore vectorStore;
    @Autowired
    private ArticleMapper articleMapper;
    @Autowired
    private CategoryMapper categoryMapper;

    @Value("${bitforum.ai.rag.query-prefix:}")
    private String queryPrefix;

    @Test
    void shouldSeparateSameThemeFromCrossTheme() {
        Map<Long, String> themeByCategory = loadThemeByCategory();
        List<Article> articles = articleMapper.selectList(new LambdaQueryWrapper<Article>()
                        .likeRight(Article::getTitle, TITLE_PREFIX)
                        .eq(Article::getStatus, "PUBLISHED"));

        Assumptions.assumeTrue(articles.size() >= 40,
                "评测语料不足 40 篇，请先运行 M17EvalDataSeeder 生成数据（当前 " + articles.size() + " 篇）");

        Map<Long, String> themeByArticle = new HashMap<>();
        for (Article article : articles) {
            String theme = themeByCategory.get(article.getCategoryId());
            if (theme != null) {
                themeByArticle.put(article.getId(), theme);
            }
        }

        double sameSum = 0d;
        double crossSum = 0d;
        long sameCount = 0;
        long crossCount = 0;
        int sameThemeInTop5 = 0;
        int top5Total = 0;

        for (Article article : articles) {
            if (!themeByArticle.containsKey(article.getId())) {
                continue;
            }
            String theme = themeByArticle.get(article.getId());
            String query = queryPrefix + article.getTitle() + " "
                    + excerpt(article.getContent(), 200);

            List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(query)
                    .topK(TOP_K)
                    .similarityThreshold(0d)
                    .filterExpression("status == 'PUBLISHED'")
                    .build());
            if (hits == null) {
                continue;
            }

            int rank = 0;
            for (Document hit : hits) {
                Long otherId = parseArticleId(hit);
                if (otherId == null || otherId.equals(article.getId())) {
                    continue; // 跳过自己：自己与自己的相似度恒为最高，计入会虚高
                }
                String otherTheme = themeByArticle.get(otherId);
                if (otherTheme == null) {
                    continue; // 非评测语料（例如库里原有的测试文章），不计入统计
                }
                double score = hit.getScore() == null ? 0d : hit.getScore();
                rank++;
                if (theme.equals(otherTheme)) {
                    sameSum += score;
                    sameCount++;
                    if (rank <= 5) {
                        sameThemeInTop5++;
                    }
                } else {
                    crossSum += score;
                    crossCount++;
                }
                if (rank <= 5) {
                    top5Total++;
                }
            }
        }

        Assumptions.assumeTrue(sameCount > 0 && crossCount > 0, "样本不足，无法统计区分度");

        double sameAverage = sameSum / sameCount;
        double crossAverage = crossSum / crossCount;
        double discrimination = sameAverage - crossAverage;
        double sameThemeRatioInTop5 = top5Total == 0 ? 0d : (double) sameThemeInTop5 / top5Total;
        // 8 个主题均匀分布时，Top-5 里"恰好同主题"的随机期望约为 1/8
        double randomBaseline = 1d / 8;

        log.info("""

                ================= M17 语料区分度检验 =================
                参与检验的文章：{} 篇
                同主题文章对：{} 对，平均相似度 {}
                跨主题文章对：{} 对，平均相似度 {}
                ────────────────────────────────────────────
                区分度（同主题 − 跨主题）= {}
                目标值：> {}（硬条件：> 0）
                ────────────────────────────────────────────
                Top-5 中同主题文章的占比：{}（随机期望约 {}）
                ======================================================
                """,
                articles.size(),
                sameCount, String.format("%.4f", sameAverage),
                crossCount, String.format("%.4f", crossAverage),
                String.format("%.4f", discrimination),
                String.format("%.2f", TARGET_DISCRIMINATION),
                String.format("%.1f%%", sameThemeRatioInTop5 * 100),
                String.format("%.1f%%", randomBaseline * 100));

        assertTrue(discrimination > 0,
                "区分度必须为正（硬条件 C1），实际为 " + discrimination
                        + "：说明同主题与跨主题的相似度没有差别，向量通道等于随机排序，必须重做数据");
    }

    private Map<Long, String> loadThemeByCategory() {
        Map<Long, String> themeByCategory = new LinkedHashMap<>();
        List<Category> categories = categoryMapper.selectList(new LambdaQueryWrapper<Category>()
                .likeRight(Category::getName, CATEGORY_PREFIX));
        for (Category category : categories) {
            themeByCategory.put(category.getId(),
                    category.getName().substring(CATEGORY_PREFIX.length()));
        }
        return themeByCategory;
    }

    private Long parseArticleId(Document document) {
        Object value = document.getMetadata().get("articleId");
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String excerpt(String content, int maxChars) {
        if (content == null) {
            return "";
        }
        return content.length() <= maxChars ? content : content.substring(0, maxChars);
    }
}

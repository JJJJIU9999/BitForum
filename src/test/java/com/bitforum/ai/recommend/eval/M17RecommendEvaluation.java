package com.bitforum.ai.recommend.eval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bitforum.ai.entity.AiRecommendLog;
import com.bitforum.ai.recommend.RecommendService;
import com.bitforum.ai.recommend.RecommendService.RecommendRequest;
import com.bitforum.ai.recommend.RecommendService.RecommendResult;
import com.bitforum.ai.recommend.RecommendService.RecommendedArticle;
import com.bitforum.entity.Article;
import com.bitforum.entity.ArticleFavorite;
import com.bitforum.entity.User;
import com.bitforum.mapper.ArticleFavoriteMapper;
import com.bitforum.mapper.ArticleMapper;
import com.bitforum.mapper.UserMapper;
import com.bitforum.service.ArticleService;

/**
 * M17 推荐效果评测：留一法（leave-one-out），严格按 `m17-eval-protocol.md` 执行。
 *
 * <p><b>Ground Truth</b>：对每个评测用户，从其收藏中固定随机抽一篇作为"下一步会收藏的文章"，
 * 把它**留在候选池里**、其余收藏进排除集，看三条线（融合推荐 / 纯热榜 / 随机）能否把它排进 Top-10。
 * 为什么必须这样构造，见规范 §3.1 —— 直接拿收藏当命中会被"排除已收藏"策略变成恒 0。
 *
 * <p><b>数据集划分</b>：用户按 id 排序后 70% 为开发集、30% 为独立测试集，
 * 用环境变量 {@code M17_EVAL_SPLIT=dev|test} 选择。测试集在口径冻结前只看一次（规范 §6.4 / §7）。
 *
 * <p><b>本测试不做效果断言</b>：命中率高低是**实验结果**，不是代码正确性。
 * 如果融合推荐没赢过热榜，正确反应是如实记录（规范 C2），而不是把构建改红或换口径。
 * 唯一断言的是实现正确性：推荐列表里**不得**出现用户的其它收藏。
 *
 * <pre>
 *   export M17_EVAL_RUN=true M17_EVAL_SPLIT=dev
 *   ./mvnw -s maven-settings.xml -Dtest=M17RecommendEvaluation test
 * </pre>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "M17_EVAL_RUN", matches = "true")
class M17RecommendEvaluation {

    private static final Logger log = LoggerFactory.getLogger(M17RecommendEvaluation.class);

    private static final String USER_PREFIX = "m17eval_user_";
    private static final String TITLE_PREFIX = "[M17Eval] ";

    private static final int TOP_N = 10;
    private static final long SEED = 20_260_919L;
    private static final double DEV_RATIO = 0.7d;

    /** 参与评测的最低收藏数：低于此值时"已知偏好"太稀疏，测的是运气而不是算法。 */
    private static final int MIN_FAVORITES = 3;

    @Autowired
    private RecommendService recommendService;
    @Autowired
    private ArticleFavoriteMapper articleFavoriteMapper;
    @Autowired
    private ArticleMapper articleMapper;
    @Autowired
    private UserMapper userMapper;

    /** 三条对比线。 */
    private enum Line {
        RECOMMEND("融合推荐"),
        HOT("纯热榜基线"),
        RANDOM("随机（sanity check）");

        private final String displayName;

        Line(String displayName) {
            this.displayName = displayName;
        }
    }

    @Test
    void runLeaveOneOutEvaluation() {
        String split = System.getenv().getOrDefault("M17_EVAL_SPLIT", "dev").trim().toLowerCase();
        boolean testSplit = "test".equals(split);

        List<User> evalUsers = userMapper.selectList(new LambdaQueryWrapper<User>()
                .likeRight(User::getUsername, USER_PREFIX)
                .orderByAsc(User::getId));
        Assumptions.assumeTrue(evalUsers.size() >= 10,
                "评测用户不足，请先运行 M17EvalDataSeeder（当前 " + evalUsers.size() + " 位）");

        int devSize = (int) Math.round(evalUsers.size() * DEV_RATIO);
        List<User> splitUsers = testSplit
                ? evalUsers.subList(devSize, evalUsers.size())
                : evalUsers.subList(0, devSize);

        Set<Long> evalArticleIds = new LinkedHashSet<>(articleMapper.selectList(
                        new LambdaQueryWrapper<Article>()
                                .select(Article::getId)
                                .likeRight(Article::getTitle, TITLE_PREFIX))
                .stream().map(Article::getId).toList());

        Map<Line, double[]> rankSum = new EnumMap<>(Line.class);
        Map<Line, Integer> hits = new EnumMap<>(Line.class);
        for (Line line : Line.values()) {
            rankSum.put(line, new double[] {0d});
            hits.put(line, 0);
        }
        Map<Integer, Integer> recommendHitRankDistribution = new java.util.TreeMap<>();
        Map<String, Integer> channelCoverage = new java.util.LinkedHashMap<>();
        int evaluated = 0;
        int skipped = 0;

        for (User user : splitUsers) {
            List<Long> favorites = favoriteArticleIds(user.getId());
            if (favorites.size() < MIN_FAVORITES) {
                skipped++;
                continue;
            }

            long target = pickTarget(favorites, user.getId());
            Set<Long> excluded = new LinkedHashSet<>(favorites);
            excluded.remove(target); // 关键：目标必须留在候选池里
            excluded.addAll(ownedArticleIds(user.getId()));

            // 兴趣画像 = 除目标之外的收藏。目标**绝不能**被当作已知兴趣参与向量查询，
            // 否则等于把答案提前告诉算法，命中率会虚高（推荐评测里最常见的泄漏）
            List<Long> interests = favorites.stream().filter(id -> !id.equals(target)).toList();
            RecommendResult recommendResult = recommendResult(user.getId(), excluded, interests);
            List<Long> recommendIds = idsOf(recommendResult);
            List<Long> hotIds = hotBaselineIds(user.getId(), excluded);
            List<Long> randomIds = randomIds(user.getId(), excluded);

            // 实现正确性：推荐列表里不得出现用户的其它收藏（规范 §3.2 排除集定义）
            for (Long id : recommendIds) {
                if (excluded.contains(id)) {
                    throw new IllegalStateException(
                            "推荐列表出现了被排除的文章：userId=" + user.getId() + "，articleId=" + id);
                }
            }

            evaluated++;
            record(Line.RECOMMEND, recommendIds, target, rankSum, hits);
            record(Line.HOT, hotIds, target, rankSum, hits);
            record(Line.RANDOM, randomIds, target, rankSum, hits);

            int rank = rankOf(recommendIds, target);
            if (rank > 0) {
                recommendHitRankDistribution.merge(rank, 1, Integer::sum);
            }
            for (RecommendedArticle article : recommendResult.articles()) {
                for (String source : article.sources()) {
                    channelCoverage.merge(source, 1, Integer::sum);
                }
            }

            log.debug("用户 {}：目标={}，融合排名={}，热榜排名={}，随机排名={}",
                    user.getId(), target, rank, rankOf(hotIds, target), rankOf(randomIds, target));
        }

        Assumptions.assumeTrue(evaluated > 0, "没有符合条件的评测用户（收藏数均不足 " + MIN_FAVORITES + "）");

        StringBuilder report = new StringBuilder();
        report.append("\n================= M17 推荐评测（留一法） =================\n");
        report.append("数据集：").append(testSplit ? "独立测试集" : "开发集").append("（用户 ")
                .append(splitUsers.size()).append(" 位，实际参与 ").append(evaluated)
                .append(" 位，跳过 ").append(skipped).append(" 位）\n");
        report.append("候选池规模：").append(candidatePoolSize()).append(" 篇已发布文章（其中评测语料 ")
                .append(evalArticleIds.size()).append(" 篇）\n");
        report.append(String.format("%-16s %-12s %-12s%n", "对比线", "HitRate@10", "MRR@10"));
        for (Line line : Line.values()) {
            report.append(String.format("%-16s %-12s %-12s%n",
                    line.displayName,
                    String.format("%.4f", (double) hits.get(line) / evaluated),
                    String.format("%.4f", rankSum.get(line)[0] / evaluated)));
        }
        report.append("\n融合推荐命中排名分布：").append(recommendHitRankDistribution).append('\n');
        report.append("Top-10 各召回通道贡献（累计篇次）：").append(channelCoverage).append('\n');
        report.append("""
                
                【声明】本评测使用**合成数据**（文章、用户、行为均由项目作者构造，不是真实社区流量）；
                热度为随机值、与主题无关，因此纯热榜基线在"主题兴趣"上没有信息量。
                引用本结果时必须带上这两句。
                """);
        report.append("=========================================================\n");
        log.info("{}", report);

        double recommendHit = (double) hits.get(Line.RECOMMEND) / evaluated;
        double hotHit = (double) hits.get(Line.HOT) / evaluated;
        log.info(">>> 硬条件 C2 判定：融合推荐 HitRate@10 = {}，纯热榜 = {} → {}",
                String.format("%.4f", recommendHit), String.format("%.4f", hotHit),
                recommendHit > hotHit ? "达标（严格高于基线）" : "**未达标**（如实记录，不得调整测试集或口径）");
    }

    private RecommendResult recommendResult(Long userId, Set<Long> excluded, List<Long> interests) {
        return recommendService.recommend(new RecommendRequest(
                AiRecommendLog.SCENE_ADMIN, userId, null, TOP_N, "RECOMMEND"), excluded, interests);
    }

    private List<Long> idsOf(RecommendResult result) {
        return result.articles().stream().map(RecommendedArticle::articleId).toList();
    }

    private List<Long> hotBaselineIds(Long userId, Set<Long> excluded) {
        RecommendResult result = recommendService.hotBaseline(
                new RecommendRequest(AiRecommendLog.SCENE_ADMIN, userId, null, TOP_N,
                        AiRecommendLog.TAG_BASELINE_HOT),
                AiRecommendLog.TAG_BASELINE_HOT, excluded);
        return result.articles().stream().map(RecommendedArticle::articleId).toList();
    }

    /** 随机基线：在**同一候选池**里随机取 Top-N（种子固定，可复现）。 */
    private List<Long> randomIds(Long userId, Set<Long> excluded) {
        List<Long> pool = new ArrayList<>(articleMapper.selectList(new LambdaQueryWrapper<Article>()
                        .select(Article::getId)
                        .eq(Article::getStatus, ArticleService.STATUS_PUBLISHED))
                .stream().map(Article::getId)
                .filter(id -> !excluded.contains(id))
                .toList());
        Collections.shuffle(pool, new Random(SEED + userId));
        return pool.subList(0, Math.min(TOP_N, pool.size()));
    }

    private void record(Line line, List<Long> ids, long target,
                        Map<Line, double[]> rankSum, Map<Line, Integer> hits) {
        int rank = rankOf(ids, target);
        if (rank > 0) {
            hits.put(line, hits.get(line) + 1);
            rankSum.get(line)[0] += 1d / rank;
        }
    }

    private int rankOf(List<Long> ids, long target) {
        int index = ids.indexOf(target);
        return index < 0 ? 0 : index + 1;
    }

    private List<Long> favoriteArticleIds(Long userId) {
        return articleFavoriteMapper.selectList(new LambdaQueryWrapper<ArticleFavorite>()
                        .eq(ArticleFavorite::getUserId, userId)
                        .orderByAsc(ArticleFavorite::getId))
                .stream().map(ArticleFavorite::getArticleId).toList();
    }

    private Set<Long> ownedArticleIds(Long userId) {
        return new LinkedHashSet<>(articleMapper.selectList(new LambdaQueryWrapper<Article>()
                        .select(Article::getId)
                        .eq(Article::getUserId, userId))
                .stream().map(Article::getId).toList());
    }

    /**
     * 目标必须仍在架上：已下架的文章不在候选池里，拿它当目标等于测一个不可能完成的任务。
     */
    private long pickTarget(List<Long> favorites, Long userId) {
        List<Long> published = articleMapper.selectList(new LambdaQueryWrapper<Article>()
                        .select(Article::getId)
                        .in(Article::getId, favorites)
                        .eq(Article::getStatus, ArticleService.STATUS_PUBLISHED))
                .stream().map(Article::getId).toList();
        if (published.isEmpty()) {
            throw new IllegalStateException("用户 " + userId + " 的收藏里没有已发布文章");
        }
        return published.get(new Random(SEED + userId).nextInt(published.size()));
    }

    private long candidatePoolSize() {
        Long count = articleMapper.selectCount(new LambdaQueryWrapper<Article>()
                .eq(Article::getStatus, ArticleService.STATUS_PUBLISHED));
        return count == null ? 0 : count;
    }
}

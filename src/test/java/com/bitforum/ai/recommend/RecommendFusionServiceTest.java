package com.bitforum.ai.recommend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.bitforum.ai.recommend.RecommendFusionService.RecommendCandidate;
import com.bitforum.ai.recommend.RecommendRecallService.RecallSource;
import com.bitforum.ai.recommend.RecommendRecallService.RecalledArticle;

/**
 * 融合排序的单元测试（M17）。
 *
 * <p>这套逻辑是"推荐系统与纯热榜基线对比"能否成立的前提：**同样的召回结果必须得到同样的排序**。
 * 因此这里重点测确定性、多路命中的计分关系、排除集与截断，而不是"哪个权重更好"
 * （权重属于需要数据才能决定的事，这正是选择 RRF 的原因）。
 */
class RecommendFusionServiceTest {

    private final RecommendFusionService fusion = new RecommendFusionService(60);

    private static final double EPSILON = 1e-9;

    @Test
    void shouldScoreSingleHitByItsRank() {
        List<RecommendCandidate> result = fusion.fuse(
                List.of(new RecalledArticle(85L, RecallSource.VECTOR, 1, 0.71)), Set.of(), 10);

        assertEquals(1, result.size());
        RecommendCandidate candidate = result.get(0);
        assertEquals(85L, candidate.articleId());
        assertEquals(1d / 61, candidate.score(), EPSILON, "RRF：1/(k+rank) = 1/(60+1)");
        assertEquals(List.of("vector"), candidate.sources());
        assertEquals("{\"vector\":1}", candidate.scoreDetail());
        assertEquals(1, candidate.rank());
    }

    /** 被两路命中的文章应排在只被一路（且排名相同）命中的文章前面。 */
    @Test
    void shouldRankMultiChannelHitAboveSingleChannelHit() {
        List<RecommendCandidate> result = fusion.fuse(List.of(
                new RecalledArticle(85L, RecallSource.VECTOR, 1, 0.71),
                new RecalledArticle(87L, RecallSource.VECTOR, 2, 0.70),
                new RecalledArticle(87L, RecallSource.HOT, 1, 3.0)), Set.of(), 10);

        assertEquals(2, result.size());
        assertEquals(87L, result.get(0).articleId(), "两路命中的应排第一");
        assertEquals(2, result.get(0).sources().size());
        assertEquals(1, result.get(0).rank());
        assertEquals(85L, result.get(1).articleId());
        assertEquals(1d / 62 + 1d / 61, result.get(0).score(), EPSILON);
    }

    /** 同一路里排名靠前的分数必须更高（RRF 单调性）。 */
    @Test
    void shouldPreferBetterRankWithinSameChannel() {
        List<RecommendCandidate> result = fusion.fuse(List.of(
                new RecalledArticle(85L, RecallSource.HOT, 1, 9.0),
                new RecalledArticle(86L, RecallSource.HOT, 2, 8.0),
                new RecalledArticle(88L, RecallSource.HOT, 3, 7.0)), Set.of(), 10);

        assertEquals(List.of(85L, 86L, 88L), result.stream().map(RecommendCandidate::articleId).toList());
        assertTrue(result.get(0).score() > result.get(1).score());
        assertTrue(result.get(1).score() > result.get(2).score());
    }

    @Test
    void shouldDropExcludedArticles() {
        List<RecommendCandidate> result = fusion.fuse(List.of(
                new RecalledArticle(85L, RecallSource.VECTOR, 1, 0.71),
                new RecalledArticle(87L, RecallSource.VECTOR, 2, 0.70)), Set.of(85L), 10);

        assertEquals(List.of(87L), result.stream().map(RecommendCandidate::articleId).toList());
        assertEquals(1, result.get(0).rank(), "排除后应重新编号，排名不能有空档");
    }

    @Test
    void shouldTruncateToTopNAndNumberRanksFromOne() {
        List<RecommendCandidate> result = fusion.fuse(List.of(
                new RecalledArticle(85L, RecallSource.VECTOR, 1, 0.71),
                new RecalledArticle(86L, RecallSource.VECTOR, 2, 0.70),
                new RecalledArticle(87L, RecallSource.VECTOR, 3, 0.69)), Set.of(), 2);

        assertEquals(2, result.size());
        assertEquals(List.of(1, 2), result.stream().map(RecommendCandidate::rank).toList());
    }

    /** 并列分数必须按 articleId 稳定排序，否则"同样输入同样输出"不成立。 */
    @Test
    void shouldBreakScoreTiesByArticleId() {
        List<RecalledArticle> sameRankHits = List.of(
                new RecalledArticle(88L, RecallSource.HOT, 1, 3.0),
                new RecalledArticle(85L, RecallSource.HOT, 1, 3.0),
                new RecalledArticle(86L, RecallSource.HOT, 1, 3.0));

        List<Long> first = fusion.fuse(sameRankHits, Set.of(), 10).stream()
                .map(RecommendCandidate::articleId).toList();
        List<Long> second = fusion.fuse(sameRankHits, Set.of(), 10).stream()
                .map(RecommendCandidate::articleId).toList();

        assertEquals(List.of(85L, 86L, 88L), first);
        assertEquals(first, second, "同样输入必须得到同样排序");
    }

    @Test
    void shouldReturnEmptyForEmptyOrZeroTopN() {
        assertTrue(fusion.fuse(List.of(), Set.of(), 10).isEmpty());
        assertTrue(fusion.fuse(null, Set.of(), 10).isEmpty());
        assertTrue(fusion.fuse(List.of(new RecalledArticle(85L, RecallSource.HOT, 1, 1.0)), Set.of(), 0).isEmpty());
    }

    /** 调用方传 null 排除集不应导致 NPE（未登录场景没有排除项时容易传 null）。 */
    @Test
    void shouldTolerateNullExcludedSet() {
        List<RecommendCandidate> result = fusion.fuse(
                List.of(new RecalledArticle(85L, RecallSource.HOT, 1, 1.0)), null, 5);

        assertEquals(1, result.size());
    }
}

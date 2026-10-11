package com.bitforum.ai.recommend;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.bitforum.ai.recommend.RecommendRecallService.RecalledArticle;

/**
 * 召回结果融合（M17）。
 *
 * <p><b>用 RRF（Reciprocal Rank Fusion，倒数排名融合）而不是加权求和</b>，理由有两条：
 *
 * <ol>
 *   <li><b>三路的分数根本不可比</b>：向量是余弦相似度（0.66~0.78 挤在一起，见 findings.md 6.15），
 *       热度是 ZSet 累积分（实测出现过 3 与 1 这种量级），关注路是发布时间序号。
 *       加权求和必须先归一化，而归一化方式本身又是一个要拍的决定。</li>
 *   <li><b>权重需要数据才能定，而当前数据不足以定权重</b>：这是 M16 的直接教训 ——
 *       当时用开发集扫出来的自动放行阈值，在独立测试集上覆盖率跌到 0%。
 *       与其拍一个站不住的权重，不如用只依赖**排名**、不需要调参的方法。</li>
 * </ol>
 *
 * <p>RRF 的计分：{@code score = Σ 1 / (k + rank)}，k 取 60（原论文的常用值，可配置）。
 * 一篇文章被越多路召回、且在各路里排名越靠前，得分越高。融合结果完全由输入决定，
 * **同样的召回结果必然得到同样的排序** —— 这是"与纯热榜基线对比"能否归因的前提。
 */
@Service
public class RecommendFusionService {

    /**
     * 融合后的一篇推荐候选。
     *
     * @param articleId   文章 id
     * @param score       RRF 融合分（越大越靠前）
     * @param sources     命中它的召回通道 code，按计分顺序排列
     * @param scoreDetail 各路排名明细（JSON），落库后可用于复现排序与调整策略
     * @param rank        最终排名，从 1 开始
     */
    public record RecommendCandidate(Long articleId, double score, List<String> sources,
                                     String scoreDetail, int rank) {

        public boolean hasSource(String code) {
            return sources.contains(code);
        }
    }

    private final int rrfK;

    public RecommendFusionService(@Value("${bitforum.ai.recommend.rrf-k:60}") int rrfK) {
        // k 必须为正：k + rank 作分母，k = 0 时 rank=1 会得到 1.0 的极值，放大单路偶然命中
        this.rrfK = Math.max(1, rrfK);
    }

    /**
     * 融合并排序。
     *
     * @param recalled 三路召回的原始结果，同一篇文章可以出现多次（多路命中）
     * @param excluded 排除集（已收藏/已点赞/自己的文章等，由调用方按产品策略决定）
     * @param topN     最终取前几名
     * @return 按融合分降序的候选；分数相同则按 articleId 升序，保证结果确定
     */
    public List<RecommendCandidate> fuse(List<RecalledArticle> recalled, Set<Long> excluded, int topN) {
        if (recalled == null || recalled.isEmpty() || topN <= 0) {
            return List.of();
        }
        Set<Long> excludedIds = excluded == null ? Set.of() : excluded;

        // 按文章聚合多路命中。用 LinkedHashMap 保留首次出现的顺序，
        // 便于在分数并列时得到稳定结果（最终排序仍显式按 articleId 兜底）。
        Map<Long, List<RecalledArticle>> hitsByArticle = new LinkedHashMap<>();
        for (RecalledArticle hit : recalled) {
            if (hit == null || hit.articleId() == null || excludedIds.contains(hit.articleId())) {
                continue;
            }
            hitsByArticle.computeIfAbsent(hit.articleId(), key -> new ArrayList<>()).add(hit);
        }
        if (hitsByArticle.isEmpty()) {
            return List.of();
        }

        List<RecommendCandidate> candidates = new ArrayList<>(hitsByArticle.size());
        for (Map.Entry<Long, List<RecalledArticle>> entry : hitsByArticle.entrySet()) {
            double score = 0d;
            Set<String> sources = new LinkedHashSet<>();
            StringBuilder detail = new StringBuilder("{");
            for (RecalledArticle hit : entry.getValue()) {
                // rank 从 1 开始，因此 k + rank 至少为 k + 1，不会出现除零
                score += 1d / (rrfK + hit.rankInSource());
                String code = hit.source().getCode();
                if (sources.add(code)) {
                    if (detail.length() > 1) {
                        detail.append(',');
                    }
                    detail.append('"').append(code).append("\":").append(hit.rankInSource());
                }
            }
            detail.append('}');
            candidates.add(new RecommendCandidate(entry.getKey(), score, List.copyOf(sources),
                    detail.toString(), 0));
        }

        candidates.sort(Comparator.comparingDouble(RecommendCandidate::score).reversed()
                .thenComparing(RecommendCandidate::articleId));

        int limit = Math.min(topN, candidates.size());
        List<RecommendCandidate> ranked = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            RecommendCandidate candidate = candidates.get(i);
            ranked.add(new RecommendCandidate(candidate.articleId(), candidate.score(),
                    candidate.sources(), candidate.scoreDetail(), i + 1));
        }
        return ranked;
    }
}

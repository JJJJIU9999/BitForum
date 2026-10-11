package com.bitforum.ai.recommend;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bitforum.ai.agent.AgentType;
import com.bitforum.ai.entity.AiExecutionTrace;
import com.bitforum.ai.entity.AiRecommendLog;
import com.bitforum.ai.mapper.AiRecommendLogMapper;
import com.bitforum.ai.recommend.RecommendFusionService.RecommendCandidate;
import com.bitforum.ai.recommend.RecommendRecallService.RecallRequest;
import com.bitforum.ai.recommend.RecommendRecallService.RecalledArticle;
import com.bitforum.ai.trace.TraceContext;
import com.bitforum.ai.trace.TraceDegradeReason;
import com.bitforum.ai.trace.TraceRecorder;
import com.bitforum.ai.trace.TraceSession;
import com.bitforum.ai.trace.TraceStepType;
import com.bitforum.ai.usage.AiTokenBudgetGuard;
import com.bitforum.entity.Article;
import com.bitforum.entity.ArticleFavorite;
import com.bitforum.entity.Category;
import com.bitforum.mapper.ArticleFavoriteMapper;
import com.bitforum.mapper.ArticleMapper;
import com.bitforum.mapper.CategoryMapper;

/**
 * 推荐编排（M17）：召回 → 融合 → 补全展示信息 → 落库。
 *
 * <p><b>本类刻意不包含"推荐理由"</b>：理由由模型生成，而"最终列表由谁定、理由由谁写"
 * 是 M17 尚未决策的事项（见 m17-decision-brief.md 的 Q1）。当前实现的是
 * <b>确定性推荐 + 降级形态</b>：只用三路召回与 RRF 排序给出列表，不带理由。
 * 这不是临时凑合 —— handoff 的硬约束第 9 条要求"AI 不可用不能影响论坛主流程"，
 * 所以"没有理由的推荐列表"本来就必须是一条能独立工作的路径。
 *
 * <p><b>排除集的设计</b>：
 * <ul>
 *   <li><b>自己写的文章</b>：从 {@code article.user_id} 反查，可行；</li>
 *   <li><b>已收藏的文章</b>：从 {@code article_favorite(user_id, article_id)} 反查，
 *       该表有 {@code idx_article_favorite_user_create_time} 索引，可行；</li>
 *   <li><b>已点赞的文章：当前数据结构下不可行</b>。M6 的点赞只存在 Redis Set
 *       {@code article:{id}:likes}（文章 → 用户集合），**没有**"用户 → 文章集合"的反向索引。
 *       要反查只能 SCAN 整个 keyspace 再逐个 SISMEMBER，成本随文章数线性增长，
 *       不适合放在每次推荐请求里。因此"是否排除已点赞"这一项在 M17 不实现，
 *       若产品上必须要，需要先给点赞加一份反向索引（属于改动 M6 数据模型，另议）。</li>
 * </ul>
 * 两个开关（exclude-own / exclude-favorited）是可配置的：产品策略若与默认值不同，
 * 改配置即可，不需要改代码。
 *
 * <p><b>每次推荐都会写 {@code ai_recommend_log}</b>：这张表是 M17 验收
 * （"命中率与纯热榜基线对比"）的数据来源。落库失败只记日志，
 * 绝不影响推荐结果返回 —— 记录是副产品，不是主流程。
 */
@Service
public class RecommendService {

    private static final Logger log = LoggerFactory.getLogger(RecommendService.class);

    /**
     * 一次推荐请求。
     *
     * @param scene           场景，取值见 {@link AiRecommendLog} 的 SCENE_* 常量
     * @param userId          当前用户；未登录为 null（此时不构造个人排除集、不召回关注路）
     * @param sourceArticleId 来源文章；为 null 时不做内容相似召回
     * @param topN            期望返回条数
     * @param experimentTag   实验批次标识；线上真实请求传 null
     */
    public record RecommendRequest(String scene, Long userId, Long sourceArticleId,
                                   int topN, String experimentTag, String queryText) {

        /** 兼容不带自由文本查询的旧调用（详情页相关推荐、离线评测）。 */
        public RecommendRequest(String scene, Long userId, Long sourceArticleId,
                               int topN, String experimentTag) {
            this(scene, userId, sourceArticleId, topN, experimentTag, null);
        }

        public static RecommendRequest forArticleDetail(Long userId, Long sourceArticleId, int topN) {
            return new RecommendRequest(AiRecommendLog.SCENE_ARTICLE_DETAIL, userId, sourceArticleId, topN, null);
        }

        /**
         * 用一段自由文本做推荐（AI 助手场景："根据你问的问题推荐相关帖"）。
         *
         * <p>与详情页不同，这里既没有来源文章、也不能把问题当成文章 id，
         * 因此向量通道改用 {@code queryText} 直接检索。
         */
        public static RecommendRequest forQuery(String queryText, Long userId, int topN) {
            return new RecommendRequest(AiRecommendLog.SCENE_ASSISTANT, userId, null, topN, null, queryText);
        }
    }

    /**
     * 推荐给前端的一篇文章。
     *
     * @param reason 推荐理由；当前恒为 null（理由生成待 Q1 决策），前端需要能处理"没有理由"
     */
    public record RecommendedArticle(Long articleId, String title, Long categoryId, String categoryName,
                                     int rank, double score, List<String> sources, String scoreDetail,
                                     String reason) {
    }

    /**
     * 一次推荐的结果。
     *
     * @param degraded    是否走了降级链路。当前恒为 true（不含模型生成的理由）
     * @param recallCount 三路召回去重前的候选总数，便于排查"为什么没推荐出东西"
     */
    public record RecommendResult(List<RecommendedArticle> articles, boolean degraded,
                                  String model, long latencyMillis, int recallCount) {

        public boolean isEmpty() {
            return articles.isEmpty();
        }
    }

    private final RecommendRecallService recallService;
    private final RecommendFusionService fusionService;
    private final RecommendReasonAgent reasonAgent;
    private final ArticleMapper articleMapper;
    private final ArticleFavoriteMapper articleFavoriteMapper;
    private final CategoryMapper categoryMapper;
    private final AiRecommendLogMapper recommendLogMapper;

    /** 单次推荐最多取几篇"最近收藏"作为向量查询的来源（召回层还会再限制实际使用篇数）。 */
    private static final int MAX_INTEREST_ARTICLES = 10;

    private final String modelName;
    private final int recallTopK;
    private final boolean excludeOwn;
    private final boolean excludeFavorited;
    private final boolean reasonEnabled;
    private final TraceRecorder traceRecorder;
    private final AiTokenBudgetGuard budgetGuard;

    public RecommendService(RecommendRecallService recallService,
                            RecommendFusionService fusionService,
                            RecommendReasonAgent reasonAgent,
                            ArticleMapper articleMapper,
                            ArticleFavoriteMapper articleFavoriteMapper,
                            CategoryMapper categoryMapper,
                            AiRecommendLogMapper recommendLogMapper,
                            @Value("${bitforum.ai.recommend.model-name:deepseek-flash}") String modelName,
                            @Value("${bitforum.ai.recommend.recall-top-k:20}") int recallTopK,
                            @Value("${bitforum.ai.recommend.exclude-own:true}") boolean excludeOwn,
                            @Value("${bitforum.ai.recommend.exclude-favorited:true}") boolean excludeFavorited,
                            @Value("${bitforum.ai.recommend.reason-enabled:true}") boolean reasonEnabled,
                            TraceRecorder traceRecorder,
                            AiTokenBudgetGuard budgetGuard) {
        this.recallService = recallService;
        this.fusionService = fusionService;
        this.reasonAgent = reasonAgent;
        this.articleMapper = articleMapper;
        this.articleFavoriteMapper = articleFavoriteMapper;
        this.categoryMapper = categoryMapper;
        this.recommendLogMapper = recommendLogMapper;
        this.modelName = modelName;
        this.recallTopK = Math.max(1, recallTopK);
        this.excludeOwn = excludeOwn;
        this.excludeFavorited = excludeFavorited;
        this.reasonEnabled = reasonEnabled;
        this.traceRecorder = traceRecorder;
        this.budgetGuard = budgetGuard;
    }

    /**
     * 生成推荐列表（三路召回 + RRF 融合）。
     *
     * <p>本方法不抛业务异常：候选为空是正常结果（文章库小、或都被排除掉了），
     * 而不是错误。调用方拿到空列表时按"暂无推荐"处理即可。
     */
    public RecommendResult recommend(RecommendRequest request) {
        return recommend(request, null, null);
    }

    /**
     * 带**排除集覆盖**的推荐，供离线评测使用（`m17-eval-protocol.md` §3.2）。
     *
     * <p>为什么评测必须能覆盖默认排除集：留一法要把用户的一条收藏当作
     * "用户下一步会收藏的文章"，因此那一篇**必须留在候选池里**。
     * 而生产策略是"排除已收藏" —— 评测若不能覆盖它，目标文章会被排除掉、命中率恒为 0。
     * 这正是规范里专门写清的那个自相矛盾。
     *
     * @param exclusionOverride 为 null 时使用默认策略（作者本人 + 已收藏）；
     *                          评测传入"除目标之外的收藏 + 作者本人"
     */
    public RecommendResult recommend(RecommendRequest request, Set<Long> exclusionOverride) {
        return recommend(request, exclusionOverride, null);
    }

    /**
     * 完整签名的推荐，供离线评测同时覆盖**排除集**与**兴趣画像**。
     *
     * <p>两者必须都能被外部指定：留一法既要把目标文章留在候选池里（覆盖排除集），
     * 又要保证它**不被当作已知兴趣**参与向量查询 —— 否则等于把答案提前告诉算法，
     * 命中率会虚高（这是推荐评测里最容易出的泄漏）。
     *
     * @param exclusionOverride 为 null 时用默认策略（作者本人 + 已收藏）
     * @param interestOverride  为 null 时用默认来源（用户最近的收藏）
     */
    public RecommendResult recommend(RecommendRequest request, Set<Long> exclusionOverride,
                                     List<Long> interestOverride) {
        long startedAt = System.currentTimeMillis();
        int topN = Math.max(1, request.topN());

        // M18：推荐链路也留下执行轨迹 —— 排序虽然完全由 Java 完成（M17 决定），
        // 但"为什么是这 10 篇"恰恰是最需要被解释的部分。
        // 只在"会调用模型写理由"时才开轨迹：离线评测（reason-enabled=false）会产生
        // 成千上万次推荐，那些记录没有解释价值，只会把轨迹表灌满噪音。
        TraceSession session = null;
        if (reasonEnabled) {
            session = traceRecorder.start(AiExecutionTrace.SCENE_RECOMMEND, AgentType.RECOMMEND.name(),
                    request.userId(), null, AiExecutionTrace.REF_SOURCE_ARTICLE,
                    request.sourceArticleId());
        }

        try {
            Set<Long> excluded = exclusionOverride != null
                    ? exclusionOverride
                    : buildExclusionSet(request.userId());
            List<Long> interests = interestOverride != null
                    ? interestOverride
                    : buildInterestArticleIds(request.userId());

            // 三路召回 + RRF 融合（来源文章自身由召回层强制排除，这里不必重复）
            boolean hasQuery = request.queryText() != null && !request.queryText().isBlank();
            // 有明确提问时**不再混入收藏画像**：用户刚说出的意图才是最强的相关性信号。
            // 实测教训：两者混用时，收藏多的主题会淹没当前问题（问"Redis 缓存穿透"却推出一屏 MySQL）。
            RecallRequest recallRequest = hasQuery
                    ? RecallRequest.forQuery(request.queryText(), request.userId(), recallTopK, excluded, List.of())
                    : RecallRequest.of(request.userId(), request.sourceArticleId(), recallTopK, excluded, interests);
            List<RecalledArticle> recalled = recallService.recall(recallRequest);

            if (hasQuery) {
                // "根据提问推荐"**必须以相关性为前提**：候选只保留向量通道（即提问）命中的文章，
                // 热度与关注只影响它们的排序，不再引入与提问无关的候选。
                // 实测教训：不做这层约束时，关注与热度会把一屏 MySQL 文章推到"Redis 问题"前面 ——
                // 从"多路命中优先"的角度无可厚非，但对"根据你问的问题推荐"来说就是答非所问。
                Set<Long> relevant = recalled.stream()
                        .filter(item -> item.source() == RecommendRecallService.RecallSource.VECTOR)
                        .map(RecalledArticle::articleId)
                        .collect(java.util.stream.Collectors.toSet());
                recalled = recalled.stream()
                        .filter(item -> relevant.contains(item.articleId()))
                        .toList();
            }
            List<RecommendCandidate> fused = fusionService.fuse(recalled, excluded, topN);
            traceRecorder.step(TraceStepType.RECALL, "三路召回",
                    "向量/热度/关注共召回 " + recalled.size() + " 条候选（去重前）");
            traceRecorder.step(TraceStepType.FUSION, "RRF 融合与截断",
                    "融合后取 Top-" + topN + "，实际 " + fused.size() + " 条");

            return assemble(request, fused, recalled.size(), request.experimentTag(), startedAt);
        } catch (RuntimeException exception) {
            // M2：推荐内部异常仍按既有契约向上抛出（调用方负责"失败不影响文章页"），
            // 但轨迹必须收尾 —— 否则 ai_execution_trace 里会留下永远不会结束的 RUNNING 行，
            // 管理端看到的"卡住的调用"其实是一次已经失败的推荐。
            // 异常详情只写服务端日志；轨迹只记异常类型，不落库内部消息。
            log.error("推荐链路异常（对外契约仍由调用方兜底）：scene={}，userId={}",
                    request.scene(), request.userId(), exception);
            traceRecorder.finishFailed("推荐链路异常：" + exception.getClass().getSimpleName());
            throw exception;
        } finally {
            // M2：线程池复用线程上不得残留 TraceContext。
            // 正常路径的收尾（finish）内部已经清理过，这里只做兜底；clear 只清 ThreadLocal、
            // 不再写库，因此不会出现"收尾两次"。
            if (session != null) {
                TraceSession current = TraceContext.current();
                if (current != null && session.traceId().equals(current.traceId())) {
                    traceRecorder.clear();
                }
            }
        }
    }

    /**
     * 用户兴趣文章：最近收藏的若干篇，供向量通道做"内容画像"查询。
     *
     * <p>收藏是当前唯一能表达"兴趣"的显式行为 —— 点赞只有"文章 → 用户集合"的方向，
     * 无法反查（findings.md 6.16）。未登录访客没有个人数据，返回空列表，
     * 这正是匿名推荐退化为"内容相似 + 热度"两路的原因。
     *
     * <p>只取最近若干篇：每一篇都要跑一次嵌入与向量检索，不限制会让延迟随收藏数增长。
     */
    private List<Long> buildInterestArticleIds(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return articleFavoriteMapper.selectList(new LambdaQueryWrapper<ArticleFavorite>()
                        .eq(ArticleFavorite::getUserId, userId)
                        .orderByDesc(ArticleFavorite::getId)
                        .last("LIMIT " + MAX_INTEREST_ARTICLES))
                .stream()
                .map(ArticleFavorite::getArticleId)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * 纯热榜基线：不融合、不个性化，只把热榜上的文章按热度推出去。
     *
     * <p>它的存在是为了 M17 的验收 ——「命中率与纯热榜基线对比」。基线有三点刻意与推荐系统对齐，
     * 否则对比会失真：
     * <ol>
     *   <li><b>共用同一份排除集</b>：如果基线能把用户已收藏的文章推出来，它会凭空多出命中；</li>
     *   <li><b>共用同一套状态校验</b>（通过复用召回层的 HOT 通道，而不是自己再写一遍查询）；</li>
     *   <li><b>共用同一套落库逻辑</b>，只有 {@code experimentTag} 不同，
     *       评测时按批次分组统计即可。</li>
     * </ol>
     *
     * @param experimentTag 实验批次标识，会写入 {@code ai_recommend_log.experiment_tag}
     */
    public RecommendResult hotBaseline(RecommendRequest request, String experimentTag) {
        return hotBaseline(request, experimentTag, null);
    }

    /**
     * 带排除集覆盖的基线，供离线评测使用 —— 与 {@link #recommend(RecommendRequest, Set)}
     * 用同一份排除集，才能保证"推荐 vs 基线"的对比只差排序方式。
     */
    public RecommendResult hotBaseline(RecommendRequest request, String experimentTag,
                                       Set<Long> exclusionOverride) {
        long startedAt = System.currentTimeMillis();
        int topN = Math.max(1, request.topN());
        Set<Long> excluded = exclusionOverride != null
                ? exclusionOverride
                : buildExclusionSet(request.userId());

        List<RecalledArticle> recalled = recallService.recall(RecallRequest.only(
                RecommendRecallService.RecallSource.HOT, request.userId(),
                request.sourceArticleId(), recallTopK, excluded));

        List<RecommendCandidate> ranked = new ArrayList<>();
        recalled.stream()
                .filter(item -> item.source() == RecommendRecallService.RecallSource.HOT)
                // 显式再排一次，不依赖召回层的返回顺序
                .sorted(Comparator.comparingDouble(RecalledArticle::rawScore).reversed()
                        .thenComparing(RecalledArticle::articleId))
                .limit(topN)
                .forEach(item -> ranked.add(new RecommendCandidate(
                        item.articleId(),
                        item.rawScore(),
                        List.of(RecommendRecallService.RecallSource.HOT.getCode()),
                        "{\"hot\":" + item.rankInSource() + "}",
                        ranked.size() + 1)));

        return assemble(request, ranked, recalled.size(), experimentTag, startedAt);
    }

    /**
     * 推荐与基线共用的后半段：补全展示信息 → 组装结果 → 落库。
     *
     * <p>抽出来是为了保证两条路径**只差排序方式**，其余环节完全一致 —— 这是对比能成立的前提。
     */
    private RecommendResult assemble(RecommendRequest request, List<RecommendCandidate> candidates,
                                     int recallCount, String experimentTag, long startedAt) {
        // 场景判断在这里重新算一次：措辞要区分"根据你的提问"与"根据你正在看的文章"
        boolean hasQuery = request.queryText() != null && !request.queryText().isBlank();
        if (candidates.isEmpty()) {
            log.info(">>> 推荐候选为空：scene={}，userId={}，来源文章={}，召回 {} 条（去重前），批次={}",
                    request.scene(), request.userId(), request.sourceArticleId(), recallCount, experimentTag);
            // M2：空候选是**正常结果**（文章库小或都被排除），不是失败，但轨迹不能停在 RUNNING ——
            // 这里按正常结束收尾。没有理由生成，因此 token 一律为 null。
            // hotBaseline 未开启轨迹时这次调用是 no-op（当前线程没有 TraceContext）。
            traceRecorder.finish(modelName, null, null, null);
            return new RecommendResult(List.of(), true, modelName,
                    System.currentTimeMillis() - startedAt, recallCount);
        }

        // 补全展示信息（标题、板块名）。召回阶段已校验过状态，理论上都能查到。
        Map<Long, Article> articles = loadArticles(candidates);
        Map<Long, String> categoryNames = loadCategoryNames(articles.values());

        List<RecommendedArticle> result = new ArrayList<>(candidates.size());
        for (RecommendCandidate candidate : candidates) {
            Article article = articles.get(candidate.articleId());
            if (article == null) {
                // 防御：状态在这两步之间被改动（例如刚被下架）
                log.debug("推荐候选在补全阶段已不可用：articleId={}", candidate.articleId());
                continue;
            }
            result.add(new RecommendedArticle(
                    article.getId(),
                    article.getTitle(),
                    article.getCategoryId(),
                    categoryNames.get(article.getCategoryId()),
                    // 过滤掉不可用文章后重新编号，避免前端看到 1、3、4 这样的空档
                    result.size() + 1,
                    candidate.score(),
                    candidate.sources(),
                    candidate.scoreDetail(),
                    null));
        }

        // 生成推荐理由：模型的输出只是"解释"，**不参与排序**（M17 实施决策）。
        // 失败时列表照常返回，只是没有理由 —— 这条降级路径是生产环境的必需能力。
        RecommendReasonAgent.ReasonOutcome reasonOutcome = generateReasons(request, result, hasQuery);
        List<RecommendedArticle> withReasons = applyReasons(result, reasonOutcome);
        traceRecorder.step(TraceStepType.LLM_CALL, reasonOutcome.model(),
                "为 " + reasonOutcome.reasonsByArticleId().size() + " 条推荐生成理由",
                reasonOutcome.latencyMillis(), reasonOutcome.totalTokens());
        // 降级原因由 RecommendReasonAgent 内部的 AiDegradeGuard 统一记录（M18 模块 3），
        // 这里不再重复记 —— 降级记录的入口收敛到 Guard 一处

        long latency = System.currentTimeMillis() - startedAt;
        boolean degraded = reasonOutcome.degraded();

        saveRecommendLog(request, withReasons, degraded, (int) latency, experimentTag);
        traceRecorder.step(TraceStepType.PERSIST, "推荐记录落库",
                "已写入 ai_recommend_log " + withReasons.size() + " 行（批次=" + experimentTag + "）");
        traceRecorder.finish(modelName, reasonOutcome.promptTokens(), reasonOutcome.completionTokens(),
                reasonOutcome.totalTokens());

        log.info(">>> 推荐完成：scene={}，userId={}，来源文章={}，返回 {} 条（召回 {} 条），"
                        + "理由 {}/{} 条，批次={}，耗时 {} ms",
                request.scene(), request.userId(), request.sourceArticleId(),
                withReasons.size(), recallCount, reasonOutcome.reasonsByArticleId().size(),
                withReasons.size(), experimentTag, latency);

        return new RecommendResult(withReasons, degraded, modelName, latency, recallCount);
    }

    /**
     * 生成推荐理由。
     *
     * <p>{@code bitforum.ai.recommend.reason-enabled=false} 时不调用模型：
     * **离线评测必须关掉它**，否则每次推荐都会消耗额度、拖慢评测，
     * 而评测关心的排序结果与理由无关。
     */
    private RecommendReasonAgent.ReasonOutcome generateReasons(RecommendRequest request,
                                                               List<RecommendedArticle> articles,
                                                               boolean queryBased) {
        if (!reasonEnabled) {
            // 配置关闭属于"AI 能力未启用"，用统一原因码而不是自造一句话
            return RecommendReasonAgent.ReasonOutcome.degraded(
                    TraceDegradeReason.AI_DISABLED, modelName, 0L,
                    "推荐理由生成已关闭（bitforum.ai.recommend.reason-enabled=false）");
        }

        // S2：匿名访客不调用模型生成理由。
        // `/api/article/recommendations` 是公开接口，而预算闸门对 userId=null 直接放行
        // （匿名没有可归属的用量），因此匿名请求可以零成本地反复触发模型调用。
        // 匿名仍拿到完整的 Java 确定性排序结果，只是没有理由 —— 这正是既有的降级语义。
        if (request.userId() == null) {
            // 复审返工：这里必须**显式标记降级**，否则后续 traceRecorder.finish(...) 会把
            // 执行轨迹写成 SUCCESS 且 degrade_reason 为空 —— 接口结果（degraded=true）、
            // 推荐日志（degraded=true）与执行轨迹（SUCCESS）三者互相矛盾。
            // 与上面的预算分支保持同一写法：先 degrade，再返回降级结果。
            traceRecorder.degrade(TraceDegradeReason.ANONYMOUS_NO_REASON,
                    "匿名访客不生成推荐理由（推荐列表仍可用）");
            return RecommendReasonAgent.ReasonOutcome.degraded(
                    TraceDegradeReason.ANONYMOUS_NO_REASON, modelName, 0L,
                    "匿名访客不生成推荐理由（推荐列表仍可用）");
        }

        // M18 收尾：单用户每日预算闸门。
        // 推荐是"详情页每次浏览都可能触发一次模型调用"的场景，最容易被无感知地刷；
        // 超预算时退化为**无理由推荐** —— 排序由 Java 完成，列表完全不受影响，
        // 这正是 M17 就设计好的那条降级路径，不需要为预算再造一条。
        AiTokenBudgetGuard.BudgetDecision decision = budgetGuard.check(request.userId());
        if (!decision.allowed()) {
            traceRecorder.degrade(decision.reason(), decision.detail());
            return RecommendReasonAgent.ReasonOutcome.degraded(decision.reason(), modelName, 0L,
                    decision.message());
        }
        return reasonAgent.generate(articles, describeUser(request.userId()), queryBased);
    }

    /** 把理由回填进推荐列表；降级时原样返回（reason 保持 null）。 */
    private List<RecommendedArticle> applyReasons(List<RecommendedArticle> articles,
                                                  RecommendReasonAgent.ReasonOutcome outcome) {
        if (outcome.degraded() || outcome.reasonsByArticleId().isEmpty()) {
            return List.copyOf(articles);
        }
        List<RecommendedArticle> withReasons = new ArrayList<>(articles.size());
        for (RecommendedArticle article : articles) {
            withReasons.add(new RecommendedArticle(
                    article.articleId(), article.title(), article.categoryId(), article.categoryName(),
                    article.rank(), article.score(), article.sources(), article.scoreDetail(),
                    outcome.reasonFor(article.articleId())));
        }
        return List.copyOf(withReasons);
    }

    /**
     * 给模型的用户情况描述。
     *
     * <p>刻意只给"有没有个性化数据"这一句摘要，而不是具体收藏清单：
     * 既不泄漏用户的具体行为，也避免把上下文浪费在罗列标题上。
     */
    private String describeUser(Long userId) {
        return userId == null
                ? "未登录访客，没有个人行为数据（只会有内容相似与社区热度两类依据）"
                : "已登录用户，系统已结合其收藏与关注关系做个性化（可能出现\"来自你关注的作者\"这类依据）";
    }

    /**
     * 构造"不该再推荐给该用户"的文章集合。
     *
     * <p>只查 id 列，避免把正文也捞出来 —— 这个查询在每次推荐时都会执行。
     */
    private Set<Long> buildExclusionSet(Long userId) {
        if (userId == null) {
            return Set.of();
        }
        Set<Long> excluded = new LinkedHashSet<>();

        if (excludeOwn) {
            articleMapper.selectList(new LambdaQueryWrapper<Article>()
                            .select(Article::getId)
                            .eq(Article::getUserId, userId))
                    .stream()
                    .map(Article::getId)
                    .filter(java.util.Objects::nonNull)
                    .forEach(excluded::add);
        }

        if (excludeFavorited) {
            articleFavoriteMapper.selectList(new LambdaQueryWrapper<ArticleFavorite>()
                            .eq(ArticleFavorite::getUserId, userId))
                    .stream()
                    .map(ArticleFavorite::getArticleId)
                    .filter(java.util.Objects::nonNull)
                    .forEach(excluded::add);
        }

        if (!excluded.isEmpty()) {
            log.debug("推荐排除集：userId={}，共 {} 篇（自己写的 / 已收藏的）", userId, excluded.size());
        }
        return excluded;
    }

    private Map<Long, Article> loadArticles(List<RecommendCandidate> fused) {
        List<Long> ids = fused.stream().map(RecommendCandidate::articleId).toList();
        return articleMapper.selectList(new LambdaQueryWrapper<Article>().in(Article::getId, ids))
                .stream()
                .collect(Collectors.toMap(Article::getId, article -> article, (left, right) -> left,
                        LinkedHashMap::new));
    }

    /** 批量取板块名：推荐卡片要显示"技术 / 前端"这类归属，避免前端再查一遍。 */
    private Map<Long, String> loadCategoryNames(java.util.Collection<Article> articles) {
        Set<Long> categoryIds = articles.stream()
                .map(Article::getCategoryId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        if (categoryIds.isEmpty()) {
            return Map.of();
        }
        return categoryMapper.selectList(new LambdaQueryWrapper<Category>().in(Category::getId, categoryIds))
                .stream()
                .collect(Collectors.toMap(Category::getId, Category::getName, (left, right) -> left));
    }

    /**
     * 写入推荐记录（评测数据来源）。
     *
     * <p>失败只记日志：推荐结果已经算好了，不能因为记录写不进去就让请求失败。
     */
    private void saveRecommendLog(RecommendRequest request, List<RecommendedArticle> articles,
                                  boolean degraded, int latencyMillis, String experimentTag) {
        if (articles.isEmpty()) {
            return;
        }
        try {
            for (RecommendedArticle article : articles) {
                AiRecommendLog record = new AiRecommendLog();
                record.setScene(request.scene());
                record.setUserId(request.userId());
                record.setSourceArticleId(request.sourceArticleId());
                record.setArticleId(article.articleId());
                record.setRankNo(article.rank());
                record.setScore(BigDecimal.valueOf(article.score()));
                record.setRecallSources(String.join(",", article.sources()));
                record.setScoreDetail(article.scoreDetail());
                record.setReason(article.reason());
                record.setExperimentTag(experimentTag);
                record.setModel(modelName);
                record.setLatencyMs(latencyMillis);
                record.setDegraded(degraded);
                recommendLogMapper.insert(record);
            }
        } catch (RuntimeException exception) {
            log.error("推荐记录落库失败（不影响本次推荐返回）：scene={}，userId={}，批次={}",
                    request.scene(), request.userId(), experimentTag, exception);
        }
    }
}

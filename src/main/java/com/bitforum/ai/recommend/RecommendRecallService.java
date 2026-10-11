package com.bitforum.ai.recommend;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bitforum.entity.Article;
import com.bitforum.entity.UserFollow;
import com.bitforum.mapper.ArticleMapper;
import com.bitforum.mapper.UserFollowMapper;
import com.bitforum.service.ArticleService;
import com.bitforum.service.RedisService;

/**
 * 三路召回（M17）。
 *
 * <p>推荐的第一阶段：只负责"把可能相关的文章捞出来"，不做排序、不做过滤决策。
 * 排序交给 {@link RecommendFusionService}，是否排除"已读过的"由调用方通过
 * {@link RecallRequest#excludedArticleIds()} 决定 —— 召回层不预设产品策略。
 *
 * <p><b>三路各自的角色</b>：
 * <ol>
 *   <li><b>vector 内容相似</b>：复用 M15 的向量库与同一个索引（{@code bitforum-kb}），
 *       以来源文章的标题 + 正文摘要作为查询；</li>
 *   <li><b>hot 近期热度</b>：复用 M6 的热榜 ZSet（浏览 +1、点赞 +3 累积）；</li>
 *   <li><b>follow 关注关系</b>：复用 M9 的 {@code user_follow}，取当前用户关注作者的文章。</li>
 * </ol>
 *
 * <p><b>为什么不直接调用 {@code RagService}</b>：RagService 的 {@code topK} 与相似度下限
 * 是为问答检索调优的（问答只需要 3~5 段最相关的片段）。推荐需要更大的候选池，
 * 且 T11 实测（findings.md 6.15）表明文章之间的相似度都挤在 0.66~0.78，
 * 用一个为问答定的阈值去截断推荐候选并不合适。因此这里复用**同一套向量库与嵌入模型**，
 * 但用推荐自己的参数，避免改动 M15 已经定型的问答链路。
 *
 * <p><b>召回结果可能含重复 articleId</b>：同一篇文章被两路命中是正常的（也是好信号），
 * 由融合层合并计分；同一路内会按文章去重，只保留排名最靠前的一次。
 * 所有召回结果最后都会经过"文章当前必须是 PUBLISHED"的校验 —— 热榜与向量索引里
 * 都可能残留已下架文章的数据。
 */
@Service
public class RecommendRecallService {

    private static final Logger log = LoggerFactory.getLogger(RecommendRecallService.class);

    /** 召回通道。{@code code} 会写进 {@code ai_recommend_log.recall_sources}，取值需保持稳定。 */
    public enum RecallSource {
        VECTOR("vector", "内容相似"),
        HOT("hot", "近期热度"),
        FOLLOW("follow", "你关注的作者");

        private final String code;
        private final String displayName;

        RecallSource(String code, String displayName) {
            this.code = code;
            this.displayName = displayName;
        }

        public String getCode() {
            return code;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /**
     * 一路召回命中的一篇文章。
     *
     * @param articleId    文章 id
     * @param source       来自哪一路
     * @param rankInSource 在该路结果内的排名，从 1 开始（融合时按排名计分，不用原始分数）
     * @param rawScore     该路的原始信号，仅供排查与展示：向量是相似度，热度是 ZSet 分值，
     *                     关注路是"该文章的发布时间序号"（越新越靠前）
     */
    public record RecalledArticle(Long articleId, RecallSource source, int rankInSource, double rawScore) {
    }

    /**
     * 一次召回请求。
     *
     * @param userId             当前用户；为 null 表示未登录访客（此时不召回"关注"这一路）
     * @param sourceArticleId    来源文章（"因为你在看这篇"）；为 null 时不做内容相似召回
     * @param topKPerSource      每一路各取多少条
     * @param excludedArticleIds 排除集（例如"已收藏过的"）。来源文章自身**始终**被排除，
     *                           不需要调用方重复传入 —— 不排除会把当前文章推荐给它自己
     * @param sources            本次要走哪几路。默认三路全走；评测的"纯热榜基线"只走 HOT 一路，
     *                           用它可以在**复用同一套状态校验**的前提下拿到纯热榜候选，
     *                           不必为基线另写一份"文章是否还在架上"的判断
     */
    public record RecallRequest(Long userId, Long sourceArticleId, int topKPerSource,
                                Set<Long> excludedArticleIds, Set<RecallSource> sources,
                                List<Long> interestArticleIds, String queryText) {

        /** 三路全走（推荐系统使用）。 */
        public static RecallRequest of(Long userId, Long sourceArticleId, int topKPerSource) {
            return new RecallRequest(userId, sourceArticleId, topKPerSource, Set.of(),
                    Set.of(RecallSource.values()), List.of(), null);
        }

        public static RecallRequest of(Long userId, Long sourceArticleId, int topKPerSource,
                                       Set<Long> excludedArticleIds) {
            return new RecallRequest(userId, sourceArticleId, topKPerSource, excludedArticleIds,
                    Set.of(RecallSource.values()), List.of(), null);
        }

        /**
         * 三路全走，并带上"用户兴趣文章"。
         *
         * <p><b>为什么需要它</b>：用户维度的推荐（"给你推荐"）**没有来源文章**，
         * 向量通道只有靠"用户收藏过的文章"作为查询才能工作。否则这一路永远不会被触发 ——
         * 开发集评测就曾因此出现「vector 通道零贡献、效果全部来自关注与热度」。
         */
        public static RecallRequest of(Long userId, Long sourceArticleId, int topKPerSource,
                                       Set<Long> excludedArticleIds, List<Long> interestArticleIds) {
            return new RecallRequest(userId, sourceArticleId, topKPerSource, excludedArticleIds,
                    Set.of(RecallSource.values()),
                    interestArticleIds == null ? List.of() : interestArticleIds, null);
        }

        /**
         * 只走指定通道。
         *
         * <p>用于评测：纯热榜基线只要 HOT 一路，且应当与推荐系统共用同一份排除集 ——
         * 否则基线会"因为能推荐用户已收藏的文章"而凭空多出命中，对比就不公平了。
         */
        public static RecallRequest only(RecallSource source, Long userId, Long sourceArticleId,
                                         int topKPerSource, Set<Long> excludedArticleIds) {
            return new RecallRequest(userId, sourceArticleId, topKPerSource, excludedArticleIds,
                    Set.of(source), List.of(), null);
        }

        /**
         * 用一段**自由文本**做内容相似召回（AI 助手场景："根据你问的问题推荐相关帖"）。
         *
         * <p>AI 助手没有"来源文章"，用户输入的是问题而不是文章 id，
         * 因此向量通道需要一个文本查询入口。文本查询与文章查询可以同时存在，结果按最高相似度合并。
         */
        public static RecallRequest forQuery(String queryText, Long userId, int topKPerSource,
                                             Set<Long> excludedArticleIds, List<Long> interestArticleIds) {
            return new RecallRequest(userId, null, topKPerSource,
                    excludedArticleIds == null ? Set.of() : excludedArticleIds,
                    Set.of(RecallSource.values()),
                    interestArticleIds == null ? List.of() : interestArticleIds,
                    queryText == null ? null : queryText.trim());
        }

        public boolean includes(RecallSource source) {
            return sources != null && sources.contains(source);
        }
    }

    /** 用于构造向量查询的正文截断长度：推荐只需要"这篇讲什么"，不需要全文。 */
    private static final int QUERY_CONTENT_CHARS = 200;

    /**
     * 单次推荐最多用几篇"用户兴趣文章"做向量查询。
     * 每个查询都要跑一次嵌入与检索，不限制会让延迟随收藏数增长。
     */
    private static final int MAX_INTEREST_QUERIES = 5;

    private final VectorStore vectorStore;
    private final RedisService redisService;
    private final ArticleService articleService;
    private final ArticleMapper articleMapper;
    private final UserFollowMapper userFollowMapper;
    private final String queryPrefix;

    public RecommendRecallService(VectorStore vectorStore,
                                  RedisService redisService,
                                  ArticleService articleService,
                                  ArticleMapper articleMapper,
                                  UserFollowMapper userFollowMapper,
                                  @Value("${bitforum.ai.rag.query-prefix:}") String queryPrefix) {
        this.vectorStore = vectorStore;
        this.redisService = redisService;
        this.articleService = articleService;
        this.articleMapper = articleMapper;
        this.userFollowMapper = userFollowMapper;
        this.queryPrefix = queryPrefix == null ? "" : queryPrefix;
    }

    /**
     * 执行三路召回。
     *
     * <p>任何一路失败都不影响其它两路：推荐是增强能力，缺一路只是候选少一些，
     * 不应该让整个推荐接口报错（与"AI 不可用不能影响论坛主流程"同一原则）。
     */
    public List<RecalledArticle> recall(RecallRequest request) {
        Set<Long> excluded = new LinkedHashSet<>();
        if (request.excludedArticleIds() != null) {
            excluded.addAll(request.excludedArticleIds());
        }
        if (request.sourceArticleId() != null) {
            // 永远排除来源文章自身：T11 实测"自己找自己"的相似度恒为最高，
            // 不排除会出现"相关推荐里推的就是当前这篇"
            excluded.add(request.sourceArticleId());
        }

        int topK = Math.max(1, request.topKPerSource());
        List<RecalledArticle> recalled = new ArrayList<>();
        if (request.includes(RecallSource.VECTOR)) {
            recalled.addAll(safely("vector", () -> recallByVector(request, topK, excluded)));
        }
        if (request.includes(RecallSource.HOT)) {
            recalled.addAll(safely("hot", () -> recallByHot(topK, excluded)));
        }
        if (request.includes(RecallSource.FOLLOW)) {
            recalled.addAll(safely("follow", () -> recallByFollow(request.userId(), topK, excluded)));
        }

        log.info(">>> 推荐召回完成：通道={}，合计 {} 条（去重前），来源文章={}，用户={}",
                request.sources(), recalled.size(), request.sourceArticleId(), request.userId());
        return recalled;
    }

    /** 单路召回失败时降级为空列表，并记录原因。 */
    private List<RecalledArticle> safely(String source, java.util.function.Supplier<List<RecalledArticle>> action) {
        try {
            return action.get();
        } catch (RuntimeException exception) {
            log.warn("推荐召回通道 {} 失败，已跳过该路", source, exception);
            return List.of();
        }
    }

    /**
     * 内容相似召回：把「来源文章」与「用户兴趣文章」都作为查询去检索向量库，同一篇取最高相似度。
     *
     * <p>为什么必须支持多查询：只有文章详情页才有"来源文章"，
     * 用户维度的推荐（"给你推荐"）没有 —— 只认来源文章的话，登录用户的向量通道永远不会触发。
     * 用用户收藏过的文章做查询，才是真正意义上的"基于内容画像"的召回。
     *
     * <p>同一篇文章可能被切成多个片段、也可能被多个查询命中，这里都只保留最高相似度。
     */
    private List<RecalledArticle> recallByVector(RecallRequest request, int topK, Set<Long> excluded) {
        // 查询来源有两类：自由文本（AI 助手提问）与文章（详情页的"在看这篇"+ 用户兴趣画像）
        List<String> queries = new ArrayList<>();
        if (request.queryText() != null && !request.queryText().isBlank()) {
            queries.add(queryPrefix + request.queryText());
        }
        for (Article queryArticle : loadVectorQueries(request)) {
            queries.add(buildVectorQuery(queryArticle));
        }
        if (queries.isEmpty()) {
            return List.of();
        }

        Map<Long, Double> bestScoreByArticle = new LinkedHashMap<>();
        for (String query : queries) {
            List<Document> documents = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(query)
                    .topK(topK * 3)
                    // 不过滤相似度：T11 实测文章之间的相似度集中在很窄的区间内，
                    // 用为问答定的阈值去截断推荐候选并不合适；候选规模由 topK 控制。
                    .filterExpression("status == '" + ArticleService.STATUS_PUBLISHED + "'")
                    .build());
            if (documents == null) {
                continue;
            }
            for (Document document : documents) {
                Long articleId = parseArticleId(document);
                if (articleId == null || excluded.contains(articleId)) {
                    continue;
                }
                double score = document.getScore() == null ? 0d : document.getScore();
                bestScoreByArticle.merge(articleId, score, Math::max);
            }
        }
        if (bestScoreByArticle.isEmpty()) {
            return List.of();
        }

        List<Map.Entry<Long, Double>> ranked = new ArrayList<>(bestScoreByArticle.entrySet());
        ranked.sort(Map.Entry.<Long, Double>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey()));

        List<RecalledArticle> candidates = new ArrayList<>(ranked.size());
        for (int i = 0; i < ranked.size(); i++) {
            Map.Entry<Long, Double> entry = ranked.get(i);
            candidates.add(new RecalledArticle(entry.getKey(), RecallSource.VECTOR, i + 1, entry.getValue()));
        }
        // 与热榜/关注路一样做"文章当前状态"的二次校验：向量索引不是文章状态的权威来源，
        // 文章下架后如果索引还没重建，它的向量仍在库里（RagService 的"三重约束"同理）。
        // 注意这里保留原始的 rank 不加压缩：RRF 按**原始排名**计分才符合其定义。
        return keepPublished(candidates, topK);
    }

    /**
     * 组装向量查询文章：来源文章（文章详情页场景）+ 用户兴趣文章（个性化场景）。
     *
     * <p>兴趣文章数量有上限：每个查询都要跑一次嵌入与检索，
     * 不限制会让推荐延迟随收藏数增长。调用方按"最近收藏优先"传入。
     */
    private List<Article> loadVectorQueries(RecallRequest request) {
        List<Long> ids = new ArrayList<>();
        if (request.sourceArticleId() != null) {
            ids.add(request.sourceArticleId());
        }
        if (request.interestArticleIds() != null) {
            request.interestArticleIds().stream()
                    .filter(java.util.Objects::nonNull)
                    .filter(id -> !ids.contains(id))
                    .limit(MAX_INTEREST_QUERIES)
                    .forEach(ids::add);
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Article> queries = new ArrayList<>(ids.size());
        for (Long id : ids) {
            Article article = articleService.findPublishedById(id);
            if (article != null) {
                queries.add(article);
            }
        }
        return queries;
    }

    /** 热度召回：取热榜前 topK。ZSet 里可能残留已下架文章，由 {@link #keepPublished} 统一校验。 */
    private List<RecalledArticle> recallByHot(int topK, Set<Long> excluded) {
        Set<ZSetOperations.TypedTuple<String>> hotSet = redisService.getHotList(topK * 2);
        if (hotSet == null || hotSet.isEmpty()) {
            return List.of();
        }

        List<RecalledArticle> candidates = new ArrayList<>();
        int rank = 0;
        for (ZSetOperations.TypedTuple<String> tuple : hotSet) {
            if (tuple == null || tuple.getValue() == null) {
                continue;
            }
            Long articleId;
            try {
                articleId = Long.parseLong(tuple.getValue());
            } catch (NumberFormatException exception) {
                log.warn("热榜成员不是合法文章 id：{}", tuple.getValue());
                continue;
            }
            if (excluded.contains(articleId)) {
                continue;
            }
            rank++;
            candidates.add(new RecalledArticle(articleId, RecallSource.HOT, rank,
                    tuple.getScore() == null ? 0d : tuple.getScore()));
        }
        return keepPublished(candidates, topK);
    }

    /**
     * 关注召回：取当前用户关注作者发布的文章，按发布时间倒序。
     *
     * <p>未登录访客（userId 为 null）没有关注关系，这一路直接返回空 ——
     * 这也是未登录推荐退化为"内容相似 + 热度"两路的原因。
     */
    private List<RecalledArticle> recallByFollow(Long userId, int topK, Set<Long> excluded) {
        if (userId == null) {
            return List.of();
        }
        List<Long> authorIds = userFollowMapper.selectList(new LambdaQueryWrapper<UserFollow>()
                        .eq(UserFollow::getFollowerId, userId))
                .stream()
                .map(UserFollow::getFollowingId)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (authorIds.isEmpty()) {
            return List.of();
        }

        List<Article> articles = articleMapper.selectList(new LambdaQueryWrapper<Article>()
                .in(Article::getUserId, authorIds)
                .eq(Article::getStatus, ArticleService.STATUS_PUBLISHED)
                .orderByDesc(Article::getCreateTime)
                .last("LIMIT " + (topK * 2)));

        List<RecalledArticle> candidates = new ArrayList<>();
        int rank = 0;
        for (Article article : articles) {
            if (article.getId() == null || excluded.contains(article.getId())) {
                continue;
            }
            rank++;
            // 关注路的"原始分"用负的发布时间序号：数值越小表示越新，
            // 与其它两路"越大越好"的语义不同，仅用于排查展示，不参与融合计分。
            candidates.add(new RecalledArticle(article.getId(), RecallSource.FOLLOW, rank, -rank));
        }
        return keepPublished(candidates, topK);
    }

    /**
     * 统一校验文章**当前**状态必须是 PUBLISHED 后截断到 topK。
     *
     * <p>为什么每路都要校验：热榜的 ZSet 与向量索引都不是文章状态的权威来源。
     * 文章下架后，热榜成员与向量可能还残留；以 article 表为准做二次校验，
     * 与 RagService 的做法一致（见 findings.md 6.15 的"三重约束"）。
     */
    private List<RecalledArticle> keepPublished(List<RecalledArticle> candidates, int topK) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        Set<Long> ids = candidates.stream().map(RecalledArticle::articleId).collect(java.util.stream.Collectors.toSet());
        Set<Long> published = articleMapper.selectList(new LambdaQueryWrapper<Article>()
                        .in(Article::getId, ids)
                        .eq(Article::getStatus, ArticleService.STATUS_PUBLISHED))
                .stream()
                .map(Article::getId)
                .collect(java.util.stream.Collectors.toSet());

        List<RecalledArticle> result = new ArrayList<>();
        for (RecalledArticle candidate : candidates) {
            if (!published.contains(candidate.articleId())) {
                log.debug("召回结果被状态校验丢弃（非已发布）：articleId={}", candidate.articleId());
                continue;
            }
            result.add(candidate);
            if (result.size() >= topK) {
                break;
            }
        }
        return result;
    }

    /**
     * 构造向量查询文本。
     *
     * <p>用「标题 + 正文前 200 字」而不是只取标题：T11 实测发现正文过短时
     * 仅凭标题的文章在向量空间里挤成一团，多带一点正文摘要能提高区分度。
     * 前缀与问答链路保持一致（bge 官方建议查询侧加检索指令前缀，索引侧不加）。
     */
    private String buildVectorQuery(Article source) {
        String title = source.getTitle() == null ? "" : source.getTitle();
        String content = source.getContent() == null ? "" : source.getContent();
        String excerpt = content.length() <= QUERY_CONTENT_CHARS
                ? content
                : content.substring(0, QUERY_CONTENT_CHARS);
        return queryPrefix + title + " " + excerpt;
    }

    private Long parseArticleId(Document document) {
        Object value = document.getMetadata().get("articleId");
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException exception) {
            log.warn("向量召回结果的 articleId 不是合法数字：{}", value);
            return null;
        }
    }
}

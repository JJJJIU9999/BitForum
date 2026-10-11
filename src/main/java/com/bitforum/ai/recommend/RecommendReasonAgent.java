package com.bitforum.ai.recommend;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.bitforum.ai.degrade.AiDegradeGuard;
import com.bitforum.ai.recommend.RecommendService.RecommendedArticle;
import com.bitforum.ai.trace.TraceDegradeReason;

/**
 * 推荐理由生成（M17）。
 *
 * <p><b>职责边界：只写解释，不参与选文。</b>
 * 召回与排序已经由 Java 完成（三路召回 → RRF 融合 → Top-N），
 * 本类只把"已经定好的列表"交给模型，让它为每一条写一句理由。
 * 这条边界来自 M17 的实施决策：排序必须可复现，模型的输出只是排序之上的说明。
 *
 * <p><b>为什么用"按序号填理由"而不是"让模型输出文章 id"</b>：
 * T9 实测（findings.md 6.13）对照过两种方案 —— A 让模型自己挑文章并写理由，
 * B 由 Java 定序、模型只按序号填理由。两者当场都没有出错，但 B 有两个结构性优势：
 * <ol>
 *   <li><b>模型完全接触不到文章 id</b>，因此"推荐错文章"在结构上不可能发生
 *       （A 方案在候选池更大时无法保证）；</li>
 *   <li><b>给模型的输入恒为 N 条</b>，与候选池大小无关，成本可控。</li>
 * </ol>
 * 越界或重复的序号在这里被丢弃并记警告 —— 不因为一次模型异常就让整条推荐链路失败。
 *
 * <p><b>不抛异常</b>：模型不可用、超时或解析失败时返回降级结果（没有理由），
 * 推荐列表照常返回。这与 handoff 的硬约束"AI 不可用不能影响论坛主流程"一致 ——
 * 而且"没有理由的推荐列表"本来就是生产环境必须能独立工作的路径。
 */
@Component
public class RecommendReasonAgent {

    private static final Logger log = LoggerFactory.getLogger(RecommendReasonAgent.class);

    /**
     * 一次理由生成的结果。
     *
     * @param reasonsByArticleId 文章 id → 理由。降级时为空 Map
     * @param degraded           是否走了降级链路（推荐列表仍然可用，只是没有理由）
     */
    public record ReasonOutcome(Map<Long, String> reasonsByArticleId, String model,
                                Integer promptTokens, Integer completionTokens, Integer totalTokens,
                                long latencyMillis, boolean degraded, String errorMessage,
                                /** 降级原因码（M18）；正常结果为 null */
                                String degradeReason) {

        public static ReasonOutcome of(Map<Long, String> reasons, String model,
                                       Integer promptTokens, Integer completionTokens, Integer totalTokens,
                                       long latencyMillis) {
            return new ReasonOutcome(Map.copyOf(reasons), model, promptTokens, completionTokens, totalTokens,
                    latencyMillis, false, null, null);
        }

        public static ReasonOutcome degraded(String reason, String model, long latencyMillis,
                                             String errorMessage) {
            return new ReasonOutcome(Map.of(), model, null, null, null, latencyMillis, true,
                    abbreviate(errorMessage), reason);
        }

        public String reasonFor(Long articleId) {
            return reasonsByArticleId.get(articleId);
        }

        private static String abbreviate(String text) {
            if (text == null) {
                return null;
            }
            return text.length() <= 500 ? text : text.substring(0, 500);
        }
    }

    /**
     * 系统提示词。
     *
     * <p>依据 T9 的实测结论设计：**必须把条数与序号写死**。
     * T7 曾经发现"让模型返回列表时数量不稳定（4/5/6 个都出现过）"，
     * 而 T9 证明只要明确要求"恰好 N 条、序号从 1 到 N"，两种方案都能稳定输出 8/8。
     */
    private static final String SYSTEM_PROMPT = """
            你是 BitForum 技术社区的推荐理由生成助手。
            系统的召回与排序**已经完成**，你不需要挑选文章、也不需要排序。
            你的唯一任务是为给定的推荐列表逐条写推荐理由。

            硬约束：
            1. index 必须与给定序号完全一致，从 1 到 N，**不得增删条目、不得修改序号**；
            2. 不要输出文章 id，也不要改变顺序；
            3. 理由必须结合该篇的**召回来源**与用户情况，例如"与你正在看的这篇主题相近"
               "近期社区热度较高""来自你关注的作者"；
            4. 不要写"内容优质""值得一读""干货满满"这类空话；
            5. 每条理由不超过 40 字，用中文；
            6. 只输出结构化结果，不要额外解释。
            """;

    private final ObjectProvider<ChatClient> chatClientProvider;
    private final String modelName;
    private final int maxArticles;
    private final AiDegradeGuard degradeGuard;

    public RecommendReasonAgent(
            ObjectProvider<ChatClient> chatClientProvider,
            @Value("${bitforum.ai.recommend.model-name:deepseek-flash}") String modelName,
            @Value("${bitforum.ai.recommend.reason-max-articles:10}") int maxArticles,
            AiDegradeGuard degradeGuard) {
        this.chatClientProvider = chatClientProvider;
        this.modelName = modelName;
        this.maxArticles = Math.max(1, maxArticles);
        this.degradeGuard = degradeGuard;
    }

    /** 模型返回的结构：只含序号与理由，**刻意不含文章 id**。 */
    public record ReasonItem(Integer index, String reason) {
    }

    public record ReasonList(List<ReasonItem> reasons) {
    }

    /**
     * 为推荐列表生成理由。
     *
     * @param articles    已定序的推荐列表（调用方保证顺序与条数）
     * @param userProfile 用户情况的一句话描述（登录用户含行为摘要，匿名访客说明没有个人数据）；
     *                    刻意只给摘要而不是具体收藏清单 —— 既不泄漏也不浪费上下文
     */
    public ReasonOutcome generate(List<RecommendedArticle> articles, String userProfile) {
        return generate(articles, userProfile, false);
    }

    /**
     * @param queryBased 本次推荐是否由"用户的提问"驱动（AI 助手场景）。
     *                   措辞必须随之变化 —— 在助手场景说"与你正在看的文章相近"是错的，
     *                   因为那时用户并没有"正在看的文章"。
     */
    public ReasonOutcome generate(List<RecommendedArticle> articles, String userProfile, boolean queryBased) {
        long startedAt = System.currentTimeMillis();
        if (articles == null || articles.isEmpty()) {
            return ReasonOutcome.degraded(TraceDegradeReason.NOTHING_TO_DO, modelName, 0L,
                    "推荐列表为空，无需生成理由");
        }

        ChatClient chatClient = chatClientProvider.getIfAvailable();
        if (chatClient == null) {
            log.debug("ChatClient 不可用，推荐理由走降级（推荐列表照常返回）");
            String reason = TraceDegradeReason.AI_DISABLED;
            return ReasonOutcome.degraded(reason, modelName, System.currentTimeMillis() - startedAt,
                    degradeGuard.degrade(reason,
                            "ChatClient 不可用（未配置 DEEPSEEK_API_KEY 或未启用 AI）"));
        }

        // 只对前 N 条生成理由：超出的部分留给前端按"无理由"处理，避免一次调用过长
        List<RecommendedArticle> target = articles.size() <= maxArticles
                ? articles
                : articles.subList(0, maxArticles);

        try {
            // 用 responseEntity 而不是 entity：既要结构化结果，也要 token 用量
            // （M18 的成本管控要按 Agent 统计 token）
            var responseEntity = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(buildUserPrompt(target, userProfile, queryBased))
                    .call()
                    .responseEntity(ReasonList.class);

            long latency = System.currentTimeMillis() - startedAt;
            ReasonList result = responseEntity == null ? null : responseEntity.entity();
            ChatResponse response = responseEntity == null ? null : responseEntity.response();
            if (result == null || result.reasons() == null || result.reasons().isEmpty()) {
                return ReasonOutcome.degraded(TraceDegradeReason.EMPTY_RESPONSE, modelName, latency,
                        degradeGuard.degrade(TraceDegradeReason.EMPTY_RESPONSE, "模型返回空结果"));
            }

            Map<Long, String> reasons = mapReasons(result.reasons(), target);
            if (reasons.isEmpty()) {
                return ReasonOutcome.degraded(TraceDegradeReason.EMPTY_RESPONSE, modelName, latency,
                        degradeGuard.degrade(TraceDegradeReason.EMPTY_RESPONSE, "模型返回的序号全部越界"));
            }

            log.info(">>> 推荐理由生成完成：请求 {} 条，命中 {} 条，耗时 {} ms",
                    target.size(), reasons.size(), latency);

            return ReasonOutcome.of(reasons, modelName,
                    promptTokens(response), completionTokens(response), totalTokens(response), latency);
        } catch (RuntimeException exception) {
            long latency = System.currentTimeMillis() - startedAt;
            log.warn("推荐理由生成失败，降级为无理由推荐：{}", exception.getMessage());
            String reason = degradeGuard.classify(exception);
            return ReasonOutcome.degraded(reason, modelName, latency,
                    degradeGuard.degrade(reason, exception.getMessage()));
        }
    }

    /**
     * 把"序号 → 理由"映射成"文章 id → 理由"。
     *
     * <p>映射由 Java 完成 —— 这是"模型接触不到文章 id"的落地方式：
     * 即使模型返回了越界或重复的序号，也只会丢失几条理由，不会推荐错文章。
     */
    private Map<Long, String> mapReasons(List<ReasonItem> items, List<RecommendedArticle> target) {
        Map<Long, String> reasons = new LinkedHashMap<>();
        List<Integer> outOfRange = new ArrayList<>();
        for (ReasonItem item : items) {
            if (item == null || item.index() == null) {
                continue;
            }
            int index = item.index();
            if (index < 1 || index > target.size()) {
                outOfRange.add(index);
                continue;
            }
            String reason = item.reason() == null ? null : item.reason().trim();
            if (reason == null || reason.isEmpty()) {
                continue;
            }
            reasons.putIfAbsent(target.get(index - 1).articleId(), reason);
        }
        if (!outOfRange.isEmpty()) {
            // 不失败，但要留痕：越界说明模型没有严格遵守序号约束
            log.warn("模型返回了越界序号，已丢弃：{}（合法范围 1~{}）", outOfRange, target.size());
        }
        return reasons;
    }

    /**
     * 构造用户提示词：把已定序的列表（带序号与真实信号）+ 用户情况交给模型。
     *
     * <p>给出的信号都是**系统真实算出来的**（召回来源、相似度、热度分值、排名），
     * 而不是让模型自己猜 —— 这是"理由必须基于真实信号"的前提。
     */
    private String buildUserPrompt(List<RecommendedArticle> articles, String userProfile,
                                   boolean queryBased) {
        StringBuilder builder = new StringBuilder();
        builder.append("用户情况：").append(userProfile == null || userProfile.isBlank()
                ? "未登录访客，没有个人行为数据" : userProfile).append("\n\n");
        builder.append("已定序的推荐列表（共 ").append(articles.size()).append(" 条，顺序不可更改）：\n");
        for (int i = 0; i < articles.size(); i++) {
            RecommendedArticle article = articles.get(i);
            builder.append(i + 1).append(". 《").append(article.title()).append('》');
            if (article.categoryName() != null && !article.categoryName().isBlank()) {
                builder.append("（板块：").append(article.categoryName()).append('）');
            }
            builder.append("，召回来源：").append(describeSignals(article, queryBased));
            builder.append('\n');
        }
        builder.append("\n请为上面这 ").append(articles.size()).append(" 条逐条写推荐理由。");
        return builder.toString();
    }

    /** 把召回来源翻译成模型能理解的中文描述（而不是让它去猜 code 的含义）。 */
    private String describeSignals(RecommendedArticle article, boolean queryBased) {
        List<String> signals = new ArrayList<>();
        for (String source : article.sources()) {
            switch (source) {
                case "vector" -> signals.add(queryBased ? "内容与你的问题相关" : "内容与你正在看的文章相近");
                case "hot" -> signals.add("近期社区热度较高");
                case "follow" -> signals.add("来自你关注的作者");
                default -> signals.add(source);
            }
        }
        return signals.isEmpty() ? "综合推荐" : String.join("；", signals);
    }

    private Integer promptTokens(ChatResponse response) {
        return response == null || response.getMetadata() == null || response.getMetadata().getUsage() == null
                ? null
                : response.getMetadata().getUsage().getPromptTokens();
    }

    private Integer completionTokens(ChatResponse response) {
        return response == null || response.getMetadata() == null || response.getMetadata().getUsage() == null
                ? null
                : response.getMetadata().getUsage().getCompletionTokens();
    }

    private Integer totalTokens(ChatResponse response) {
        return response == null || response.getMetadata() == null || response.getMetadata().getUsage() == null
                ? null
                : response.getMetadata().getUsage().getTotalTokens();
    }
}

package com.bitforum.ai.recommend;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * M17 前置验证探针（T9）：为「不定长推荐列表」生成结构化理由的稳定性。
 *
 * <p><b>为什么验这一件事</b>：M13-M16 里模型只输出过两种形态 —— 自由文本（QA）
 * 与<b>固定字段</b>结构（{@code ModerationAssessment}，五维字段写死）。
 * T7 实测（findings.md 6.12）已经发现：一旦让模型返回<b>列表</b>，
 * 元素数量就不稳定（4、5、6 个都出现过），M16 因此改用固定字段。
 *
 * <p>而 M17 的 RecommendAgent 本质上必须输出一个<b>不定长列表</b>
 * （Top-N 文章 + 每篇一句理由），这是本项目唯一没验证过的输出形态。
 * 本探针在写任何业务代码之前，用真实模型调用先把它测清楚，避免实现定型后才发现形态不可靠。
 *
 * <p><b>对照的两种方案</b>：
 * <ul>
 *   <li><b>A 模型自选</b>：把召回候选池给模型，让它自己挑 N 篇并输出 {@code {articleId, reason}}。
 *       灵活，但模型可能编造 id、少给或多给条数。</li>
 *   <li><b>B 应用定序</b>：召回与排序完全由 Java 决定（可复现），模型只按给定序号
 *       填理由 {@code {index, reason}}，id 由 Java 回填。模型不可能选错文章。</li>
 * </ul>
 *
 * <p>统计口径：解析成功率、条数符合率、<b>越界率</b>（幻觉：id/序号不在候选中）、
 * 重复条目、空理由、耗时。默认不执行（消耗真实 API 额度）：
 *
 * <pre>
 *   export DEEPSEEK_CHAT_ENABLED=true
 *   export DEEPSEEK_API_KEY="$(grep '^DEEPSEEK_API_KEY=' .env | cut -d= -f2-)"
 *   ./mvnw -s maven-settings.xml -Dtest=RecommendReasonProbe test
 * </pre>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_CHAT_ENABLED", matches = "true")
class RecommendReasonProbe {

    private static final Logger log = LoggerFactory.getLogger(RecommendReasonProbe.class);

    /** 每个方案重复运行次数：单次结果无法区分"稳定"与"碰巧"。 */
    private static final int ROUNDS = 3;

    /** 要求模型给出的推荐条数；小于候选数，才能暴露"选哪几篇"与"条数是否会飘"。 */
    private static final int TOP_N = 8;

    /**
     * 模拟三路召回融合后的候选池：真实文章（id 85/86/87/88/749，取自本地库的已发布文章）
     * + 合成技术文章，凑出比 TOP_N 更大的候选空间。
     * 带 sources 字段模拟"这一篇被哪几路召回"，用于检验理由是否真的引用了召回依据。
     */
    private record Candidate(long id, String title, String category, String sources) {
    }

    private static final List<Candidate> CANDIDATES = List.of(
            new Candidate(85L, "Spring Boot 论坛项目实践", "技术", "向量相似,热度"),
            new Candidate(86L, "React 与 Vite 前端开发笔记", "前端", "向量相似"),
            new Candidate(87L, "Redis 热点数据同步方案", "中间件", "向量相似,热度,关注"),
            new Candidate(88L, "社区使用指南", "公告", "热度"),
            new Candidate(749L, "Spring 和 Spring Boot 到底是什么关系？一篇讲清", "技术", "向量相似,热度"),
            new Candidate(901L, "MySQL 索引下推与覆盖索引实践", "数据库", "向量相似"),
            new Candidate(902L, "Spring 事务传播行为避坑指南", "技术", "向量相似"),
            new Candidate(903L, "Redis 缓存穿透与雪崩的实战解法", "中间件", "向量相似,关注"),
            new Candidate(904L, "RabbitMQ 死信队列与重试机制", "中间件", "向量相似"),
            new Candidate(905L, "Vite 构建性能优化清单", "前端", "向量相似"),
            new Candidate(906L, "React Hooks 常见闭包陷阱", "前端", "关注"),
            new Candidate(907L, "JWT 无状态鉴权的边界与取舍", "后端", "向量相似"),
            new Candidate(908L, "MyBatis-Plus 分页插件原理剖析", "后端", "向量相似"),
            new Candidate(909L, "Docker Compose 本地开发环境编排", "运维", "热度"),
            new Candidate(910L, "从单体到模块化：包结构划分经验", "架构", "向量相似"));

    /** 当前用户的画像：M17 的推荐理由要能体现"为什么推荐给他"。 */
    private static final String USER_PROFILE = """
            当前用户（id=13）的站内行为：
            - 收藏过：《Redis 热点数据同步方案》《Spring Boot 论坛项目实践》
            - 关注了 2 位作者，他们主要写后端与中间件方向
            - 最近浏览集中在：中间件、后端、数据库
            """;

    // ===== 方案 A：模型自选 id =====

    public record ItemA(Long articleId, String reason) {
    }

    public record ResultA(List<ItemA> recommendations) {
    }

    // ===== 方案 B：应用定序，模型只填理由 =====

    public record ItemB(Integer index, String reason) {
    }

    public record ResultB(List<ItemB> reasons) {
    }

    private static final String SYSTEM_PROMPT_A = """
            你是 BitForum 技术社区的推荐助手。系统会给你一个候选文章池，每篇标注了召回来源
            （向量相似 = 内容主题相近；热度 = 近期活跃；关注 = 来自你关注作者）。

            任务：从候选池中挑选 %d 篇最值得推荐给当前用户的文章，按推荐优先级从高到低排序，
            并为每一篇写一句不超过 40 字的推荐理由，说明"为什么推荐给他"。

            硬约束：
            1. articleId 只能取自候选池中列出的 id，**不得编造、不得使用候选池外的任何 id**；
            2. 必须恰好返回 %d 篇，不得多也不得少；
            3. 理由要结合该文章的主题与召回来源，不要写"内容优质""值得一读"这类空话；
            4. 只输出结构化结果，不要额外解释。
            """.formatted(TOP_N, TOP_N);

    private static final String SYSTEM_PROMPT_B = """
            你是 BitForum 技术社区的推荐助手。系统的多路召回与排序已经完成，
            你只需要为**给定的**推荐列表逐条写推荐理由。

            任务：下面给出了 %d 篇文章（顺序已固定），为每一篇写一句不超过 40 字的推荐理由，
            说明"为什么推荐给当前用户"，理由要结合文章主题与该篇的召回来源。

            硬约束：
            1. index 必须与给定序号完全一致，从 1 到 %d，**不得增删条目、不得修改序号**；
            2. 不要输出文章 id，也不要改变顺序；
            3. 理由不要写"内容优质""值得一读"这类空话；
            4. 只输出结构化结果，不要额外解释。
            """.formatted(TOP_N, TOP_N);

    @Autowired
    private ObjectProvider<ChatClient> chatClientProvider;

    @Test
    void shouldGenerateStableReasonsForFixedLengthList() {
        ChatClient chatClient = chatClientProvider.getIfAvailable();
        if (chatClient == null) {
            throw new IllegalStateException("ChatClient 不可用：需要 DEEPSEEK_CHAT_ENABLED=true 与有效 API Key");
        }

        List<String> report = new ArrayList<>();
        for (int round = 1; round <= ROUNDS; round++) {
            report.add(runPlanA(chatClient, round));
        }
        for (int round = 1; round <= ROUNDS; round++) {
            report.add(runPlanB(chatClient, round));
        }

        log.info("""
                
                ================= T9 探针汇总（共 {} 轮真实调用） =================
                {}
                ================================================================
                """, ROUNDS * 2, String.join("\n", report));
    }

    /** 方案 A：模型自己挑 id 与条数。统计条数偏离与 id 幻觉。 */
    private String runPlanA(ChatClient chatClient, int round) {
        long startedAt = System.currentTimeMillis();
        try {
            ResultA result = chatClient.prompt()
                    .system(SYSTEM_PROMPT_A)
                    .user(buildUserPrompt("从候选中挑选 " + TOP_N + " 篇推荐给该用户。"))
                    .call()
                    .entity(ResultA.class);

            long latency = System.currentTimeMillis() - startedAt;
            List<ItemA> items = result == null || result.recommendations() == null
                    ? List.of()
                    : result.recommendations();

            Set<Long> candidateIds = new HashSet<>();
            CANDIDATES.forEach(candidate -> candidateIds.add(candidate.id()));
            Set<Long> seen = new LinkedHashSet<>();
            List<Long> hallucinated = new ArrayList<>();
            List<Long> duplicated = new ArrayList<>();
            long emptyReason = 0;
            for (ItemA item : items) {
                if (item == null || item.articleId() == null) {
                    continue;
                }
                if (!candidateIds.contains(item.articleId())) {
                    hallucinated.add(item.articleId());
                }
                if (!seen.add(item.articleId())) {
                    duplicated.add(item.articleId());
                }
                if (item.reason() == null || item.reason().isBlank()) {
                    emptyReason++;
                }
            }

            String line = "A 第 %d 轮：条数=%d/%d 解析=成功 幻觉id=%s 重复=%s 空理由=%d 耗时=%dms"
                    .formatted(round, items.size(), TOP_N, hallucinated, duplicated, emptyReason, latency);
            log.info(">>> {}", line);
            items.forEach(item -> log.info("      A{} -> id={} | {}",
                    round, item == null ? null : item.articleId(), item == null ? null : item.reason()));
            return line;
        } catch (RuntimeException exception) {
            long latency = System.currentTimeMillis() - startedAt;
            String line = "A 第 %d 轮：解析=失败（%s）耗时=%dms"
                    .formatted(round, exception.getClass().getSimpleName() + ": " + exception.getMessage(), latency);
            log.error(">>> {}", line);
            return line;
        }
    }

    /**
     * 方案 B：Java 已定序，模型只填理由。
     *
     * <p>这里的"已定序列表"用固定下标截取候选池模拟融合排序结果 ——
     * 探针只验证输出形态，不验证排序算法本身。
     */
    private String runPlanB(ChatClient chatClient, int round) {
        List<Candidate> picked = CANDIDATES.subList(0, TOP_N);
        long startedAt = System.currentTimeMillis();
        try {
            ResultB result = chatClient.prompt()
                    .system(SYSTEM_PROMPT_B)
                    .user(buildUserPrompt(buildFixedList(picked) + "\n请为上面这 " + TOP_N + " 篇逐条写理由。"))
                    .call()
                    .entity(ResultB.class);

            long latency = System.currentTimeMillis() - startedAt;
            List<ItemB> items = result == null || result.reasons() == null ? List.of() : result.reasons();

            Set<Integer> seen = new LinkedHashSet<>();
            List<Integer> outOfRange = new ArrayList<>();
            List<Integer> duplicated = new ArrayList<>();
            long emptyReason = 0;
            for (ItemB item : items) {
                if (item == null || item.index() == null) {
                    continue;
                }
                if (item.index() < 1 || item.index() > TOP_N) {
                    outOfRange.add(item.index());
                }
                if (!seen.add(item.index())) {
                    duplicated.add(item.index());
                }
                if (item.reason() == null || item.reason().isBlank()) {
                    emptyReason++;
                }
            }
            // 序号缺失也是缺陷：模型少给了某几篇，前端就会出现"没有理由的推荐"
            List<Integer> missing = new ArrayList<>();
            for (int index = 1; index <= TOP_N; index++) {
                if (!seen.contains(index)) {
                    missing.add(index);
                }
            }

            String line = ("B 第 %d 轮：条数=%d/%d 解析=成功 越界序号=%s 重复=%s 缺失序号=%s 空理由=%d 耗时=%dms")
                    .formatted(round, items.size(), TOP_N, outOfRange, duplicated, missing, emptyReason, latency);
            log.info(">>> {}", line);
            items.forEach(item -> log.info("      B{} -> index={} | {}",
                    round, item == null ? null : item.index(), item == null ? null : item.reason()));
            return line;
        } catch (RuntimeException exception) {
            long latency = System.currentTimeMillis() - startedAt;
            String line = "B 第 %d 轮：解析=失败（%s）耗时=%dms"
                    .formatted(round, exception.getClass().getSimpleName() + ": " + exception.getMessage(), latency);
            log.error(">>> {}", line);
            return line;
        }
    }

    private String buildUserPrompt(String task) {
        StringBuilder builder = new StringBuilder();
        builder.append(USER_PROFILE).append('\n');
        builder.append("候选文章池（共 ").append(CANDIDATES.size()).append(" 篇）：\n");
        for (Candidate candidate : CANDIDATES) {
            builder.append("- id=").append(candidate.id())
                    .append(" 《").append(candidate.title()).append("》")
                    .append("（板块=").append(candidate.category())
                    .append("，召回来源=").append(candidate.sources()).append("）\n");
        }
        builder.append('\n').append(task);
        return builder.toString();
    }

    /** 方案 B 的"已定序推荐列表"：带序号、不带 id，模型无法自行改选文章。 */
    private String buildFixedList(List<Candidate> picked) {
        StringBuilder builder = new StringBuilder("已定序的推荐列表：\n");
        for (int index = 0; index < picked.size(); index++) {
            Candidate candidate = picked.get(index);
            builder.append(index + 1).append(". 《").append(candidate.title()).append("》")
                    .append("（板块=").append(candidate.category())
                    .append("，召回来源=").append(candidate.sources()).append("）\n");
        }
        return builder.toString();
    }
}

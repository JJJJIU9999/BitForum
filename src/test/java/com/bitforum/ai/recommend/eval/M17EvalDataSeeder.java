package com.bitforum.ai.recommend.eval;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bitforum.ai.rag.KbIndexService;
import com.bitforum.entity.Article;
import com.bitforum.entity.ArticleFavorite;
import com.bitforum.entity.Category;
import com.bitforum.entity.User;
import com.bitforum.entity.UserFollow;
import com.bitforum.mapper.ArticleFavoriteMapper;
import com.bitforum.mapper.ArticleMapper;
import com.bitforum.mapper.CategoryMapper;
import com.bitforum.mapper.UserFollowMapper;
import com.bitforum.mapper.UserMapper;
import com.bitforum.service.ArticleService;
import com.bitforum.service.RedisService;

/**
 * M17 评测数据生成器（**合成数据**，如实声明：不是真实社区流量）。
 *
 * <p>按照 `m17-eval-protocol.md` 第六节生成评测所需的数据：
 * 50 篇有实质正文的技术文章（分 8 个主题）、8 位主题作者、20 位评测用户，
 * 以及收藏、关注与热度信号，最后重建向量索引。
 *
 * <p><b>为什么必须真的写进库里</b>：评测要在**真实链路**上跑 ——
 * 向量通道走 Redis 向量索引、热度通道走热榜 ZSet、关注通道走 {@code user_follow} 表。
 * 用内存里的假数据评测，测的就不是这套系统。
 *
 * <p><b>可重复执行</b>：每次运行先按前缀清理上一次生成的评测数据（文章标题 {@code [M17Eval]}
 * 、用户名 {@code m17eval_}、板块名 {@code M17Eval-}），再重新插入，因此不会累积脏数据。
 * 随机数种子固定，同样的代码生成同样的数据。
 *
 * <p><b>刻意不动的数据</b>：库里原有的文章与用户一律保留。
 * 评测因此是在"既有内容 + 合成评测语料"的真实候选池上进行的，报告里会给出候选池规模。
 *
 * <p>默认不执行（它会写入数据库与 Redis）：
 *
 * <pre>
 *   export M17_EVAL_SEED=true
 *   ./mvnw -s maven-settings.xml -Dtest=M17EvalDataSeeder test
 * </pre>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "M17_EVAL_SEED", matches = "true")
class M17EvalDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(M17EvalDataSeeder.class);

    /** 评测数据的识别前缀，清理与统计都依赖它们。 */
    private static final String TITLE_PREFIX = "[M17Eval] ";
    private static final String USER_PREFIX = "m17eval_";
    private static final String CATEGORY_PREFIX = "M17Eval-";

    /** 固定随机种子：同样的代码必须生成同样的数据，否则评测不可复现。 */
    private static final long SEED = 20_260_919L;

    /** 评测用户的统一密码（仅本地评测数据使用，不涉及任何真实凭据）。 */
    private static final String EVAL_PASSWORD = "M17Eval@2026";

    /** 普通评测用户数量。取规范上限 30：留一法按 70/30 划分后，测试集仍有 9 位用户，指标粒度不至于太粗。 */
    private static final int NORMAL_USER_COUNT = 30;

    /** article.title 是 varchar(50)，超长会被数据库截断，这里显式控制。 */
    private static final int TITLE_MAX_CHARS = 50;

    /**
     * 8 个技术主题，与语料的 {@code theme} 取值一一对应。
     *
     * <p>主题数量与语料分布由 `m17-eval-protocol.md` 第六节规定：每主题 5~8 篇、合计 40~60 篇。
     */
    private static final List<String> THEMES = List.of(
            "spring", "persistence", "redis", "mq", "frontend", "deploy", "concurrency", "api");

    private static final Map<String, String> THEME_DISPLAY = Map.of(
            "spring", "Spring 框架",
            "persistence", "持久层与数据库",
            "redis", "Redis 与缓存",
            "mq", "消息队列",
            "frontend", "前端工程",
            "deploy", "部署与运维",
            "concurrency", "并发与 JVM",
            "api", "接口与鉴权");

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private ArticleMapper articleMapper;
    @Autowired
    private CategoryMapper categoryMapper;
    @Autowired
    private ArticleFavoriteMapper articleFavoriteMapper;
    @Autowired
    private UserFollowMapper userFollowMapper;
    @Autowired
    private RedisService redisService;
    @Autowired
    private KbIndexService kbIndexService;

    @Test
    void seedEvaluationDataset() {
        Random random = new Random(SEED);

        validateCorpus();
        cleanupPreviousRun();

        // 只清理不生成：演示前想回到"只有真实文章"的干净状态时用。
        // 注意清掉之后候选池只剩原有的少量文章，"相关推荐"的效果会明显变弱 ——
        // 这是数据量的必然结果（评测报告里的结论是在 55 篇候选池上得出的）。
        if ("true".equalsIgnoreCase(System.getenv("M17_EVAL_CLEANUP_ONLY"))) {
            log.info("""

                    ================= M17 评测数据已清理 =================
                    只清理不生成（M17_EVAL_CLEANUP_ONLY=true）：
                    评测文章、评测用户、评测板块、收藏、关注，
                    以及向量索引中的对应片段都已移除。
                    需要重新生成时：去掉该环境变量再跑一次即可。
                    ======================================================
                    """);
            return;
        }

        Map<String, Long> categoryIds = createThemeCategories();
        Map<String, Long> expertIds = createExperts();
        List<Long> normalUserIds = createNormalUsers();
        Map<String, List<Article>> articlesByTheme = createArticles(categoryIds, expertIds);

        List<Article> allArticles = articlesByTheme.values().stream().flatMap(List::stream).toList();
        Map<Long, List<String>> interestsByUser = assignInterests(normalUserIds, random);

        int favorites = createFavorites(interestsByUser, articlesByTheme, random);
        int follows = createFollows(interestsByUser, expertIds);
        seedHotScores(allArticles, random);

        KbIndexService.RebuildResult rebuild = kbIndexService.rebuildAll();

        log.info("""

                ================= M17 评测数据生成完成（合成数据） =================
                主题数：{}，文章数：{}
                作者（每主题一位）：{}
                评测用户：{} 位
                收藏记录：{} 条，关注关系：{} 条
                热度：已为 {} 篇文章写入随机热榜分值（与主题无关 —— 合成数据的简化假设）
                向量索引：已发布 {} 篇 → 已索引 {}，共 {} 个片段，耗时 {} ms
                识别前缀：文章「{}」/ 用户「{}」/ 板块「{}」
                ==================================================================
                """,
                THEMES.size(), allArticles.size(), expertIds.size(), normalUserIds.size(),
                favorites, follows, allArticles.size(),
                rebuild.publishedArticles(), rebuild.indexed(),
                kbIndexService.stats().chunks(), rebuild.elapsedMillis(),
                TITLE_PREFIX.trim(), USER_PREFIX, CATEGORY_PREFIX);
    }

    // ==================== 清理 ====================

    /**
     * 按前缀清理上一次生成的评测数据。
     *
     * <p>删除顺序刻意从"依赖方"到"被依赖方"：先收藏/关注，再向量索引与 Redis，最后文章、板块、用户。
     * 项目没有物理外键，顺序不影响正确性，但这样写能避免留下指向已删文章的悬空记录。
     */
    private void cleanupPreviousRun() {
        List<Long> userIds = userMapper.selectList(new LambdaQueryWrapper<User>()
                        .likeRight(User::getUsername, USER_PREFIX))
                .stream().map(User::getId).toList();
        List<Long> articleIds = articleMapper.selectList(new LambdaQueryWrapper<Article>()
                        .likeRight(Article::getTitle, TITLE_PREFIX))
                .stream().map(Article::getId).toList();

        if (!userIds.isEmpty()) {
            userFollowMapper.delete(new LambdaQueryWrapper<UserFollow>()
                    .in(UserFollow::getFollowerId, userIds).or().in(UserFollow::getFollowingId, userIds));
            articleFavoriteMapper.delete(new LambdaQueryWrapper<ArticleFavorite>()
                    .in(ArticleFavorite::getUserId, userIds));
        }
        if (!articleIds.isEmpty()) {
            articleFavoriteMapper.delete(new LambdaQueryWrapper<ArticleFavorite>()
                    .in(ArticleFavorite::getArticleId, articleIds));
            for (Long articleId : articleIds) {
                kbIndexService.removeArticle(articleId);
                redisService.deleteArticleData(articleId);
            }
            articleMapper.delete(new LambdaQueryWrapper<Article>().in(Article::getId, articleIds));
        }
        categoryMapper.delete(new LambdaQueryWrapper<Category>()
                .likeRight(Category::getName, CATEGORY_PREFIX));
        if (!userIds.isEmpty()) {
            userMapper.delete(new LambdaQueryWrapper<User>().in(User::getId, userIds));
        }

        log.info(">>> 清理上一轮评测数据：用户 {} 位、文章 {} 篇", userIds.size(), articleIds.size());
    }

    // ==================== 生成 ====================

    private Map<String, Long> createThemeCategories() {
        Map<String, Long> ids = new LinkedHashMap<>();
        int sortOrder = 100;
        for (String theme : THEMES) {
            Category category = new Category();
            category.setName(CATEGORY_PREFIX + theme);
            category.setDescription("M17 推荐评测合成主题：" + THEME_DISPLAY.getOrDefault(theme, theme));
            category.setSortOrder(sortOrder++);
            category.setStatus(1);
            categoryMapper.insert(category);
            ids.put(theme, category.getId());
        }
        return ids;
    }

    /** 每个主题一位作者：关注通道需要"用户关注的作者"确实存在。 */
    private Map<String, Long> createExperts() {
        String passwordHash = new BCryptPasswordEncoder().encode(EVAL_PASSWORD);
        Map<String, Long> ids = new LinkedHashMap<>();
        for (String theme : THEMES) {
            User expert = newUser(USER_PREFIX + "expert_" + theme,
                    "评测作者·" + THEME_DISPLAY.getOrDefault(theme, theme), passwordHash);
            userMapper.insert(expert);
            ids.put(theme, expert.getId());
        }
        return ids;
    }

    private List<Long> createNormalUsers() {
        String passwordHash = new BCryptPasswordEncoder().encode(EVAL_PASSWORD);
        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= NORMAL_USER_COUNT; i++) {
            User user = newUser(USER_PREFIX + String.format("user_%02d", i),
                    "评测用户 " + i, passwordHash);
            userMapper.insert(user);
            ids.add(user.getId());
        }
        return ids;
    }

    private User newUser(String username, String nickname, String passwordHash) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordHash);
        user.setNickname(nickname);
        user.setBio("M17 推荐评测合成账号");
        user.setRole("USER");
        user.setStatus(1);
        return user;
    }

    /**
     * 按语料建文章。
     *
     * <p>{@code create_time} 刻意逐篇递增：关注通道按发布时间倒序取文，
     * 如果 50 篇时间戳完全相同，那一路的"排序"就没有意义了。
     */
    private Map<String, List<Article>> createArticles(Map<String, Long> categoryIds, Map<String, Long> expertIds) {
        Map<String, List<Article>> byTheme = new LinkedHashMap<>();
        LocalDateTime base = LocalDateTime.now().minusDays(60);
        int index = 0;
        for (M17EvalCorpus.Spec spec : M17EvalCorpus.ARTICLES) {
            Long categoryId = categoryIds.get(spec.theme());
            Long expertId = expertIds.get(spec.theme());
            if (categoryId == null || expertId == null) {
                throw new IllegalStateException("语料出现未登记的主题：" + spec.theme());
            }
            Article article = new Article();
            article.setTitle(truncate(TITLE_PREFIX + spec.title(), TITLE_MAX_CHARS));
            article.setContent(spec.content());
            article.setUserId(expertId);
            article.setCategoryId(categoryId);
            article.setStatus(ArticleService.STATUS_PUBLISHED);
            article.setCreateTime(base.plusHours(index));
            index++;
            articleMapper.insert(article);
            byTheme.computeIfAbsent(spec.theme(), k -> new ArrayList<>()).add(article);
        }
        return byTheme;
    }

    /**
     * 给每个评测用户分配 1~2 个兴趣主题。
     *
     * <p>必须有结构而不是随机：留一法要测的是"能不能推出用户会感兴趣的同主题文章"，
     * 如果用户兴趣是均匀随机的，任何算法都不可能命中，评测就失去意义。
     */
    private Map<Long, List<String>> assignInterests(List<Long> userIds, Random random) {
        Map<Long, List<String>> interests = new LinkedHashMap<>();
        for (Long userId : userIds) {
            List<String> picked = new ArrayList<>();
            picked.add(THEMES.get(random.nextInt(THEMES.size())));
            // 一半用户有第二兴趣：让"多兴趣用户"这类情形也被覆盖
            if (random.nextBoolean()) {
                String second;
                do {
                    second = THEMES.get(random.nextInt(THEMES.size()));
                } while (picked.contains(second));
                picked.add(second);
            }
            interests.put(userId, picked);
        }
        return interests;
    }

    /**
     * 建收藏行为。
     *
     * <p>主兴趣收藏 4~6 篇、次兴趣 1~2 篇，合计 4~8 篇 —— 满足规范"每用户 4~8 篇、
     * 且留一之后仍有足够已知偏好（≥3）"的要求。
     */
    private int createFavorites(Map<Long, List<String>> interestsByUser,
                                Map<String, List<Article>> articlesByTheme, Random random) {
        int total = 0;
        for (Map.Entry<Long, List<String>> entry : interestsByUser.entrySet()) {
            List<String> themes = entry.getValue();
            for (int i = 0; i < themes.size(); i++) {
                List<Article> pool = new ArrayList<>(articlesByTheme.get(themes.get(i)));
                Collections.shuffle(pool, random);
                int count = i == 0 ? 4 + random.nextInt(3) : 1 + random.nextInt(2);
                for (int j = 0; j < Math.min(count, pool.size()); j++) {
                    ArticleFavorite favorite = new ArticleFavorite();
                    favorite.setUserId(entry.getKey());
                    favorite.setArticleId(pool.get(j).getId());
                    articleFavoriteMapper.insert(favorite);
                    total++;
                }
            }
        }
        return total;
    }

    private int createFollows(Map<Long, List<String>> interestsByUser, Map<String, Long> expertIds) {
        int total = 0;
        for (Map.Entry<Long, List<String>> entry : interestsByUser.entrySet()) {
            for (String theme : entry.getValue()) {
                UserFollow follow = new UserFollow();
                follow.setFollowerId(entry.getKey());
                follow.setFollowingId(expertIds.get(theme));
                userFollowMapper.insert(follow);
                total++;
            }
        }
        return total;
    }

    /**
     * 写入热榜分值。
     *
     * <p><b>刻意的简化假设</b>：热度是随机值，与文章主题无关（模拟"浏览行为不针对主题"）。
     * 这意味着纯热榜基线在**主题兴趣**这件事上几乎没有信息量 —— 评测报告必须如实说明这一点，
     * 不能把它包装成"推荐算法大幅超越热榜"。热度分值同时写进 Redis ZSet，
     * 走的是与线上完全相同的 {@code article:hot} 键。
     */
    private void seedHotScores(List<Article> articles, Random random) {
        for (Article article : articles) {
            // increaseHot 是累加语义：重跑前会先清理 Redis 键，因此不会叠加
            redisService.increaseHot(article.getId(), 1 + random.nextInt(30));
        }
    }

    private String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars);
    }

    /** 各主题实际篇数，供人工核对语料是否符合规范（每主题 5~8 篇）。 */
    private Map<String, Integer> countByTheme() {
        Map<String, Integer> counts = new HashMap<>();
        for (M17EvalCorpus.Spec spec : M17EvalCorpus.ARTICLES) {
            counts.merge(spec.theme(), 1, Integer::sum);
        }
        return counts;
    }

    /**
     * 语料必须符合 `m17-eval-protocol.md` 第六节的构成要求，否则评测的样本量站不住。
     *
     * <p>把校验放在入库之前：宁可生成失败，也不要悄悄用一份"少了几篇"的数据集跑出结论。
     */
    private void validateCorpus() {
        Map<String, Integer> counts = countByTheme();
        for (String theme : THEMES) {
            int count = counts.getOrDefault(theme, 0);
            if (count < 5 || count > 8) {
                throw new IllegalStateException(
                        "主题 " + theme + " 的语料篇数为 " + count + "，规范要求每主题 5~8 篇");
            }
        }
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        if (total < 40 || total > 60) {
            throw new IllegalStateException("语料总篇数为 " + total + "，规范要求 40~60 篇");
        }
        log.info(">>> 语料校验通过：{} 篇，各主题分布 {}", total, counts);
    }
}

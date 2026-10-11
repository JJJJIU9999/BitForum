package com.bitforum.ai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import com.bitforum.ai.tool.ToolDtos.ActionResult;
import com.bitforum.ai.tool.ToolDtos.ArticleBrief;
import com.bitforum.ai.tool.ToolDtos.ArticleDetail;
import com.bitforum.ai.tool.ToolDtos.CategoryBrief;
import com.bitforum.ai.tool.ToolDtos.PagedResult;
import com.bitforum.entity.Article;
import com.bitforum.entity.Category;
import com.bitforum.entity.User;
import com.bitforum.mapper.ArticleMapper;
import com.bitforum.mapper.CategoryMapper;
import com.bitforum.mapper.UserMapper;
import com.bitforum.service.ArticleService;
import com.bitforum.service.RedisService;

/**
 * 工具集集成测试（M14）。
 *
 * 测试策略：
 * - 数据库与既有 Service 用真实实现，验证工具确实复用了业务逻辑而非自写 SQL；
 * - RedisService 用 mock：可精确断言"点赞成功时才提升热度"这类副作用，
 *   且不依赖 Redis 内部 key 格式；
 * - @Transactional 保证数据回滚，不污染本地库。
 */
@SpringBootTest
@Transactional
class ArticleToolsTest {

    @Autowired
    private ArticleTools articleTools;
    @Autowired
    private UserInteractionTools interactionTools;
    @Autowired
    private ArticleMapper articleMapper;
    @Autowired
    private CategoryMapper categoryMapper;

    @MockitoBean
    private RedisService redisService;

    @Autowired
    private UserMapper userMapper;

    // ==================== 查询类工具 ====================

    @Test
    void searchShouldOnlyReturnPublishedArticles() {
        String marker = "M14SEARCH" + UUID.randomUUID().toString().substring(0, 8);
        Article published = createArticle(marker + " 已发布", ArticleService.STATUS_PUBLISHED);
        createArticle(marker + " 草稿", ArticleService.STATUS_DRAFT);
        createArticle(marker + " 待审核", "PENDING");

        PagedResult<ArticleBrief> result = articleTools.searchArticles(marker, null, 1, 10);

        assertEquals(1, result.total(), "只应检索到已发布文章，草稿与待审核必须被排除");
        assertEquals(published.getId(), result.items().get(0).id());
    }

    @Test
    void searchShouldReturnEmptyResultInsteadOfFailing() {
        PagedResult<ArticleBrief> result =
                articleTools.searchArticles("绝对不存在的关键词" + UUID.randomUUID(), null, 1, 5);

        assertEquals(0, result.total());
        assertTrue(result.items().isEmpty(), "无结果时应返回空列表，而不是抛异常");
        assertFalse(result.hasMore());
    }

    @Test
    void searchShouldCapPageSizeToProtectContext() {
        // 传入 999 应被收敛到上限 20，避免模型一次拉取过多内容撑爆上下文
        PagedResult<ArticleBrief> result = articleTools.searchArticles("", null, 1, 999);

        assertEquals(20, result.pageSize(), "每页条数必须被限制在上限内");
    }

    @Test
    void getDetailShouldReturnContentAndTruncateLongText() {
        String longContent = "内容".repeat(600); // 1200 字，超过 800 的截断阈值
        Article article = createArticle("M14详情" + UUID.randomUUID(), ArticleService.STATUS_PUBLISHED);
        article.setContent(longContent);
        articleMapper.updateById(article);

        ArticleDetail detail = articleTools.getArticleDetail(article.getId());

        assertEquals(article.getId(), detail.id());
        assertNotNull(detail.authorName(), "作者名应可解析");
        assertNotNull(detail.categoryName(), "板块名应可解析");
        assertTrue(detail.contentExcerpt().contains("正文已截断"), "超长正文应被截断并标注");
        assertTrue(detail.contentExcerpt().length() < longContent.length(), "截断后长度应小于原文");
    }

    @Test
    void hiddenHistoricalProfileNameMustNotLeakThroughAiTools() {
        Long userId = createUser("m17hidden");
        User user = userMapper.selectById(userId);
        user.setNickname("待审旧昵称");
        user.setPublicProfileStatus("PENDING");
        userMapper.updateById(user);
        Article article = createArticle("M17公开文章" + UUID.randomUUID(), ArticleService.STATUS_PUBLISHED);
        article.setUserId(userId);
        articleMapper.updateById(article);

        assertEquals("待审核用户", articleTools.getArticleDetail(article.getId()).authorName());
        assertEquals("待审核用户", interactionTools.getFollowStats(userId, ctx(null)).username());
    }

    @Test
    void getDetailShouldRefuseUnpublishedArticle() {
        Article draft = createArticle("M14草稿" + UUID.randomUUID(), ArticleService.STATUS_DRAFT);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> articleTools.getArticleDetail(draft.getId()));
        assertTrue(ex.getMessage().contains("不存在或未发布"),
                "草稿不应通过详情工具暴露给模型，实际消息：" + ex.getMessage());
    }

    @Test
    void getDetailShouldRejectNullId() {
        assertThrows(IllegalArgumentException.class, () -> articleTools.getArticleDetail(null));
    }

    @Test
    void listCategoriesShouldReturnOnlyEnabledOnes() {
        List<CategoryBrief> categories = articleTools.listCategories();

        assertFalse(categories.isEmpty(), "本地库应至少有一个启用板块");
        for (CategoryBrief category : categories) {
            assertNotNull(category.id());
            assertNotNull(category.name());
        }
    }

    // ==================== 写操作工具：收藏 ====================

    @Test
    void favoriteShouldPersistAndBeQueryable() {
        Article article = createArticle("M14收藏" + UUID.randomUUID(), ArticleService.STATUS_PUBLISHED);
        Long userId = createUser("m14fav");

        ActionResult result = interactionTools.favoriteArticle(article.getId(), ctx(userId));

        assertTrue(result.success(), "收藏应成功：" + result.message());
        assertEquals(article.getId(), result.targetId());

        // 通过既有 Service 的收藏列表验证确实写入了
        List<Article> favorites = articleServicePageFavorites(userId);
        assertTrue(favorites.stream().anyMatch(a -> a.getId().equals(article.getId())),
                "收藏应出现在用户的收藏列表中");
    }

    @Test
    void favoriteShouldFailForUnpublishedArticle() {
        Article draft = createArticle("M14收藏草稿" + UUID.randomUUID(), ArticleService.STATUS_DRAFT);

        ActionResult result = interactionTools.favoriteArticle(draft.getId(), ctx(createUser("m14favdraft")));

        assertFalse(result.success(), "草稿不应可收藏");
        assertTrue(result.message().contains("收藏失败"), "应返回明确的失败原因");
    }

    @Test
    void favoriteShouldRejectMissingArguments() {
        assertFalse(interactionTools.favoriteArticle(null, ctx(1L)).success(), "文章 id 为空应被拒绝");
        assertFalse(interactionTools.favoriteArticle(1L, ctx(null)).success(), "缺少用户身份应被拒绝");
    }

    // ==================== 写操作工具：点赞 ====================

    @Test
    void likeShouldIncreaseHotScoreWhenLikeSucceeds() {
        Article article = createArticle("M14点赞" + UUID.randomUUID(), ArticleService.STATUS_PUBLISHED);
        Long userId = createUser("m14like");
        when(redisService.like(article.getId(), userId)).thenReturn(true);
        when(redisService.getLikeCount(article.getId())).thenReturn(7L);

        ActionResult result = interactionTools.likeArticle(article.getId(), ctx(userId));

        assertTrue(result.success());
        assertTrue(result.message().contains("7"), "应把最新点赞数告知模型");
        // 关键副作用：与站内点赞接口一致，成功点赞要提升热榜权重
        verify(redisService).increaseHot(article.getId(), 3);
    }

    @Test
    void likeShouldNotIncreaseHotScoreWhenAlreadyLiked() {
        Article article = createArticle("M14重复点赞" + UUID.randomUUID(), ArticleService.STATUS_PUBLISHED);
        Long userId = createUser("m14like2");
        when(redisService.like(article.getId(), userId)).thenReturn(false);
        when(redisService.getLikeCount(article.getId())).thenReturn(7L);

        ActionResult result = interactionTools.likeArticle(article.getId(), ctx(userId));

        assertTrue(result.success());
        assertTrue(result.message().contains("已经点过赞"), "重复点赞应明确告知，而不是报错");
        // 重复点赞不应重复加热，否则热榜会被刷
        verify(redisService, never()).increaseHot(article.getId(), 3);
    }

    @Test
    void likeShouldFailForUnpublishedArticleWithoutTouchingRedis() {
        Article draft = createArticle("M14点赞草稿" + UUID.randomUUID(), ArticleService.STATUS_DRAFT);

        ActionResult result = interactionTools.likeArticle(draft.getId(), ctx(createUser("m14likedraft")));

        assertFalse(result.success());
        verify(redisService, never()).like(anyLong(), anyLong());
    }

    // ==================== 写操作工具：关注 ====================

    @Test
    void followShouldSucceedAndBeReflectedInStats() {
        Long followerId = createUser("m14follower");
        Long targetId = createUser("m14target");

        ActionResult result = interactionTools.followUser(targetId, ctx(followerId));

        assertTrue(result.success(), "关注应成功：" + result.message());
        var stats = interactionTools.getFollowStats(targetId, ctx(followerId));
        assertEquals(1L, stats.followerCount(), "目标用户粉丝数应为 1");
        assertTrue(stats.followedByCurrentUser(), "应标记当前用户已关注");
        assertNotNull(stats.username(), "应返回被关注用户的名称");
    }

    @Test
    void followShouldRejectSelfFollow() {
        Long userId = createUser("m14self");

        ActionResult result = interactionTools.followUser(userId, ctx(userId));

        assertFalse(result.success());
        assertTrue(result.message().contains("不能关注自己"));
    }

    @Test
    void followStatsShouldBeQueryableWithoutCurrentUser() {
        Long targetId = createUser("m14stats");

        var stats = interactionTools.getFollowStats(targetId, ctx(null));

        assertEquals(targetId, stats.userId());
        assertEquals(0L, stats.followingCount());
        assertEquals(0L, stats.followerCount());
    }

    // ==================== 写操作工具：创建草稿 ====================

    @Test
    void createDraftShouldPersistAsDraftNotPublished() {
        Long categoryId = anyCategoryId();
        Long userId = createUser("m14drafter");

        ActionResult result = articleTools.createDraftArticle(
                "M14 工具创建的草稿", "正文内容", categoryId, ctx(userId));

        assertTrue(result.success(), "创建草稿应成功：" + result.message());
        Article created = articleMapper.selectById(result.targetId());
        assertNotNull(created, "草稿应已落库");
        assertEquals(ArticleService.STATUS_DRAFT, created.getStatus(),
                "必须是草稿状态，不能直接发布或提交审核");
        assertEquals(userId, created.getUserId());
    }

    @Test
    void createDraftShouldFailWithoutRequiredFields() {
        Long categoryId = anyCategoryId();
        Long userId = createUser("m14draftbad");

        assertFalse(articleTools.createDraftArticle("  ", "正文", categoryId, ctx(userId)).success(),
                "空标题应被拒绝");
        assertFalse(articleTools.createDraftArticle("标题", "  ", categoryId, ctx(userId)).success(),
                "空正文应被拒绝");
        // 缺少用户身份：模拟 ToolContext 中没有任何上下文的情况
        assertFalse(articleTools.createDraftArticle("标题", "正文", categoryId,
                new ToolContext(new java.util.HashMap<>())).success(),
                "缺少用户身份应被拒绝");
    }

    // ==================== 辅助方法 ====================

    @Autowired
    private ArticleService articleService;

    private List<Article> articleServicePageFavorites(Long userId) {
        return articleService.pageFavoriteArticles(userId, 1, 50).getRecords();
    }

    /**
     * 构造工具上下文，模拟应用在调用模型时注入的"当前登录用户"。
     * 这一改动对应 M14 的修正：身份由应用注入，而不是由模型通过参数传入。
     */
    private ToolContext ctx(Long userId) {
        Map<String, Object> context = new HashMap<>();
        if (userId != null) {
            context.put(AgentContextKeys.USER_ID, userId);
        }
        return new ToolContext(context);
    }

    private Long anyCategoryId() {
        return categoryMapper.selectList(null).get(0).getId();
    }

    /**
     * 创建真实用户。
     *
     * 关注、点赞、收藏等操作底层会校验"用户/文章是否存在"，所以测试不能使用虚构的 userId，
     * 否则拿到的是"用户不存在"这种正确但非预期的失败。
     */
    private Long createUser(String namePrefix) {
        User user = new User();
        user.setUsername(namePrefix + UUID.randomUUID().toString().substring(0, 8));
        user.setPassword("$2a$10$testplaceholderpasswordhashxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
        user.setRole("USER");
        user.setStatus(1);
        userMapper.insert(user);
        return user.getId();
    }

    private Article createArticle(String title, String status) {
        Article article = new Article();
        article.setTitle(title);
        article.setContent("M14 工具测试正文：" + title);
        article.setUserId(84900L);
        article.setCategoryId(anyCategoryId());
        article.setStatus(status);
        article.setViewCount(0);
        article.setLikeCount(0);
        articleMapper.insert(article);
        return article;
    }
}

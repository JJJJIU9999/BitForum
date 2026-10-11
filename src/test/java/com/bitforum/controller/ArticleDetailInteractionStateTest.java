package com.bitforum.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.bitforum.entity.Article;
import com.bitforum.entity.ArticleFavorite;
import com.bitforum.entity.Category;
import com.bitforum.mapper.ArticleFavoriteMapper;
import com.bitforum.mapper.ArticleMapper;
import com.bitforum.mapper.CategoryMapper;
import com.bitforum.service.ArticleService;
import com.bitforum.service.RedisService;
import com.bitforum.util.JwtUtil;

/**
 * 文章详情接口的交互状态测试。
 *
 * <p>背景：详情接口过去只返回点赞数/收藏数，不返回"当前用户是否已点赞/已收藏"，
 * 于是前端只能把"取消点赞/取消收藏"塞进另一个菜单，同一个功能被拆成两个入口。
 *
 * <p>这个测试锁定新契约 {@code likedByCurrentUser} / {@code favoritedByCurrentUser}：
 * <ol>
 *   <li><b>匿名必须是确定的 false</b>，而不是 null —— 未登录访客的状态是"确定没操作过"；</li>
 *   <li>匿名不能因为 Redis 里恰好有别人的点赞就泄漏出 true；</li>
 *   <li>登录用户返回真实状态（点赞查 Redis Set，收藏查 article_favorite 表）；</li>
 *   <li>原有可见性与统计字段（status / viewCount / likeCount / favoriteCount）不受影响。</li>
 * </ol>
 *
 * <p>Redis 用 {@link MockitoBean} 替换：本测试关心的是"状态如何随登录身份和存储内容变化"，
 * 而不是 Redis 本身是否可用，替换后也不需要在真实 Redis 里留下测试数据。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ArticleDetailInteractionStateTest {

    private static final Long READER_ID = 88601L;
    private static final String READER_TOKEN = "interaction-reader-token";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ArticleMapper articleMapper;
    @Autowired
    private ArticleFavoriteMapper articleFavoriteMapper;
    @Autowired
    private CategoryMapper categoryMapper;

    @MockitoBean
    private JwtUtil jwtUtil;
    @MockitoBean
    private RedisService redisService;

    @BeforeEach
    void stubRedisCounters() {
        // 详情接口对已发布文章会叠加 Redis 统计，这里给出确定值，避免依赖真实 Redis 内容
        when(redisService.getViews(anyLong())).thenReturn(0L);
        when(redisService.getLikeCount(anyLong())).thenReturn(0L);
        when(redisService.hasLiked(anyLong(), anyLong())).thenReturn(false);
    }

    // ==================== 匿名访问 ====================

    @Test
    void anonymousVisitorShouldReceiveBothStatesAsFalse() throws Exception {
        Article published = createArticle(ArticleService.STATUS_PUBLISHED);

        mockMvc.perform(get("/api/article/detail")
                        .param("articleId", String.valueOf(published.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.likedByCurrentUser").value(false))
                .andExpect(jsonPath("$.data.favoritedByCurrentUser").value(false));
    }

    @Test
    void anonymousVisitorShouldNotLeakLikedStateFromSomeoneElse() throws Exception {
        Article published = createArticle(ArticleService.STATUS_PUBLISHED);
        // 即便这篇文章在 Redis 里有别人的点赞记录，匿名访客也必须是 false
        when(redisService.hasLiked(any(), any())).thenReturn(true);

        mockMvc.perform(get("/api/article/detail")
                        .param("articleId", String.valueOf(published.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.likedByCurrentUser").value(false))
                .andExpect(jsonPath("$.data.favoritedByCurrentUser").value(false));
    }

    // ==================== 点赞状态 ====================

    @Test
    void likedArticleShouldReportLikedByCurrentUser() throws Exception {
        Article published = createArticle(ArticleService.STATUS_PUBLISHED);
        when(jwtUtil.getUserId(READER_TOKEN)).thenReturn(READER_ID);
        when(redisService.hasLiked(published.getId(), READER_ID)).thenReturn(true);

        mockMvc.perform(get("/api/article/detail")
                        .param("articleId", String.valueOf(published.getId()))
                        .header("Authorization", "Bearer " + READER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.likedByCurrentUser").value(true))
                .andExpect(jsonPath("$.data.favoritedByCurrentUser").value(false));
    }

    @Test
    void notLikedArticleShouldReportLikedByCurrentUserAsFalse() throws Exception {
        Article published = createArticle(ArticleService.STATUS_PUBLISHED);
        when(jwtUtil.getUserId(READER_TOKEN)).thenReturn(READER_ID);

        mockMvc.perform(get("/api/article/detail")
                        .param("articleId", String.valueOf(published.getId()))
                        .header("Authorization", "Bearer " + READER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.likedByCurrentUser").value(false));
    }

    // ==================== 收藏状态 ====================

    @Test
    void favoritedArticleShouldReportFavoritedByCurrentUser() throws Exception {
        Article published = createArticle(ArticleService.STATUS_PUBLISHED);
        createFavorite(published.getId(), READER_ID);
        when(jwtUtil.getUserId(READER_TOKEN)).thenReturn(READER_ID);

        mockMvc.perform(get("/api/article/detail")
                        .param("articleId", String.valueOf(published.getId()))
                        .header("Authorization", "Bearer " + READER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.favoritedByCurrentUser").value(true))
                .andExpect(jsonPath("$.data.likedByCurrentUser").value(false));
    }

    @Test
    void favoritedArticleShouldNotReportForAnotherUser() throws Exception {
        Article published = createArticle(ArticleService.STATUS_PUBLISHED);
        createFavorite(published.getId(), 88699L);
        when(jwtUtil.getUserId(READER_TOKEN)).thenReturn(READER_ID);

        mockMvc.perform(get("/api/article/detail")
                        .param("articleId", String.valueOf(published.getId()))
                        .header("Authorization", "Bearer " + READER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.favoritedByCurrentUser").value(false));
    }

    @Test
    void notFavoritedArticleShouldReportFavoritedByCurrentUserAsFalse() throws Exception {
        Article published = createArticle(ArticleService.STATUS_PUBLISHED);
        when(jwtUtil.getUserId(READER_TOKEN)).thenReturn(READER_ID);

        mockMvc.perform(get("/api/article/detail")
                        .param("articleId", String.valueOf(published.getId()))
                        .header("Authorization", "Bearer " + READER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.favoritedByCurrentUser").value(false));
    }

    // ==================== 不破坏原有行为 ====================

    @Test
    void publishedDetailShouldKeepVisibilityAndStatFields() throws Exception {
        Article published = createArticle(ArticleService.STATUS_PUBLISHED);
        createFavorite(published.getId(), READER_ID);
        when(jwtUtil.getUserId(READER_TOKEN)).thenReturn(READER_ID);
        when(redisService.getViews(published.getId())).thenReturn(17L);
        when(redisService.getLikeCount(published.getId())).thenReturn(5L);

        mockMvc.perform(get("/api/article/detail")
                        .param("articleId", String.valueOf(published.getId()))
                        .header("Authorization", "Bearer " + READER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.title").value(published.getTitle()))
                .andExpect(jsonPath("$.data.status").value(ArticleService.STATUS_PUBLISHED))
                .andExpect(jsonPath("$.data.viewCount").value(17))
                .andExpect(jsonPath("$.data.likeCount").value(5))
                .andExpect(jsonPath("$.data.favoriteCount").value(1));
    }

    @Test
    void unpublishedDraftShouldStillBeVisibleOnlyToItsAuthor() throws Exception {
        Article ownDraft = createOwnArticle(ArticleService.STATUS_DRAFT, READER_ID);

        // 匿名：草稿不可见（原有规则不变）
        mockMvc.perform(get("/api/article/detail")
                        .param("articleId", String.valueOf(ownDraft.getId())))
                .andExpect(status().isNotFound());

        // 作者本人：可读，且交互状态仍是确定的 false —— 未公开文章不参与公开互动
        when(jwtUtil.getUserId(READER_TOKEN)).thenReturn(READER_ID);
        mockMvc.perform(get("/api/article/detail")
                        .param("articleId", String.valueOf(ownDraft.getId()))
                        .header("Authorization", "Bearer " + READER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.likedByCurrentUser").value(false))
                .andExpect(jsonPath("$.data.favoritedByCurrentUser").value(false));
    }

    // ==================== 辅助方法 ====================

    private Article createArticle(String status) {
        return createOwnArticle(status, 88501L);
    }

    private Article createOwnArticle(String status, Long ownerId) {
        Category category = categoryMapper.selectList(null).get(0);
        Article article = new Article();
        article.setTitle("交互状态测试-" + status + "-" + UUID.randomUUID().toString().substring(0, 6));
        article.setContent("用于验证详情接口点赞/收藏状态的测试正文");
        article.setUserId(ownerId);
        article.setCategoryId(category.getId());
        article.setStatus(status);
        article.setViewCount(0);
        article.setLikeCount(0);
        articleMapper.insert(article);
        return article;
    }

    private void createFavorite(Long articleId, Long userId) {
        ArticleFavorite favorite = new ArticleFavorite();
        favorite.setArticleId(articleId);
        favorite.setUserId(userId);
        favorite.setCreateTime(LocalDateTime.now());
        articleFavoriteMapper.insert(favorite);
    }
}

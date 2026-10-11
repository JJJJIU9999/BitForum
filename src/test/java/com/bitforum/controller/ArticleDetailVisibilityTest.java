package com.bitforum.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.bitforum.entity.Article;
import com.bitforum.entity.Category;
import com.bitforum.mapper.ArticleMapper;
import com.bitforum.mapper.CategoryMapper;
import com.bitforum.service.ArticleService;
import com.bitforum.util.JwtUtil;

/**
 * 文章详情可见性测试。
 *
 * 修复的缺陷：作者在「我的文章」点开自己的草稿，详情页显示"文章不存在"。
 * 原因是详情接口只查已发布文章，而「我的文章」列表对所有状态都跳公开详情页
 * （该列表跳转是既有实现，并非 AI 模块引入；AI 创建草稿只是把这个既有缺陷暴露出来）。
 *
 * 修复后的可见性规则：
 * - 已发布（PUBLISHED）：所有人可见，匿名也可访问
 * - 草稿、待审核、已驳回、已下架：仅作者本人可见（需携带有效 Bearer Token）
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ArticleDetailVisibilityTest {

    private static final Long AUTHOR_ID = 88501L;
    private static final Long OTHER_ID = 88502L;
    private static final String OWNER_TOKEN = "visibility-owner-token";
    private static final String OTHER_TOKEN = "visibility-other-token";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ArticleService articleService;
    @Autowired
    private ArticleMapper articleMapper;
    @Autowired
    private CategoryMapper categoryMapper;

    @MockitoBean
    private JwtUtil jwtUtil;

    // ==================== Service 层可见性规则 ====================

    @Test
    void draftShouldBeReadableByItsAuthor() {
        Article draft = createArticle(ArticleService.STATUS_DRAFT);

        Article found = articleService.findReadableById(draft.getId(), AUTHOR_ID);

        assertNotNull(found, "作者应能查看自己的草稿");
        assertEquals(draft.getId(), found.getId());
    }

    @Test
    void draftShouldNotBeReadableByOthers() {
        Article draft = createArticle(ArticleService.STATUS_DRAFT);

        assertNull(articleService.findReadableById(draft.getId(), OTHER_ID),
                "其他用户不应能查看他人草稿");
    }

    @Test
    void draftShouldNotBeReadableAnonymously() {
        Article draft = createArticle(ArticleService.STATUS_DRAFT);

        assertNull(articleService.findReadableById(draft.getId(), null),
                "匿名用户不应能查看草稿");
    }

    @Test
    void rejectedArticleShouldBeReadableByItsAuthor() {
        Article rejected = createArticle(ArticleService.STATUS_REJECTED);

        assertNotNull(articleService.findReadableById(rejected.getId(), AUTHOR_ID),
                "作者应能查看自己被驳回的文章，以便修改后重新提交");
        assertNull(articleService.findReadableById(rejected.getId(), OTHER_ID),
                "其他用户不应能查看他人被驳回的文章");
    }

    @Test
    void pendingArticleShouldBeReadableByItsAuthorOnly() {
        Article pending = createArticle(ArticleService.STATUS_PENDING);

        assertNotNull(articleService.findReadableById(pending.getId(), AUTHOR_ID));
        assertNull(articleService.findReadableById(pending.getId(), OTHER_ID));
    }

    @Test
    void publishedArticleShouldBeReadableByAnyone() {
        Article published = createArticle(ArticleService.STATUS_PUBLISHED);

        assertNotNull(articleService.findReadableById(published.getId(), null),
                "已发布文章匿名也应可读");
        assertNotNull(articleService.findReadableById(published.getId(), OTHER_ID),
                "已发布文章其他用户可读");
        assertNotNull(articleService.findReadableById(published.getId(), AUTHOR_ID));
    }

    @Test
    void unknownArticleShouldReturnNull() {
        assertNull(articleService.findReadableById(999999999L, AUTHOR_ID));
        assertNull(articleService.findReadableById(999999999L, null));
    }

    // ==================== 接口层：作者可打开自己的草稿 ====================

    @Test
    void detailShouldReturnDraftToItsAuthorWithToken() throws Exception {
        Article draft = createArticle(ArticleService.STATUS_DRAFT);
        when(jwtUtil.getUserId(OWNER_TOKEN)).thenReturn(AUTHOR_ID);

        mockMvc.perform(get("/api/article/detail")
                        .param("articleId", String.valueOf(draft.getId()))
                        .header("Authorization", "Bearer " + OWNER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.title").value(draft.getTitle()))
                .andExpect(jsonPath("$.data.status").value(ArticleService.STATUS_DRAFT));
    }

    @Test
    void detailShouldReturn404ForDraftWithoutToken() throws Exception {
        Article draft = createArticle(ArticleService.STATUS_DRAFT);

        mockMvc.perform(get("/api/article/detail")
                        .param("articleId", String.valueOf(draft.getId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("文章不存在"));
    }

    @Test
    void detailShouldReturn404ForDraftForOtherUser() throws Exception {
        Article draft = createArticle(ArticleService.STATUS_DRAFT);
        when(jwtUtil.getUserId(OTHER_TOKEN)).thenReturn(OTHER_ID);

        mockMvc.perform(get("/api/article/detail")
                        .param("articleId", String.valueOf(draft.getId()))
                        .header("Authorization", "Bearer " + OTHER_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("文章不存在"));
    }

    @Test
    void detailShouldIgnoreInvalidTokenAndTreatAsAnonymous() throws Exception {
        Article draft = createArticle(ArticleService.STATUS_DRAFT);
        when(jwtUtil.getUserId("bad-token")).thenThrow(new RuntimeException("Token 无效"));

        mockMvc.perform(get("/api/article/detail")
                        .param("articleId", String.valueOf(draft.getId()))
                        .header("Authorization", "Bearer bad-token"))
                .andExpect(status().isNotFound());
    }

    // ==================== 接口层：不得破坏原有公开访问 ====================

    @Test
    void detailShouldStillServePublishedArticleAnonymously() throws Exception {
        Article published = createArticle(ArticleService.STATUS_PUBLISHED);

        mockMvc.perform(get("/api/article/detail")
                        .param("articleId", String.valueOf(published.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value(ArticleService.STATUS_PUBLISHED));
    }

    @Test
    void detailShouldReturn404ForUnknownArticle() throws Exception {
        mockMvc.perform(get("/api/article/detail").param("articleId", "999999999"))
                .andExpect(status().isNotFound());
    }

    // ==================== 辅助方法 ====================

    private Article createArticle(String status) {
        Category category = categoryMapper.selectList(null).get(0);
        Article article = new Article();
        article.setTitle("可见性测试-" + status + "-" + UUID.randomUUID().toString().substring(0, 6));
        article.setContent("用于验证详情接口可见性规则的测试正文");
        article.setUserId(AUTHOR_ID);
        article.setCategoryId(category.getId());
        article.setStatus(status);
        article.setViewCount(0);
        article.setLikeCount(0);
        articleMapper.insert(article);
        return article;
    }
}

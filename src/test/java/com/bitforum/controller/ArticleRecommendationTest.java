package com.bitforum.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.bitforum.ai.entity.AiRecommendLog;
import com.bitforum.ai.recommend.RecommendService;
import com.bitforum.ai.recommend.RecommendService.RecommendRequest;
import com.bitforum.ai.recommend.RecommendService.RecommendResult;
import com.bitforum.ai.recommend.RecommendService.RecommendedArticle;
import com.bitforum.util.JwtUtil;

/**
 * 相关推荐接口测试（M17）。
 *
 * <p>重点验证三件事：
 * <ol>
 *   <li><b>匿名可访问</b>：这是 M17 的产品决策 —— 文章详情页是公开页，
 *       相关推荐不能要求先登录；匿名时 userId 必须为 null（服务端据此退化为两路召回）；</li>
 *   <li><b>limit 有上限</b>：防止前端把它当列表接口用；</li>
 *   <li><b>失败不报错</b>：推荐是增强能力，底层异常时应返回空列表而不是 5xx。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ArticleRecommendationTest {

    private static final String TOKEN = "recommend-test-token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RecommendService recommendService;

    @MockitoBean
    private JwtUtil jwtUtil;

    @Test
    void anonymousVisitorShouldReceiveRecommendations() throws Exception {
        when(recommendService.recommend(any())).thenReturn(resultWithOneArticle());

        mockMvc.perform(get("/api/article/recommendations").param("articleId", "87"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].articleId").value(85))
                .andExpect(jsonPath("$.data[0].title").value("Spring Boot 论坛项目实践"))
                .andExpect(jsonPath("$.data[0].reason").value("与你正在看的这篇主题相近"));

        ArgumentCaptor<RecommendRequest> captor = ArgumentCaptor.forClass(RecommendRequest.class);
        verify(recommendService).recommend(captor.capture());
        assertNull(captor.getValue().userId(), "匿名请求不应带用户身份（服务端据此退化为两路召回）");
        assertEquals(87L, captor.getValue().sourceArticleId());
        assertEquals(AiRecommendLog.SCENE_ARTICLE_DETAIL, captor.getValue().scene());
        assertEquals(5, captor.getValue().topN(), "缺省 limit 应为 5");
    }

    @Test
    void loggedInUserShouldBeIdentifiedForPersonalisation() throws Exception {
        when(jwtUtil.getUserId(TOKEN)).thenReturn(13L);
        when(recommendService.recommend(any())).thenReturn(emptyResult());

        mockMvc.perform(get("/api/article/recommendations")
                        .param("articleId", "87")
                        .param("limit", "3")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk());

        ArgumentCaptor<RecommendRequest> captor = ArgumentCaptor.forClass(RecommendRequest.class);
        verify(recommendService).recommend(captor.capture());
        assertEquals(13L, captor.getValue().userId());
        assertEquals(3, captor.getValue().topN());
    }

    /** 无效 Token 不应报错，按匿名处理（与详情接口的既有行为一致）。 */
    @Test
    void invalidTokenShouldFallBackToAnonymous() throws Exception {
        when(jwtUtil.getUserId(TOKEN)).thenThrow(new IllegalArgumentException("token 无效"));
        when(recommendService.recommend(any())).thenReturn(emptyResult());

        mockMvc.perform(get("/api/article/recommendations")
                        .param("articleId", "87")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk());

        ArgumentCaptor<RecommendRequest> captor = ArgumentCaptor.forClass(RecommendRequest.class);
        verify(recommendService).recommend(captor.capture());
        assertNull(captor.getValue().userId());
    }

    @Test
    void limitShouldBeCappedToMax() throws Exception {
        when(recommendService.recommend(any())).thenReturn(emptyResult());

        mockMvc.perform(get("/api/article/recommendations")
                        .param("articleId", "87")
                        .param("limit", "999"))
                .andExpect(status().isOk());

        ArgumentCaptor<RecommendRequest> captor = ArgumentCaptor.forClass(RecommendRequest.class);
        verify(recommendService).recommend(captor.capture());
        assertEquals(10, captor.getValue().topN(), "limit 应被截到上限 10");
    }

    /** 推荐失败不能把文章页拖下水：返回空列表而不是 5xx，且**不暴露内部异常消息**。 */
    @Test
    void shouldReturnEmptyListWhenRecommendationFails() throws Exception {
        when(recommendService.recommend(any()))
                .thenThrow(new IllegalStateException("Redis 连接中断：redis://198.51.100.5:6379"));

        mockMvc.perform(get("/api/article/recommendations").param("articleId", "87"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty())
                // M4：这是公开接口，只允许固定公开文案
                .andExpect(jsonPath("$.message").value("相关推荐暂不可用"));
    }

    private RecommendResult resultWithOneArticle() {
        return new RecommendResult(
                List.of(new RecommendedArticle(85L, "Spring Boot 论坛项目实践", 16L, "技术",
                        1, 0.0325, List.of("vector"), "{\"vector\":1}", "与你正在看的这篇主题相近")),
                false, "deepseek-flash", 120L, 3);
    }

    private RecommendResult emptyResult() {
        return new RecommendResult(List.of(), true, "deepseek-flash", 5L, 0);
    }
}

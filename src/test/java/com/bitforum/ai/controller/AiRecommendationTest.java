package com.bitforum.ai.controller;

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

import com.bitforum.ai.recommend.RecommendService;
import com.bitforum.ai.recommend.RecommendService.RecommendRequest;
import com.bitforum.ai.recommend.RecommendService.RecommendResult;
import com.bitforum.ai.recommend.RecommendService.RecommendedArticle;
import com.bitforum.entity.User;
import com.bitforum.service.UserService;
import com.bitforum.util.JwtUtil;

/**
 * AI 助手「相关帖子」接口测试（M17）。
 *
 * <p>验证三件事：必须登录、提问文本被正确传给召回层、推荐失败时不影响对话（返回空列表）。
 * 真实的召唤与排序由 RecommendService 自己的测试覆盖，这里只测接口契约。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AiRecommendationTest {

    private static final String TOKEN = "ai-recommend-token";
    private static final Long USER_ID = 13L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtUtil jwtUtil;
    @MockitoBean
    private UserService userService;
    @MockitoBean
    private RecommendService recommendService;

    @Test
    void shouldRequireLogin() throws Exception {
        mockMvc.perform(get("/api/ai/recommendations").param("query", "Redis 缓存穿透怎么处理"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldPassQueryToRecallAndReturnArticles() throws Exception {
        stubLoggedIn();
        when(recommendService.recommend(any())).thenReturn(new RecommendResult(
                List.of(new RecommendedArticle(87L, "Redis 热点数据同步方案", 16L, "技术",
                        1, 0.0325, List.of("vector"), "{\"vector\":1}", "与你的问题主题相近")),
                false, "deepseek-flash", 120L, 3));

        mockMvc.perform(get("/api/ai/recommendations")
                        .param("query", "Redis 缓存穿透怎么处理")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].articleId").value(87))
                .andExpect(jsonPath("$.data[0].reason").value("与你的问题主题相近"));

        ArgumentCaptor<RecommendRequest> captor = ArgumentCaptor.forClass(RecommendRequest.class);
        verify(recommendService).recommend(captor.capture());
        RecommendRequest request = captor.getValue();
        // AI 助手场景：没有来源文章，靠提问文本做内容相似召回
        org.junit.jupiter.api.Assertions.assertNull(request.sourceArticleId());
        org.junit.jupiter.api.Assertions.assertEquals("Redis 缓存穿透怎么处理", request.queryText());
        org.junit.jupiter.api.Assertions.assertEquals(USER_ID, request.userId());
    }

    @Test
    void shouldCapLimit() throws Exception {
        stubLoggedIn();
        when(recommendService.recommend(any())).thenReturn(new RecommendResult(
                List.of(), true, "deepseek-flash", 5L, 0));

        mockMvc.perform(get("/api/ai/recommendations")
                        .param("query", "并发")
                        .param("limit", "999")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk());

        ArgumentCaptor<RecommendRequest> captor = ArgumentCaptor.forClass(RecommendRequest.class);
        verify(recommendService).recommend(captor.capture());
        org.junit.jupiter.api.Assertions.assertEquals(10, captor.getValue().topN());
    }

    /** 推荐失败不能影响对话：接口返回空列表而不是 5xx，且**不暴露内部异常消息**。 */
    @Test
    void shouldReturnEmptyListWhenRecommendationFails() throws Exception {
        stubLoggedIn();
        when(recommendService.recommend(any()))
                .thenThrow(new IllegalStateException("Redis 连接中断：redis://10.0.0.5:6379"));

        mockMvc.perform(get("/api/ai/recommendations")
                        .param("query", "并发")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty())
                // M4：响应只允许固定公开文案，不得回显异常消息
                .andExpect(jsonPath("$.message").value("暂无相关推荐"));
    }

    private void stubLoggedIn() {
        User user = new User();
        user.setId(USER_ID);
        user.setUsername("tester");
        user.setRole("USER");
        user.setStatus(1);
        when(jwtUtil.getUserId(TOKEN)).thenReturn(USER_ID);
        when(userService.findById(USER_ID)).thenReturn(user);
    }
}

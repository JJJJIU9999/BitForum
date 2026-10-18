package com.bitforum.controller;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bitforum.entity.Article;
import com.bitforum.entity.User;
import com.bitforum.service.ArticleService;
import com.bitforum.service.NotificationService;
import com.bitforum.service.RedisService;
import com.bitforum.service.UserService;
import com.bitforum.util.JwtUtil;

@SpringBootTest
@AutoConfigureMockMvc
class ArticleControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtUtil jwtUtil;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private ArticleService articleService;
    @MockitoBean
    private RedisService redisService;
    @MockitoBean
    private NotificationService notificationService;

    @Test
    void favoriteShouldRequireLogin() throws Exception {
        mockMvc.perform(post("/api/article/favorite")
                .param("articleId", "1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void searchShouldAllowAnonymousUser() throws Exception {
        when(articleService.searchPublishedArticles("关键词", null, 1, 10, null))
                .thenReturn(new Page<Article>(1, 10));

        mockMvc.perform(get("/api/article/search")
                .param("keyword", "关键词"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("搜索成功"));
    }
    @Test
    void likeShouldCreateNotificationOnlyWhenFirstLiked() throws Exception {
        Article article = new Article();
        article.setId(1L);
        article.setUserId(60L);
        article.setTitle("M4 like notification");
        mockEnabledUser("user-token", 50L);
        when(articleService.findPublishedById(1L)).thenReturn(article);
        when(redisService.like(1L, 50L)).thenReturn(true);
        when(redisService.getLikeCount(1L)).thenReturn(1L);

        mockMvc.perform(post("/api/article/like")
                .param("articleId", "1")
                .header("Authorization", "Bearer user-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(notificationService).notifyLike(article, 50L);
    }

    @Test
    void repeatedLikeShouldNotCreateNotification() throws Exception {
        Article article = new Article();
        article.setId(2L);
        article.setUserId(61L);
        article.setTitle("M4 repeated like");
        mockEnabledUser("user-token", 51L);
        when(articleService.findPublishedById(2L)).thenReturn(article);
        when(redisService.like(2L, 51L)).thenReturn(false);
        when(redisService.getLikeCount(2L)).thenReturn(1L);

        mockMvc.perform(post("/api/article/like")
                .param("articleId", "2")
                .header("Authorization", "Bearer user-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(notificationService, never()).notifyLike(article, 51L);
    }

    @Test
    void disabledUserShouldNotAccessProtectedArticleApi() throws Exception {
        User disabledUser = new User();
        disabledUser.setId(52L);
        disabledUser.setStatus(UserService.STATUS_DISABLED);

        when(jwtUtil.getUserId("disabled-token")).thenReturn(52L);
        when(userService.findById(52L)).thenReturn(disabledUser);

        mockMvc.perform(post("/api/article/like")
                .param("articleId", "1")
                .header("Authorization", "Bearer disabled-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("账号不可用"));

        verify(articleService, never()).findPublishedById(1L);
        verify(redisService, never()).like(1L, 52L);
    }

    @Test
    void userShouldNotLikeOwnArticle() throws Exception {
        Article article = new Article();
        article.setId(3L);
        article.setUserId(53L);
        article.setTitle("self like should fail");
        mockEnabledUser("user-token", 53L);
        when(articleService.findPublishedById(3L)).thenReturn(article);

        mockMvc.perform(post("/api/article/like")
                .param("articleId", "3")
                .header("Authorization", "Bearer user-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("不能给自己的文章点赞"));

        verify(redisService, never()).like(3L, 53L);
        verify(redisService, never()).increaseHot(3L, 3);
        verify(notificationService, never()).notifyLike(article, 53L);
    }

    @Test
    void updateShouldReturnServiceBusinessErrorMessage() throws Exception {
        Article article = new Article();
        article.setId(4L);
        article.setUserId(54L);
        mockEnabledUser("user-token", 54L);
        when(articleService.findById(4L)).thenReturn(article);
        doThrow(new com.bitforum.exception.BusinessException("只能修改草稿或被驳回文章"))
                .when(articleService).updateWithCoverId(54L, 4L, "新标题", "新内容", null, null);

        mockMvc.perform(put("/api/article/update")
                .header("Authorization", "Bearer user-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"articleId\":4,\"title\":\"新标题\",\"content\":\"新内容\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("只能修改草稿或被驳回文章"));
    }

    @Test
    void deleteShouldSanitizeUnknownServiceFailure() throws Exception {
        Article article = new Article();
        article.setId(5L);
        article.setUserId(55L);
        mockEnabledUser("user-token", 55L);
        when(articleService.findById(5L)).thenReturn(article);
        doThrow(new RuntimeException("sensitive delete failure"))
                .when(articleService).delete(55L, 5L);

        mockMvc.perform(delete("/api/article/delete")
                .param("articleId", "5")
                .header("Authorization", "Bearer user-token"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("服务器内部错误，请稍后尝试"));
    }

    @Test
    void missingArticleDetailShouldReturnNotFound() throws Exception {
        when(articleService.findPublishedById(404L)).thenReturn(null);

        mockMvc.perform(get("/api/article/detail").param("articleId", "404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("文章不存在"));
    }

    @Test
    void duplicateFavoriteShouldReturnConflict() throws Exception {
        mockEnabledUser("user-token", 56L);
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "不能重复收藏同一篇文章"))
                .when(articleService).favoriteArticle(56L, 6L);

        mockMvc.perform(post("/api/article/favorite")
                .param("articleId", "6")
                .header("Authorization", "Bearer user-token"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("不能重复收藏同一篇文章"));
    }

    @Test
    void unexpectedExceptionShouldReturnInternalServerError() throws Exception {
        when(articleService.searchPublishedArticles("boom", null, 1, 10, null))
                .thenThrow(new RuntimeException("sensitive detail"));

        mockMvc.perform(get("/api/article/search").param("keyword", "boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("服务器内部错误，请稍后尝试"));
    }

    private void mockEnabledUser(String token, Long userId) {
        User user = new User();
        user.setId(userId);
        user.setStatus(UserService.STATUS_ENABLED);

        when(jwtUtil.getUserId(token)).thenReturn(userId);
        when(userService.findById(userId)).thenReturn(user);
    }
}

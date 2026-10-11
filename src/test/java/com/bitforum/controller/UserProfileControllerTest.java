package com.bitforum.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bitforum.dto.PublicUserProfileResponse;
import com.bitforum.dto.UserProfileResponse;
import com.bitforum.dto.UserProfileUpdateRequest;
import com.bitforum.entity.Article;
import com.bitforum.entity.User;
import com.bitforum.service.ArticleService;
import com.bitforum.service.UserService;
import com.bitforum.util.JwtUtil;

@SpringBootTest
@AutoConfigureMockMvc
class UserProfileControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtUtil jwtUtil;
    @MockitoBean
    private UserService userService;
    @MockitoBean
    private ArticleService articleService;

    @Test
    void currentProfileShouldRequireLogin() throws Exception {
        mockMvc.perform(get("/api/user/profile"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("请先登录"));
    }

    @Test
    void currentUserShouldGetProfileWithoutPassword() throws Exception {
        mockEnabledUser("user-token", 81001L);
        UserProfileResponse response = new UserProfileResponse();
        response.setUserId(81001L);
        response.setUsername("profile-user");
        response.setNickname("资料昵称");
        response.setBio("资料简介");
        response.setPublishedArticleCount(2L);
        response.setFavoriteCount(3L);
        when(userService.getCurrentProfile(81001L)).thenReturn(response);

        mockMvc.perform(get("/api/user/profile")
                .header("Authorization", "Bearer user-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("个人资料查询成功"))
                .andExpect(jsonPath("$.data.userId").value(81001))
                .andExpect(jsonPath("$.data.nickname").value("资料昵称"))
                .andExpect(jsonPath("$.data.publishedArticleCount").value(2))
                .andExpect(jsonPath("$.data.favoriteCount").value(3))
                .andExpect(jsonPath("$.data.password").doesNotExist());
    }

    @Test
    void currentUserShouldUpdateProfile() throws Exception {
        mockEnabledUser("user-token", 81002L);
        UserProfileResponse response = new UserProfileResponse();
        response.setUserId(81002L);
        response.setAvatar("/api/images/8/content");
        response.setNickname("新昵称");
        response.setBio("新的个人简介");
        when(userService.updateProfile(eq(81002L), any(UserProfileUpdateRequest.class))).thenReturn(response);

        mockMvc.perform(put("/api/user/profile")
                .header("Authorization", "Bearer user-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "nickname": "新昵称",
                          "bio": "新的个人简介",
                          "role": "ADMIN",
                          "status": 0,
                          "password": "hack"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("个人资料更新成功"))
                .andExpect(jsonPath("$.data.avatar").value("/api/images/8/content"))
                .andExpect(jsonPath("$.data.nickname").value("新昵称"))
                .andExpect(jsonPath("$.data.bio").value("新的个人简介"))
                .andExpect(jsonPath("$.data.role").doesNotExist())
                .andExpect(jsonPath("$.data.status").doesNotExist())
                .andExpect(jsonPath("$.data.password").doesNotExist());
    }

    @Test
    void publicProfileShouldAllowAnonymousUser() throws Exception {
        PublicUserProfileResponse response = new PublicUserProfileResponse();
        response.setUserId(81003L);
        response.setUsername("public-user");
        response.setNickname("公开昵称");
        response.setStatus(UserService.STATUS_ENABLED);
        response.setPublishedArticleCount(4L);
        response.setFollowingCount(5L);
        response.setFollowerCount(6L);
        response.setFollowedByCurrentUser(false);
        when(userService.getPublicProfile(eq(81003L), isNull())).thenReturn(response);

        mockMvc.perform(get("/api/users/81003/profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("公开用户主页查询成功"))
                .andExpect(jsonPath("$.data.username").value("public-user"))
                .andExpect(jsonPath("$.data.status").value(UserService.STATUS_ENABLED))
                .andExpect(jsonPath("$.data.followingCount").value(5))
                .andExpect(jsonPath("$.data.followerCount").value(6))
                .andExpect(jsonPath("$.data.followedByCurrentUser").value(false))
                .andExpect(jsonPath("$.data.password").doesNotExist());
    }

    @Test
    void publicProfileShouldUseOptionalLoginUserWhenTokenExists() throws Exception {
        PublicUserProfileResponse response = new PublicUserProfileResponse();
        response.setUserId(81006L);
        response.setUsername("follow-target");
        response.setFollowedByCurrentUser(true);
        when(jwtUtil.getUserId("user-token")).thenReturn(81007L);
        when(userService.getPublicProfile(81006L, 81007L)).thenReturn(response);

        mockMvc.perform(get("/api/users/81006/profile")
                .header("Authorization", "Bearer user-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.followedByCurrentUser").value(true));
    }

    @Test
    void publicArticlesShouldAllowAnonymousUserAndReturnPage() throws Exception {
        User user = new User();
        user.setId(81004L);
        when(userService.findById(81004L)).thenReturn(user);

        Article article = new Article();
        article.setId(1L);
        article.setUserId(81004L);
        article.setStatus(ArticleService.STATUS_PUBLISHED);
        Page<Article> page = new Page<>(1, 10, 1);
        page.setRecords(List.of(article));
        when(articleService.pagePublishedArticlesByUser(81004L, 1, 10)).thenReturn(page);

        mockMvc.perform(get("/api/users/81004/articles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("用户公开文章分页查询成功"))
                .andExpect(jsonPath("$.data.records[0].id").value(1))
                .andExpect(jsonPath("$.data.records[0].status").value(ArticleService.STATUS_PUBLISHED));
    }

    @Test
    void publicArticlesShouldFailWhenUserMissing() throws Exception {
        when(userService.findById(81005L)).thenReturn(null);

        mockMvc.perform(get("/api/users/81005/articles"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("用户不存在"));
    }

    private void mockEnabledUser(String token, Long userId) {
        User user = new User();
        user.setId(userId);
        user.setStatus(UserService.STATUS_ENABLED);

        when(jwtUtil.getUserId(token)).thenReturn(userId);
        when(userService.findById(userId)).thenReturn(user);
    }
}

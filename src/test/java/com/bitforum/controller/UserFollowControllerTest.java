package com.bitforum.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bitforum.dto.FollowStatsResponse;
import com.bitforum.dto.FollowUserResponse;
import com.bitforum.entity.User;
import com.bitforum.service.UserFollowService;
import com.bitforum.service.UserService;
import com.bitforum.util.JwtUtil;

@SpringBootTest
@AutoConfigureMockMvc
class UserFollowControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtUtil jwtUtil;
    @MockitoBean
    private UserService userService;
    @MockitoBean
    private UserFollowService userFollowService;

    @Test
    void followShouldRequireLogin() throws Exception {
        mockMvc.perform(post("/api/users/2/follow"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        verify(userFollowService, never()).follow(eq(1L), eq(2L));
    }

    @Test
    void followShouldReturnStats() throws Exception {
        mockEnabledUser("user-token", 1L);
        FollowStatsResponse response = stats(2L, 3L, 4L, true);
        when(userFollowService.follow(1L, 2L)).thenReturn(response);

        mockMvc.perform(post("/api/users/2/follow")
                .header("Authorization", "Bearer user-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("关注成功"))
                .andExpect(jsonPath("$.data.followerCount").value(4))
                .andExpect(jsonPath("$.data.followedByCurrentUser").value(true));
    }

    @Test
    void followShouldReturnBusinessError() throws Exception {
        mockEnabledUser("user-token", 1L);
        when(userFollowService.follow(1L, 1L)).thenThrow(new com.bitforum.exception.BusinessException("不能关注自己"));

        mockMvc.perform(post("/api/users/1/follow")
                .header("Authorization", "Bearer user-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("不能关注自己"));
    }

    @Test
    void unfollowShouldRequireLogin() throws Exception {
        mockMvc.perform(delete("/api/users/2/follow"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void unfollowShouldReturnStats() throws Exception {
        mockEnabledUser("user-token", 1L);
        when(userFollowService.unfollow(1L, 2L)).thenReturn(stats(2L, 3L, 4L, false));

        mockMvc.perform(delete("/api/users/2/follow")
                .header("Authorization", "Bearer user-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("取消关注成功"))
                .andExpect(jsonPath("$.data.followedByCurrentUser").value(false));
    }

    @Test
    void followersShouldAllowAnonymousUser() throws Exception {
        FollowUserResponse follower = new FollowUserResponse();
        follower.setUserId(1L);
        follower.setUsername("follower");
        Page<FollowUserResponse> page = new Page<>(1, 10, 1);
        page.setRecords(List.of(follower));
        when(userFollowService.pageFollowers(2L, 1, 10)).thenReturn(page);

        mockMvc.perform(get("/api/users/2/followers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("粉丝列表查询成功"))
                .andExpect(jsonPath("$.data.records[0].username").value("follower"));
    }

    @Test
    void followingShouldAllowAnonymousUser() throws Exception {
        FollowUserResponse following = new FollowUserResponse();
        following.setUserId(3L);
        following.setUsername("following");
        Page<FollowUserResponse> page = new Page<>(1, 10, 1);
        page.setRecords(List.of(following));
        when(userFollowService.pageFollowing(2L, 1, 10)).thenReturn(page);

        mockMvc.perform(get("/api/users/2/following"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("关注列表查询成功"))
                .andExpect(jsonPath("$.data.records[0].username").value("following"));
    }

    @Test
    void followStatsShouldUseOptionalToken() throws Exception {
        when(jwtUtil.getUserId("user-token")).thenReturn(1L);
        when(userFollowService.getStats(2L, 1L)).thenReturn(stats(2L, 3L, 4L, true));

        mockMvc.perform(get("/api/users/2/follow-stats")
                .header("Authorization", "Bearer user-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.followedByCurrentUser").value(true));
    }

    private FollowStatsResponse stats(Long userId, Long followingCount, Long followerCount, boolean followedByCurrentUser) {
        FollowStatsResponse response = new FollowStatsResponse();
        response.setUserId(userId);
        response.setFollowingCount(followingCount);
        response.setFollowerCount(followerCount);
        response.setFollowedByCurrentUser(followedByCurrentUser);
        return response;
    }

    private void mockEnabledUser(String token, Long userId) {
        User user = new User();
        user.setId(userId);
        user.setStatus(UserService.STATUS_ENABLED);

        when(jwtUtil.getUserId(token)).thenReturn(userId);
        when(userService.findById(userId)).thenReturn(user);
    }
}


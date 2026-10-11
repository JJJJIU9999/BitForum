package com.bitforum.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bitforum.entity.Notification;
import com.bitforum.entity.User;
import com.bitforum.service.NotificationService;
import com.bitforum.service.UserService;
import com.bitforum.util.JwtUtil;

@SpringBootTest
@AutoConfigureMockMvc
class UserNotificationControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtUtil jwtUtil;
    @MockitoBean
    private UserService userService;
    @MockitoBean
    private NotificationService notificationService;

    @Test
    void notificationsShouldRequireLogin() throws Exception {
        mockMvc.perform(get("/api/user/notifications"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void userShouldPageOwnNotifications() throws Exception {
        mockEnabledUser("user-token", 71001L);
        when(notificationService.pageNotifications(71001L, 1, 10)).thenReturn(new Page<Notification>(1, 10));

        mockMvc.perform(get("/api/user/notifications")
                .header("Authorization", "Bearer user-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("通知分页查询成功"));
    }

    @Test
    void userShouldGetUnreadCount() throws Exception {
        mockEnabledUser("user-token", 71002L);
        when(notificationService.countUnread(71002L)).thenReturn(3L);

        mockMvc.perform(get("/api/user/notifications/unread-count")
                .header("Authorization", "Bearer user-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(3));
    }

    @Test
    void userShouldMarkOneNotificationRead() throws Exception {
        mockEnabledUser("user-token", 71003L);

        mockMvc.perform(put("/api/user/notifications/read")
                .param("notificationId", "9")
                .header("Authorization", "Bearer user-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(notificationService).markRead(71003L, 9L);
    }

    @Test
    void userShouldMarkAllNotificationsRead() throws Exception {
        mockEnabledUser("user-token", 71004L);

        mockMvc.perform(put("/api/user/notifications/read-all")
                .header("Authorization", "Bearer user-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(notificationService).markAllRead(71004L);
    }

    private void mockEnabledUser(String token, Long userId) {
        User user = new User();
        user.setId(userId);
        user.setStatus(UserService.STATUS_ENABLED);

        when(jwtUtil.getUserId(token)).thenReturn(userId);
        when(userService.findById(userId)).thenReturn(user);
    }
}

package com.bitforum.controller;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bitforum.dto.AdminUserResponse;
import com.bitforum.entity.User;
import com.bitforum.service.UserService;
import com.bitforum.service.UserService.UserStatusUpdateResult;
import com.bitforum.util.JwtUtil;

@SpringBootTest
@AutoConfigureMockMvc
class AdminUserControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtUtil jwtUtil;

    @MockitoBean
    private UserService userService;

    @Test
    void adminShouldPageUsersWithoutPassword() throws Exception {
        mockAdmin("admin-token", 30L);

        User user = new User();
        user.setId(1L);
        user.setUsername("normal-user");
        user.setPassword("password-hash-should-not-return");
        user.setRole("USER");
        user.setStatus(UserService.STATUS_ENABLED);

        Page<AdminUserResponse> page = new Page<>(1, 10, 1);
        page.setRecords(List.of(AdminUserResponse.from(user)));
        when(userService.pageUsers(1, 10)).thenReturn(page);

        // 用户列表必须走 AdminUserResponse DTO，所以响应里不应该有 password 字段。
        mockMvc.perform(get("/api/admin/user/page")
                .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records[0].userId").value(1))
                .andExpect(jsonPath("$.data.records[0].username").value("normal-user"))
                .andExpect(jsonPath("$.data.records[0].password").doesNotExist());
    }

    @Test
    void adminShouldDisableUser() throws Exception {
        mockAdmin("admin-token", 30L);
        when(userService.disableUser(30L, 1L)).thenReturn(UserStatusUpdateResult.SUCCESS);

        mockMvc.perform(put("/api/admin/user/disable")
                .param("userId", "1")
                .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("禁用用户成功"));
    }

    @Test
    void adminShouldNotDisableSelf() throws Exception {
        mockAdmin("admin-token", 30L);
        when(userService.disableUser(30L, 30L)).thenReturn(UserStatusUpdateResult.CANNOT_DISABLE_SELF);

        // 禁止管理员禁用自己，避免唯一后台账号被自己锁死。
        mockMvc.perform(put("/api/admin/user/disable")
                .param("userId", "30")
                .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("不能禁用当前管理员自己"));
    }

    @Test
    void adminShouldEnableUser() throws Exception {
        mockAdmin("admin-token", 30L);
        when(userService.enableUser(1L)).thenReturn(UserStatusUpdateResult.SUCCESS);

        mockMvc.perform(put("/api/admin/user/enable")
                .param("userId", "1")
                .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("启用用户成功"));
    }

    @Test
    void normalUserShouldNotManageUsersThroughAdminApi() throws Exception {
        User user = new User();
        user.setId(31L);
        user.setRole("USER");
        user.setStatus(UserService.STATUS_ENABLED);

        when(jwtUtil.getUserId("user-token")).thenReturn(31L);
        when(userService.findById(31L)).thenReturn(user);

        // 普通用户即使知道后台用户管理地址，也会先被 AdminInterceptor 拦截。
        mockMvc.perform(put("/api/admin/user/disable")
                .param("userId", "1")
                .header("Authorization", "Bearer user-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("无管理员权限"));
    }

    private void mockAdmin(String token, Long userId) {
        User admin = new User();
        admin.setId(userId);
        admin.setRole(UserService.ROLE_ADMIN);
        admin.setStatus(UserService.STATUS_ENABLED);

        when(jwtUtil.getUserId(token)).thenReturn(userId);
        when(userService.findById(userId)).thenReturn(admin);
    }
}

package com.bitforum.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.bitforum.dto.AdminDashboardSummaryResponse;
import com.bitforum.dto.DashboardUserStats;
import com.bitforum.entity.User;
import com.bitforum.exception.GlobalExceptionHandler;
import com.bitforum.exception.ResultHttpStatusAdvice;
import com.bitforum.interceptor.AdminInterceptor;
import com.bitforum.service.AdminDashboardService;
import com.bitforum.service.UserService;
import com.bitforum.util.JwtUtil;

/** Real controller, admin interceptor and advice; no DB, Redis, MQ or model context. */
class AdminDashboardControllerTest {
    private MockMvc mockMvc;
    private JwtUtil jwtUtil;
    private UserService userService;
    private AdminDashboardService adminDashboardService;

    @BeforeEach
    void setUp() {
        jwtUtil = mock(JwtUtil.class);
        userService = mock(UserService.class);
        adminDashboardService = mock(AdminDashboardService.class);
        AdminDashboardController controller = new AdminDashboardController();
        ReflectionTestUtils.setField(controller, "adminDashboardService", adminDashboardService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addMappedInterceptors(new String[] {"/api/admin/**"}, new AdminInterceptor(jwtUtil, userService))
                .setControllerAdvice(new GlobalExceptionHandler(), new ResultHttpStatusAdvice())
                .build();
    }

    @Test
    void adminShouldGetDashboardSummary() throws Exception {
        mockAdmin("admin-token", 76001L);

        DashboardUserStats userStats = new DashboardUserStats();
        userStats.setTotal(3L);
        userStats.setNormalUsers(2L);
        userStats.setAdmins(1L);

        AdminDashboardSummaryResponse response = new AdminDashboardSummaryResponse();
        response.setUserStats(userStats);
        when(adminDashboardService.summary()).thenReturn(response);

        mockMvc.perform(get("/api/admin/dashboard/summary")
                .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("管理员数据看板统计成功"))
                .andExpect(jsonPath("$.data.userStats.total").value(3))
                .andExpect(jsonPath("$.data.userStats.normalUsers").value(2))
                .andExpect(jsonPath("$.data.userStats.admins").value(1))
                .andExpect(jsonPath("$.data.userStats.password").doesNotExist());
    }

    @Test
    void normalUserShouldNotAccessDashboardSummary() throws Exception {
        User user = new User();
        user.setId(76002L);
        user.setRole("USER");
        user.setStatus(UserService.STATUS_ENABLED);

        when(jwtUtil.getUserId("user-token")).thenReturn(76002L);
        when(userService.findById(76002L)).thenReturn(user);

        mockMvc.perform(get("/api/admin/dashboard/summary")
                .header("Authorization", "Bearer user-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("无管理员权限"));
        verifyNoInteractions(adminDashboardService);
    }

    @Test
    void anonymousUserShouldNotAccessDashboardSummary() throws Exception {
        mockMvc.perform(get("/api/admin/dashboard/summary"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("请先登录"));
        verifyNoInteractions(adminDashboardService);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SQL SELECT secret_column FROM synthetic_private_table failed",
            "Cannot read /synthetic/private/dashboard/report.json",
            "Connection failed jdbc:mysql://127.0.0.1:23306/synthetic_private_db user=synthetic_user"
    })
    void internalFailureShouldReturnSafe500(String detail) throws Exception {
        mockAdmin("admin-token", 76001L);
        when(adminDashboardService.summary()).thenThrow(new RuntimeException(detail));

        mockMvc.perform(get("/api/admin/dashboard/summary")
                .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("服务器内部错误，请稍后尝试"))
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(content().string(not(containsString(detail))))
                .andExpect(content().string(not(containsString("SQL"))))
                .andExpect(content().string(not(containsString("/synthetic/private/"))))
                .andExpect(content().string(not(containsString("jdbc:mysql:"))))
                .andExpect(content().string(not(containsString("synthetic_user"))));
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

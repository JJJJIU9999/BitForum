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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bitforum.entity.ContentReport;
import com.bitforum.entity.User;
import com.bitforum.service.ContentReportService;
import com.bitforum.service.UserService;
import com.bitforum.util.JwtUtil;

@SpringBootTest
@AutoConfigureMockMvc
class AdminReportControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtUtil jwtUtil;
    @MockitoBean
    private UserService userService;
    @MockitoBean
    private ContentReportService contentReportService;

    @Test
    void adminShouldPageReportsByStatus() throws Exception {
        mockAdmin("admin-token", 73001L);
        when(contentReportService.pageAdminReports(1, 10, ContentReportService.STATUS_PENDING))
                .thenReturn(new Page<ContentReport>(1, 10));

        mockMvc.perform(get("/api/admin/reports")
                .param("status", ContentReportService.STATUS_PENDING)
                .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("管理员举报分页查询成功"));
    }

    @Test
    void adminShouldResolveReport() throws Exception {
        mockAdmin("admin-token", 73002L);

        mockMvc.perform(put("/api/admin/reports/resolve")
                .header("Authorization", "Bearer admin-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reportId\":5,\"handleResult\":\"举报成立\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("举报处理成功"));

        verify(contentReportService).resolve(5L, 73002L, "举报成立");
    }

    @Test
    void adminShouldRejectReport() throws Exception {
        mockAdmin("admin-token", 73003L);

        mockMvc.perform(put("/api/admin/reports/reject")
                .header("Authorization", "Bearer admin-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reportId\":6,\"handleResult\":\"举报不成立\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("举报驳回成功"));

        verify(contentReportService).reject(6L, 73003L, "举报不成立");
    }

    @Test
    void normalUserShouldNotAccessAdminReports() throws Exception {
        User user = new User();
        user.setId(73004L);
        user.setRole("USER");
        user.setStatus(UserService.STATUS_ENABLED);

        when(jwtUtil.getUserId("user-token")).thenReturn(73004L);
        when(userService.findById(73004L)).thenReturn(user);

        mockMvc.perform(get("/api/admin/reports")
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


package com.bitforum.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
class UserReportControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtUtil jwtUtil;
    @MockitoBean
    private UserService userService;
    @MockitoBean
    private ContentReportService contentReportService;

    @Test
    void reportArticleShouldRequireLogin() throws Exception {
        mockMvc.perform(post("/api/user/reports/article")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"articleId\":1,\"reason\":\"违规\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void userShouldReportArticle() throws Exception {
        ContentReport report = new ContentReport();
        report.setId(1L);
        report.setStatus(ContentReportService.STATUS_PENDING);

        mockEnabledUser("user-token", 72001L);
        when(contentReportService.reportArticle(72001L, 1L, "内容违规")).thenReturn(report);

        mockMvc.perform(post("/api/user/reports/article")
                .header("Authorization", "Bearer user-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"articleId\":1,\"reason\":\"内容违规\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("举报文章提交成功"))
                .andExpect(jsonPath("$.data.id").value(1));
    }

    @Test
    void userShouldReportComment() throws Exception {
        ContentReport report = new ContentReport();
        report.setId(2L);

        mockEnabledUser("user-token", 72002L);
        when(contentReportService.reportComment(72002L, 3L, "恶意评论")).thenReturn(report);

        mockMvc.perform(post("/api/user/reports/comment")
                .header("Authorization", "Bearer user-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"commentId\":3,\"reason\":\"恶意评论\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("举报评论提交成功"))
                .andExpect(jsonPath("$.data.id").value(2));
    }

    @Test
    void userShouldPageOwnReports() throws Exception {
        mockEnabledUser("user-token", 72003L);
        when(contentReportService.pageMyReports(72003L, 1, 10)).thenReturn(new Page<ContentReport>(1, 10));

        mockMvc.perform(get("/api/user/reports")
                .header("Authorization", "Bearer user-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("我的举报分页查询成功"));
    }

    private void mockEnabledUser(String token, Long userId) {
        User user = new User();
        user.setId(userId);
        user.setStatus(UserService.STATUS_ENABLED);

        when(jwtUtil.getUserId(token)).thenReturn(userId);
        when(userService.findById(userId)).thenReturn(user);
    }
}

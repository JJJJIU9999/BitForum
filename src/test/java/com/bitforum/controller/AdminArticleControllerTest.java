package com.bitforum.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bitforum.entity.Article;
import com.bitforum.entity.User;
import com.bitforum.service.ArticleService;
import com.bitforum.service.UserService;
import com.bitforum.util.JwtUtil;

@SpringBootTest
@AutoConfigureMockMvc
class AdminArticleControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtUtil jwtUtil;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private ArticleService articleService;

    @Test
    void adminShouldPageArticles() throws Exception {
        mockAdmin("admin-token", 10L);
        when(articleService.pageAdminArticles(1, 10, null)).thenReturn(new Page<Article>(1, 10));

        // 管理员文章分页接口只负责转发分页查询，权限判断由 AdminInterceptor 先完成。
        mockMvc.perform(get("/api/admin/article/page")
                .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("管理员文章分页查询成功"));
    }

    @Test
    void adminShouldPageAuditArticles() throws Exception {
        mockAdmin("admin-token", 10L);
        when(articleService.pageAuditArticles(1, 10, "PENDING")).thenReturn(new Page<Article>(1, 10));

        mockMvc.perform(get("/api/admin/article/audit/page")
                .param("status", "PENDING")
                .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("审核文章分页查询成功"));
    }

    @Test
    void adminShouldApproveArticle() throws Exception {
        mockAdmin("admin-token", 10L);

        mockMvc.perform(put("/api/admin/article/audit/approve")
                .param("articleId", "1")
                .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("审核通过成功"));

        verify(articleService).approve(1L, 10L);
    }

    @Test
    void adminShouldRejectArticleWithReason() throws Exception {
        mockAdmin("admin-token", 10L);

        mockMvc.perform(put("/api/admin/article/audit/reject")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"articleId\":1,\"reason\":\"内容不符合要求\"}")
                .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("审核驳回成功"));

        verify(articleService).reject(1L, 10L, "内容不符合要求");
    }

    @Test
    void rejectShouldFailWithoutReason() throws Exception {
        mockAdmin("admin-token", 10L);

        mockMvc.perform(put("/api/admin/article/audit/reject")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"articleId\":1,\"reason\":\"\"}")
                .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void adminShouldOfflineArticle() throws Exception {
        mockAdmin("admin-token", 10L);

        mockMvc.perform(put("/api/admin/article/offline")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"articleId\":1,\"reason\":\"违规内容\"}")
                .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("文章下架成功"));

        verify(articleService).offline(1L, 10L, "违规内容");
    }

    @Test
    void adminShouldDeleteAnyArticle() throws Exception {
        mockAdmin("admin-token", 10L);
        when(articleService.deleteByAdmin(1L)).thenReturn(true);

        // 管理员删除接口不再判断文章作者是谁，只要管理员身份通过就调用后台删除逻辑。
        mockMvc.perform(delete("/api/admin/article/delete")
                .param("articleId", "1")
                .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("管理员删除文章成功"));
    }

    @Test
    void adminDeleteShouldFailWhenArticleNotExists() throws Exception {
        mockAdmin("admin-token", 10L);
        when(articleService.deleteByAdmin(404L)).thenReturn(false);

        mockMvc.perform(delete("/api/admin/article/delete")
                .param("articleId", "404")
                .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("文章不存在"));
    }

    @Test
    void normalUserShouldNotDeleteArticleThroughAdminApi() throws Exception {
        User user = new User();
        user.setId(20L);
        user.setRole("USER");
        user.setStatus(UserService.STATUS_ENABLED);

        when(jwtUtil.getUserId("user-token")).thenReturn(20L);
        when(userService.findById(20L)).thenReturn(user);

        // 即使普通用户知道管理员删除接口地址，也会先被 AdminInterceptor 拦住。
        mockMvc.perform(delete("/api/admin/article/delete")
                .param("articleId", "1")
                .header("Authorization", "Bearer user-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("无管理员权限"));
    }

    @Test
    void normalUserShouldNotApproveArticleThroughAdminApi() throws Exception {
        User user = new User();
        user.setId(20L);
        user.setRole("USER");
        user.setStatus(UserService.STATUS_ENABLED);

        when(jwtUtil.getUserId("user-token")).thenReturn(20L);
        when(userService.findById(20L)).thenReturn(user);

        mockMvc.perform(put("/api/admin/article/audit/approve")
                .param("articleId", "1")
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

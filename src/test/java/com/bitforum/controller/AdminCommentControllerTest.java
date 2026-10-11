package com.bitforum.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bitforum.entity.Comment;
import com.bitforum.entity.User;
import com.bitforum.service.CommentService;
import com.bitforum.service.UserService;
import com.bitforum.util.JwtUtil;

@SpringBootTest
@AutoConfigureMockMvc
class AdminCommentControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtUtil jwtUtil;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private CommentService commentService;

    @Test
    void adminShouldPageComments() throws Exception {
        mockAdmin("admin-token", 11L);
        when(commentService.pageComments(1, 10)).thenReturn(new Page<Comment>(1, 10));

        // 管理员评论分页接口用于后台巡查评论，权限仍然由 AdminInterceptor 先判断。
        mockMvc.perform(get("/api/admin/comment/page")
                .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("管理员评论分页查询成功"));
    }

    @Test
    void adminShouldDeleteAnyComment() throws Exception {
        mockAdmin("admin-token", 11L);
        when(commentService.deleteByAdmin(1L)).thenReturn(true);

        mockMvc.perform(delete("/api/admin/comment/delete")
                .param("commentId", "1")
                .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("管理员删除评论成功"));
    }

    @Test
    void adminDeleteShouldFailWhenCommentNotExists() throws Exception {
        mockAdmin("admin-token", 11L);
        when(commentService.deleteByAdmin(404L)).thenReturn(false);

        mockMvc.perform(delete("/api/admin/comment/delete")
                .param("commentId", "404")
                .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("评论不存在"));
    }

    @Test
    void normalUserShouldNotDeleteCommentThroughAdminApi() throws Exception {
        User user = new User();
        user.setId(21L);
        user.setRole("USER");
        user.setStatus(UserService.STATUS_ENABLED);

        when(jwtUtil.getUserId("user-token")).thenReturn(21L);
        when(userService.findById(21L)).thenReturn(user);

        // 普通用户直接调用后台评论删除接口，也会先被管理员拦截器拒绝。
        mockMvc.perform(delete("/api/admin/comment/delete")
                .param("commentId", "1")
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

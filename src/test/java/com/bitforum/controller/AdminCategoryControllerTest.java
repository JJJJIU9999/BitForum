package com.bitforum.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

import com.bitforum.entity.Category;
import com.bitforum.entity.User;
import com.bitforum.service.CategoryService;
import com.bitforum.service.CategoryService.CategoryDeleteResult;
import com.bitforum.service.CategoryService.CategoryUpdateResult;
import com.bitforum.service.UserService;
import com.bitforum.util.JwtUtil;

@SpringBootTest
@AutoConfigureMockMvc
class AdminCategoryControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtUtil jwtUtil;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private CategoryService categoryService;

    @Test
    void adminShouldCreateCategory() throws Exception {
        mockAdmin("admin-token", 10L);

        Category category = new Category();
        category.setId(1L);
        category.setName("后端技术");
        category.setStatus(CategoryService.STATUS_ENABLED);
        when(categoryService.create("后端技术", "Java 和 Spring 讨论", 1)).thenReturn(category);

        mockMvc.perform(post("/api/admin/category/create")
                .header("Authorization", "Bearer admin-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "name": "后端技术",
                          "description": "Java 和 Spring 讨论",
                          "sortOrder": 1
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("板块创建成功"))
                .andExpect(jsonPath("$.data.id").value(1));
    }

    @Test
    void normalUserShouldNotManageCategory() throws Exception {
        User user = new User();
        user.setId(20L);
        user.setRole("USER");
        user.setStatus(UserService.STATUS_ENABLED);

        when(jwtUtil.getUserId("user-token")).thenReturn(20L);
        when(userService.findById(20L)).thenReturn(user);

        mockMvc.perform(post("/api/admin/category/create")
                .header("Authorization", "Bearer user-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "name": "普通用户不能创建",
                          "description": "权限测试",
                          "sortOrder": 1
                        }
                        """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("无管理员权限"));
    }

    @Test
    void adminShouldDisableCategory() throws Exception {
        mockAdmin("admin-token", 10L);
        when(categoryService.disable(1L)).thenReturn(CategoryUpdateResult.SUCCESS);

        mockMvc.perform(put("/api/admin/category/disable")
                .header("Authorization", "Bearer admin-token")
                .param("categoryId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("板块禁用成功"));
    }

    @Test
    void categoryWithArticleShouldNotBeDeleted() throws Exception {
        mockAdmin("admin-token", 10L);
        when(categoryService.delete(1L)).thenReturn(CategoryDeleteResult.HAS_ARTICLE);

        mockMvc.perform(delete("/api/admin/category/delete")
                .header("Authorization", "Bearer admin-token")
                .param("categoryId", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("板块下已有文章，不能删除"));
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

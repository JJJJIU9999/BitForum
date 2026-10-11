package com.bitforum.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.bitforum.entity.User;
import com.bitforum.mapper.UserMapper;
import com.bitforum.service.UserService;
import com.bitforum.util.JwtUtil;

/**
 * 管理员知识库接口测试（M15）。
 *
 * <p>只覆盖接口层职责：路径是否正确、是否受既有管理员拦截器保护、返回结构是否符合 {@code Result} 约定。
 * 重建与统计的业务正确性由 {@code KbIndexServiceTest} 覆盖。
 *
 * <p>重建接口只验证未授权路径，不触发真实重建 —— 重建会写入 Redis 向量，
 * 而 {@code @Transactional} 只能回滚 MySQL，会留下孤儿向量。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminAiKbControllerTest {

    private static final String ADMIN_TOKEN = "kb-admin-token";
    private static final String USER_TOKEN = "kb-user-token";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserMapper userMapper;

    @MockitoBean
    private JwtUtil jwtUtil;

    @BeforeEach
    void prepareUsers() {
        Long adminId = createUser("kb-test-admin", UserService.ROLE_ADMIN);
        Long userId = createUser("kb-test-user", "USER");
        when(jwtUtil.getUserId(ADMIN_TOKEN)).thenReturn(adminId);
        when(jwtUtil.getUserId(USER_TOKEN)).thenReturn(userId);
    }

    @Test
    void statsShouldRejectAnonymousRequest() throws Exception {
        mockMvc.perform(get("/api/admin/ai/kb/stats")).andExpect(status().isUnauthorized());
    }

    @Test
    void statsShouldRejectNonAdminUser() throws Exception {
        mockMvc.perform(get("/api/admin/ai/kb/stats").header("Authorization", "Bearer " + USER_TOKEN))
                .andExpect(status().isForbidden());
    }

    @Test
    void statsShouldReturnKnowledgeBaseNumbersForAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/ai/kb/stats").header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.publishedArticles").isNumber())
                .andExpect(jsonPath("$.data.documents").isNumber())
                .andExpect(jsonPath("$.data.indexedDocuments").isNumber())
                .andExpect(jsonPath("$.data.pendingDocuments").isNumber())
                .andExpect(jsonPath("$.data.failedDocuments").isNumber())
                .andExpect(jsonPath("$.data.chunks").isNumber())
                .andExpect(jsonPath("$.data.embeddingModel").value("bge-base-zh-v1.5"));
    }

    @Test
    void rebuildShouldRejectAnonymousRequest() throws Exception {
        mockMvc.perform(post("/api/admin/ai/kb/rebuild")).andExpect(status().isUnauthorized());
    }

    private Long createUser(String username, String role) {
        User user = new User();
        user.setUsername(username);
        // 该密码不会被校验：本测试只走管理员拦截器，不经过登录流程
        user.setPassword("$2a$10$knowledge-base-controller-test-placeholder");
        user.setNickname(username);
        user.setRole(role);
        user.setStatus(UserService.STATUS_ENABLED);
        userMapper.insert(user);
        return user.getId();
    }
}

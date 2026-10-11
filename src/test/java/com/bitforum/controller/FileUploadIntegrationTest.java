package com.bitforum.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.bitforum.entity.User;
import com.bitforum.service.UserService;
import com.bitforum.util.JwtUtil;

@SpringBootTest
@AutoConfigureMockMvc
class FileUploadIntegrationTest {
    @TempDir
    static Path uploadRoot;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtUtil jwtUtil;
    @MockitoBean
    private UserService userService;

    @DynamicPropertySource
    static void uploadProperties(DynamicPropertyRegistry registry) {
        registry.add("bitforum.upload.root", () -> uploadRoot.toString());
    }

    @Test
    void avatarBeyondBusinessLimitShouldReturnClearError() throws Exception {
        mockEnabledUser("user-token", 1L);
        byte[] content = new byte[2 * 1024 * 1024 + 1];
        MockMultipartFile file = new MockMultipartFile("file", "large-avatar.png", "image/png", content);

        mockMvc.perform(multipart("/api/upload/avatar")
                .file(file)
                .header("Authorization", "Bearer user-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("上传文件大小超过限制"));
    }

    private void mockEnabledUser(String token, Long userId) {
        User user = new User();
        user.setId(userId);
        user.setStatus(UserService.STATUS_ENABLED);

        when(jwtUtil.getUserId(token)).thenReturn(userId);
        when(userService.findById(userId)).thenReturn(user);
    }
}

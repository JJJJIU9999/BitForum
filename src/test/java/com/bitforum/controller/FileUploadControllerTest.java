package com.bitforum.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;

import com.bitforum.dto.FileUploadResponse;
import com.bitforum.entity.User;
import com.bitforum.service.ImageResourceService;
import com.bitforum.service.UserService;
import com.bitforum.util.JwtUtil;

@SpringBootTest
@AutoConfigureMockMvc
class FileUploadControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtUtil jwtUtil;
    @MockitoBean
    private UserService userService;
    @MockitoBean
    private ImageResourceService imageResourceService;

    @Test
    void avatarUploadShouldRequireLogin() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", "png".getBytes());

        mockMvc.perform(multipart("/api/upload/avatar").file(file))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        verify(imageResourceService, never()).uploadAvatar(any(MultipartFile.class), anyLong());
    }

    @Test
    void avatarUploadShouldReturnFileInfo() throws Exception {
        mockEnabledUser("user-token", 1L);
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", "png".getBytes());
        when(imageResourceService.uploadAvatar(any(MultipartFile.class), anyLong())).thenReturn(uploadResponse("AVATAR"));

        mockMvc.perform(multipart("/api/upload/avatar")
                .file(file)
                .header("Authorization", "Bearer user-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("头像已上传，等待人工审核"))
                .andExpect(jsonPath("$.data.id").value(42))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.url").doesNotExist());
    }

    @Test
    void articleCoverUploadShouldReturnFileInfo() throws Exception {
        mockEnabledUser("user-token", 2L);
        MockMultipartFile file = new MockMultipartFile("file", "cover.jpg", "image/jpeg", "jpg".getBytes());
        when(imageResourceService.uploadCover(any(MultipartFile.class), anyLong())).thenReturn(uploadResponse("COVER"));

        mockMvc.perform(multipart("/api/upload/article-cover")
                .file(file)
                .header("Authorization", "Bearer user-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("封面已上传，等待人工审核"))
                .andExpect(jsonPath("$.data.id").value(42))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.url").doesNotExist());
    }

    @Test
    void serviceBusinessErrorShouldReturnResultFail() throws Exception {
        mockEnabledUser("user-token", 3L);
        MockMultipartFile file = new MockMultipartFile("file", "note.txt", "text/plain", "txt".getBytes());
        when(imageResourceService.uploadAvatar(any(MultipartFile.class), anyLong())).thenThrow(new com.bitforum.exception.BusinessException("只允许上传 JPG、PNG 或 WebP 图片"));

        mockMvc.perform(multipart("/api/upload/avatar")
                .file(file)
                .header("Authorization", "Bearer user-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("只允许上传 JPG、PNG 或 WebP 图片"));
    }

    private FileUploadResponse uploadResponse(String kind) {
        FileUploadResponse response = new FileUploadResponse();
        response.setId(42L);
        response.setStatus("PENDING");
        response.setKind(kind);
        response.setOriginalFilename("mock.png");
        response.setSize(3);
        response.setContentType("image/png");
        return response;
    }

    private void mockEnabledUser(String token, Long userId) {
        User user = new User();
        user.setId(userId);
        user.setStatus(UserService.STATUS_ENABLED);

        when(jwtUtil.getUserId(token)).thenReturn(userId);
        when(userService.findById(userId)).thenReturn(user);
    }
}

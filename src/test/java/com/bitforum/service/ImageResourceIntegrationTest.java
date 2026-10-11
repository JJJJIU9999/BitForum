package com.bitforum.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.bitforum.dto.FileUploadResponse;
import com.bitforum.entity.Article;
import com.bitforum.entity.Category;
import com.bitforum.entity.ImageResource;
import com.bitforum.entity.User;
import com.bitforum.mapper.ArticleMapper;
import com.bitforum.mapper.CategoryMapper;
import com.bitforum.mapper.ImageResourceMapper;
import com.bitforum.mapper.UserMapper;
import com.bitforum.util.JwtUtil;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ImageResourceIntegrationTest {
    @TempDir static Path uploadRoot;
    @DynamicPropertySource
    static void uploadProperties(DynamicPropertyRegistry registry) {
        registry.add("bitforum.upload.root", () -> uploadRoot.toString());
    }

    @Autowired private ImageResourceService images;
    @Autowired private ArticleService articles;
    @Autowired private UserMapper users;
    @Autowired private ArticleMapper articleMapper;
    @Autowired private CategoryMapper categories;
    @Autowired private ImageResourceMapper imageMapper;
    @Autowired private MockMvc mockMvc;
    @MockitoBean private JwtUtil jwt;
    @MockitoBean private RabbitTemplate rabbitTemplate;
    @MockitoBean private RedisService redisService;

    @Test
    void pendingAvatarRequiresOwnerPreviewAndHumanApproval() throws Exception {
        User owner = user("USER");
        User stranger = user("USER");
        User admin = user("ADMIN");
        byte[] bytes = png();
        FileUploadResponse uploaded = images.uploadAvatar(file(bytes), owner.getId());

        assertEquals(ImageResourceService.PENDING, uploaded.getStatus());
        assertEquals(uploaded.getId(), users.selectById(owner.getId()).getPendingAvatarImageId());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> images.publicImage(uploaded.getId()));
        assertArrayEquals(bytes, images.preview(uploaded.getId(), owner.getId()).bytes());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> images.preview(uploaded.getId(), stranger.getId()));
        assertArrayEquals(bytes, images.preview(uploaded.getId(), admin.getId()).bytes());

        mockMvc.perform(get("/api/images/{id}/preview", uploaded.getId()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/images/{id}/content", uploaded.getId()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/uploads/private/avatar/secret.png"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/admin/images/pending"))
                .andExpect(status().isUnauthorized());
        when(jwt.getUserId("stranger-token")).thenReturn(stranger.getId());
        mockMvc.perform(get("/api/images/{id}/preview", uploaded.getId())
                .header("Authorization", "Bearer stranger-token"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/images/pending")
                .header("Authorization", "Bearer stranger-token"))
                .andExpect(status().isForbidden());

        images.decide(uploaded.getId(), admin.getId(), true, null);
        assertArrayEquals(bytes, images.publicImage(uploaded.getId()).bytes());
        mockMvc.perform(get("/api/images/{id}/content", uploaded.getId()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("X-Content-Type-Options", "nosniff"));
        assertEquals("/api/images/" + uploaded.getId() + "/content", users.selectById(owner.getId()).getAvatar());
        assertNull(users.selectById(owner.getId()).getPendingAvatarImageId());
    }

    @Test
    void pendingCoverBlocksArticleApprovalAndExternalUrlIsRejected() throws Exception {
        User owner = user("USER");
        User admin = user("ADMIN");
        Category category = categories.selectList(null).get(0);
        FileUploadResponse uploaded = images.uploadCover(file(png()), owner.getId());
        assertThrows(IllegalArgumentException.class, () -> articles.publish(
                "外链封面", "正文", category.getId(), owner.getId(), "https://example.com/cover.png"));

        Article draft = articles.saveDraftWithCoverId("待审封面" + UUID.randomUUID(), "正文",
                category.getId(), owner.getId(), uploaded.getId());
        articles.submit(owner.getId(), draft.getId());
        assertThrows(RuntimeException.class, () -> articles.approve(draft.getId(), admin.getId()));
        assertEquals(ArticleService.STATUS_PENDING, articleMapper.selectById(draft.getId()).getStatus());
        assertNull(articleMapper.selectById(draft.getId()).getCoverUrl());

        images.decide(uploaded.getId(), admin.getId(), true, null);
        assertEquals("/api/images/" + uploaded.getId() + "/content",
                articleMapper.selectById(draft.getId()).getCoverUrl());
        assertTrue(articles.approve(draft.getId(), admin.getId()));
    }

    @Test
    void rejectedImageStaysPrivateAndOwnerCanUploadAgain() throws Exception {
        User owner = user("USER");
        User admin = user("ADMIN");
        FileUploadResponse first = images.uploadAvatar(file(png()), owner.getId());

        images.decide(first.getId(), admin.getId(), false, "图片不合适");
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> images.publicImage(first.getId()));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> images.preview(first.getId(), owner.getId()));
        assertNull(users.selectById(owner.getId()).getPendingAvatarImageId());

        FileUploadResponse second = images.uploadAvatar(file(png()), owner.getId());
        assertEquals(ImageResourceService.PENDING, second.getStatus());
        assertEquals(second.getId(), users.selectById(owner.getId()).getPendingAvatarImageId());
    }

    @Test
    void sharedLegacyFileCanBeReviewedForBothOwners() throws Exception {
        User firstOwner = user("USER");
        User secondOwner = user("USER");
        User admin = user("ADMIN");
        byte[] bytes = png();
        Path oldFile = uploadRoot.resolve("avatar/shared.png");
        Files.createDirectories(oldFile.getParent());
        Files.write(oldFile, bytes);

        ImageResource first = legacyAvatar(firstOwner);
        ImageResource second = legacyAvatar(secondOwner);
        images.decide(first.getId(), admin.getId(), true, null);
        images.decide(second.getId(), admin.getId(), true, null);

        assertTrue(Files.exists(oldFile));
        assertArrayEquals(bytes, images.publicImage(first.getId()).bytes());
        assertArrayEquals(bytes, images.publicImage(second.getId()).bytes());
        assertTrue(!imageMapper.selectById(first.getId()).getStorageKey()
                .equals(imageMapper.selectById(second.getId()).getStorageKey()));
    }

    @Test
    void rollbackRemovesBothPrivateUploadAndApprovedCopy() throws Exception {
        User owner = user("USER");
        User admin = user("ADMIN");
        FileUploadResponse uploaded = images.uploadAvatar(file(png()), owner.getId());
        String privateKey = imageMapper.selectById(uploaded.getId()).getStorageKey();
        images.decide(uploaded.getId(), admin.getId(), true, null);
        String publicKey = imageMapper.selectById(uploaded.getId()).getStorageKey();
        assertTrue(Files.exists(uploadRoot.resolve(privateKey)));
        assertTrue(Files.exists(uploadRoot.resolve(publicKey)));

        TestTransaction.flagForRollback();
        TestTransaction.end();
        assertTrue(!Files.exists(uploadRoot.resolve(privateKey)));
        assertTrue(!Files.exists(uploadRoot.resolve(publicKey)));
    }

    @Test
    void missingLegacyFileCannotBePublishedButCanBeRejected() throws Exception {
        User owner = user("USER");
        User admin = user("ADMIN");
        ImageResource image = legacyAvatar(owner);
        image.setStorageKey("avatar/missing-" + UUID.randomUUID() + ".png");
        imageMapper.updateById(image);
        when(jwt.getUserId("missing-owner")).thenReturn(owner.getId());
        when(jwt.getUserId("missing-admin")).thenReturn(admin.getId());

        mockMvc.perform(get("/api/images/{id}/preview", image.getId())
                .header("Authorization", "Bearer missing-owner"))
                .andExpect(status().isNotFound());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                "/api/admin/images/{id}/approve", image.getId())
                .header("Authorization", "Bearer missing-admin"))
                .andExpect(status().isBadRequest());
        assertEquals(ImageResourceService.LEGACY_REVIEW, imageMapper.selectById(image.getId()).getStatus());
        assertNull(users.selectById(owner.getId()).getAvatar());
        images.decide(image.getId(), admin.getId(), false, "历史原件缺失，请重新上传");
        assertNull(users.selectById(owner.getId()).getPendingAvatarImageId());
        assertEquals(ImageResourceService.PENDING, images.uploadAvatar(file(png()), owner.getId()).getStatus());
    }

    private ImageResource legacyAvatar(User owner) {
        ImageResource image = new ImageResource();
        image.setOwnerId(owner.getId());
        image.setKind(ImageResourceService.AVATAR);
        image.setStatus(ImageResourceService.LEGACY_REVIEW);
        image.setStorageKey("avatar/shared.png");
        image.setLegacyUrl("/uploads/avatar/shared.png");
        image.setTargetId(owner.getId());
        imageMapper.insert(image);
        users.setPendingAvatar(owner.getId(), image.getId());
        return image;
    }

    private User user(String role) {
        User user = new User();
        user.setUsername("m18-" + UUID.randomUUID());
        user.setPassword("test-hash");
        user.setRole(role);
        user.setStatus(1);
        user.setPublicProfileStatus("PUBLISHED");
        users.insert(user);
        return user;
    }

    private MockMultipartFile file(byte[] bytes) {
        return new MockMultipartFile("file", "photo.png", "image/png", bytes);
    }

    private byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            assertTrue(ImageIO.write(image, "png", output));
            return output.toByteArray();
        }
    }
}

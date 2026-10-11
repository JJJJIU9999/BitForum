package com.bitforum.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import com.bitforum.service.FileUploadService.StoredImage;

class FileUploadServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void readFailureIsInternalAndRetainsCause() throws Exception {
        var file = org.mockito.Mockito.mock(org.springframework.web.multipart.MultipartFile.class);
        org.mockito.Mockito.when(file.isEmpty()).thenReturn(false);
        org.mockito.Mockito.when(file.getContentType()).thenReturn("image/png");
        org.mockito.Mockito.when(file.getOriginalFilename()).thenReturn("synthetic.png");
        var cause = new java.io.IOException("synthetic read failure");
        org.mockito.Mockito.when(file.getBytes()).thenThrow(cause);
        var failure = assertThrows(RuntimeException.class,
                () -> new FileUploadService(tempDir.toString()).uploadAvatar(file));
        assertFalse(failure instanceof com.bitforum.exception.BusinessException);
        org.junit.jupiter.api.Assertions.assertSame(cause, failure.getCause());
    }

    @Test
    void saveFailureIsInternalAndRetainsCause() throws Exception {
        Path blocked = tempDir.resolve("blocked");
        Files.writeString(blocked, "synthetic file blocks directory");
        var file = new MockMultipartFile("file", "synthetic.png", "image/png", imageBytes("png"));
        var failure = assertThrows(RuntimeException.class,
                () -> new FileUploadService(blocked.toString()).uploadAvatar(file));
        assertFalse(failure instanceof com.bitforum.exception.BusinessException);
        assertTrue(failure.getCause() instanceof java.io.IOException);
    }

    @Test
    void emptyFileShouldBeRejected() {
        FileUploadService service = new FileUploadService(tempDir.toString());
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", new byte[0]);

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            service.uploadAvatar(file);
        });

        assertEquals("上传文件不能为空", exception.getMessage());
    }

    @Test
    void nonImageFileShouldBeRejected() {
        FileUploadService service = new FileUploadService(tempDir.toString());
        MockMultipartFile file = new MockMultipartFile("file", "note.txt", "text/plain", "hello".getBytes());

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            service.uploadAvatar(file);
        });

        assertEquals("只允许上传 JPG、PNG 或 WebP 图片", exception.getMessage());
    }

    @Test
    void avatarLargerThanLimitShouldBeRejected() {
        FileUploadService service = new FileUploadService(tempDir.toString());
        byte[] content = new byte[2 * 1024 * 1024 + 1];
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", content);

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            service.uploadAvatar(file);
        });

        assertEquals("上传文件大小超过限制", exception.getMessage());
    }

    @Test
    void articleCoverLargerThanLimitShouldBeRejected() {
        FileUploadService service = new FileUploadService(tempDir.toString());
        byte[] content = new byte[5 * 1024 * 1024 + 1];
        MockMultipartFile file = new MockMultipartFile("file", "cover.jpg", "image/jpeg", content);

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            service.uploadArticleCover(file);
        });

        assertEquals("上传文件大小超过限制", exception.getMessage());
    }

    @Test
    void validAvatarShouldBeSavedWithGeneratedName() throws Exception {
        FileUploadService service = new FileUploadService(tempDir.toString());
        byte[] content = imageBytes("png");
        MockMultipartFile file = new MockMultipartFile("file", "..\\avatar.png", "image/png", content);

        StoredImage response = service.uploadAvatar(file);

        assertTrue(response.storageKey().startsWith("private/avatar/"));
        assertTrue(response.storageKey().endsWith(".png"));
        assertEquals("avatar.png", response.originalFilename());
        assertEquals("image/png", response.contentType());
        assertEquals(content.length, response.size());
        assertFalse(response.storageKey().contains("avatar.png"));

        assertTrue(Files.exists(tempDir.resolve(response.storageKey())));
        assertEquals(content.length, service.read(response.storageKey()).length);
    }

    @Test
    void approvedCopyKeepsOriginalForRecovery() throws Exception {
        FileUploadService service = new FileUploadService(tempDir.toString());
        MockMultipartFile file = new MockMultipartFile("file", "cover.jpeg", "image/jpeg", imageBytes("jpg"));

        StoredImage response = service.uploadArticleCover(file);

        assertTrue(response.storageKey().startsWith("private/article-cover/"));
        assertTrue(response.storageKey().endsWith(".jpg"));

        String publicKey = service.copyToPublic(response.storageKey(), "COVER");
        assertTrue(publicKey.startsWith("public/article-cover/"));
        assertTrue(Files.exists(tempDir.resolve(response.storageKey())));
        assertTrue(Files.exists(tempDir.resolve(publicKey)));
    }

    @Test
    void missingStoredImageShouldBeNotFoundAndCannotBeApproved() {
        FileUploadService service = new FileUploadService(tempDir.toString());
        var missing = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.read("avatar/missing.png"));
        assertEquals(404, missing.getStatusCode().value());
        var approval = assertThrows(RuntimeException.class,
                () -> service.copyToPublic("avatar/missing.png", "AVATAR"));
        assertEquals("图片原件不存在，请让作者重新上传", approval.getMessage());
    }

    @Test
    void fakePngContentShouldBeRejected() {
        FileUploadService service = new FileUploadService(tempDir.toString());
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", "not-a-png".getBytes());

        RuntimeException exception = assertThrows(RuntimeException.class, () -> service.uploadAvatar(file));

        assertEquals("图片内容与文件类型不匹配", exception.getMessage());
        assertFalse(Files.exists(tempDir.resolve("private/avatar")));
    }

    @Test
    void mismatchedExtensionAndContentTypeShouldBeRejected() throws Exception {
        FileUploadService service = new FileUploadService(tempDir.toString());
        MockMultipartFile file = new MockMultipartFile("file", "avatar.jpg", "image/png", imageBytes("png"));

        RuntimeException exception = assertThrows(RuntimeException.class, () -> service.uploadAvatar(file));

        assertEquals("文件扩展名与图片类型不匹配", exception.getMessage());
    }

    @Test
    void truncatedWebpHeaderShouldBeRejected() {
        FileUploadService service = new FileUploadService(tempDir.toString());
        byte[] content = {'R', 'I', 'F', 'F', 4, 0, 0, 0, 'W', 'E', 'B', 'P'};
        MockMultipartFile file = new MockMultipartFile("file", "avatar.webp", "image/webp", content);

        RuntimeException exception = assertThrows(RuntimeException.class, () -> service.uploadAvatar(file));

        assertEquals("图片内容与文件类型不匹配", exception.getMessage());
    }

    private byte[] imageBytes(String format) throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            assertTrue(ImageIO.write(image, format, output));
            return output.toByteArray();
        }
    }
}

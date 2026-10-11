package com.bitforum.service;

import com.bitforum.exception.BusinessException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FileUploadService {
    private static final long AVATAR_MAX_SIZE = 2L * 1024 * 1024;
    private static final long ARTICLE_COVER_MAX_SIZE = 5L * 1024 * 1024;
    private static final Map<String, String> IMAGE_EXTENSIONS = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp");

    private final Path uploadRoot;

    public FileUploadService(@Value("${bitforum.upload.root:uploads}") String uploadRoot) {
        this.uploadRoot = Paths.get(uploadRoot).toAbsolutePath().normalize();
    }

    public record StoredImage(String storageKey, String originalFilename, long size, String contentType) {}

    public StoredImage uploadAvatar(MultipartFile file) {
        return uploadImage(file, "avatar", AVATAR_MAX_SIZE);
    }

    public StoredImage uploadArticleCover(MultipartFile file) {
        return uploadImage(file, "article-cover", ARTICLE_COVER_MAX_SIZE);
    }

    private StoredImage uploadImage(MultipartFile file, String folder, long maxSize) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件不能为空");
        }
        if (file.getSize() > maxSize) {
            throw new BusinessException("上传文件大小超过限制");
        }

        String contentType = normalizeContentType(file.getContentType());
        String extension = IMAGE_EXTENSIONS.get(contentType);
        if (extension == null) {
            throw new BusinessException("只允许上传 JPG、PNG 或 WebP 图片");
        }
        String originalFilename = cleanOriginalFilename(file.getOriginalFilename());
        if (!matchesExtension(originalFilename, contentType)) {
            throw new BusinessException("文件扩展名与图片类型不匹配");
        }

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new RuntimeException("文件读取失败", e);
        }
        if (content.length > maxSize) {
            throw new BusinessException("上传文件大小超过限制");
        }
        if (!hasExpectedImageContent(content, contentType)) {
            throw new BusinessException("图片内容与文件类型不匹配");
        }

        Path targetDir = uploadRoot.resolve("private").resolve(folder).normalize();
        ensureInRoot(targetDir);

        String filename = UUID.randomUUID() + "." + extension;
        Path target = targetDir.resolve(filename).normalize();
        if (!target.startsWith(targetDir)) {
            throw new RuntimeException("文件保存路径不合法");
        }

        try {
            Files.createDirectories(targetDir);
            Files.write(target, content);
        } catch (IOException e) {
            throw new RuntimeException("文件保存失败", e);
        }

        return new StoredImage("private/" + folder + "/" + filename,
                originalFilename, content.length, contentType);
    }

    public byte[] read(String storageKey) {
        try {
            return Files.readAllBytes(resolveKey(storageKey));
        } catch (NoSuchFileException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "图片原件不存在，请重新上传");
        } catch (IOException e) {
            throw new RuntimeException("图片文件不可读取", e);
        }
    }

    public String copyToPublic(String storageKey, String kind) {
        Path source = resolveKey(storageKey);
        String folder = "AVATAR".equals(kind) ? "avatar" : "article-cover";
        String publicKey = "public/" + folder + "/" + UUID.randomUUID() + "-" + source.getFileName();
        Path target = resolveKey(publicKey);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(source, target);
            return publicKey;
        } catch (NoSuchFileException e) {
            throw new BusinessException("图片原件不存在，请让作者重新上传", e);
        } catch (IOException e) {
            throw new RuntimeException("图片文件复制失败", e);
        }
    }

    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(resolveKey(storageKey));
        } catch (IOException e) {
            throw new RuntimeException("图片文件清理失败", e);
        }
    }

    private Path resolveKey(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new IllegalArgumentException("图片文件路径无效");
        }
        Path key = Paths.get(storageKey);
        Path path = uploadRoot.resolve(key).normalize();
        if (key.isAbsolute() || !path.startsWith(uploadRoot)) {
            throw new IllegalArgumentException("图片文件路径无效");
        }
        return path;
    }

    private void ensureInRoot(Path path) {
        if (!path.startsWith(uploadRoot)) {
            throw new RuntimeException("文件保存目录不合法");
        }
    }

    private String normalizeContentType(String contentType) {
        if (contentType == null) {
            return "";
        }
        return contentType.toLowerCase(Locale.ROOT);
    }

    private String cleanOriginalFilename(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "unknown";
        }
        String normalized = originalFilename.replace('\\', '/');
        int slashIndex = normalized.lastIndexOf('/');
        return slashIndex >= 0 ? normalized.substring(slashIndex + 1) : normalized;
    }

    private boolean matchesExtension(String filename, String contentType) {
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == filename.length() - 1) {
            return false;
        }
        String extension = filename.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
        if ("image/jpeg".equals(contentType)) {
            return "jpg".equals(extension) || "jpeg".equals(extension);
        }
        return IMAGE_EXTENSIONS.get(contentType).equals(extension);
    }

    private boolean hasExpectedImageContent(byte[] content, String contentType) {
        if ("image/png".equals(contentType)) {
            return hasPngSignature(content) && canDecodeImage(content);
        }
        if ("image/jpeg".equals(contentType)) {
            return hasJpegSignature(content) && canDecodeImage(content);
        }
        return hasWebpSignature(content);
    }

    private boolean hasPngSignature(byte[] content) {
        byte[] signature = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        if (content.length < signature.length) {
            return false;
        }
        for (int index = 0; index < signature.length; index++) {
            if (content[index] != signature[index]) {
                return false;
            }
        }
        return true;
    }

    private boolean hasJpegSignature(byte[] content) {
        return content.length >= 3
                && content[0] == (byte) 0xff
                && content[1] == (byte) 0xd8
                && content[2] == (byte) 0xff;
    }

    private boolean hasWebpSignature(byte[] content) {
        if (content.length < 21
                || content[0] != 'R' || content[1] != 'I' || content[2] != 'F' || content[3] != 'F'
                || content[8] != 'W' || content[9] != 'E' || content[10] != 'B' || content[11] != 'P') {
            return false;
        }
        String chunkType = new String(content, 12, 4, java.nio.charset.StandardCharsets.US_ASCII);
        if (!"VP8 ".equals(chunkType) && !"VP8L".equals(chunkType) && !"VP8X".equals(chunkType)) {
            return false;
        }
        long riffSize = readUnsignedLittleEndianInt(content, 4);
        long chunkSize = readUnsignedLittleEndianInt(content, 16);
        long paddedChunkSize = chunkSize + (chunkSize & 1L);
        return riffSize + 8L == content.length
                && chunkSize > 0
                && 20L + paddedChunkSize <= content.length;
    }

    private long readUnsignedLittleEndianInt(byte[] content, int offset) {
        return (content[offset] & 0xffL)
                | ((content[offset + 1] & 0xffL) << 8)
                | ((content[offset + 2] & 0xffL) << 16)
                | ((content[offset + 3] & 0xffL) << 24);
    }

    private boolean canDecodeImage(byte[] content) {
        try (ByteArrayInputStream input = new ByteArrayInputStream(content)) {
            return ImageIO.read(input) != null;
        } catch (IOException e) {
            return false;
        }
    }
}

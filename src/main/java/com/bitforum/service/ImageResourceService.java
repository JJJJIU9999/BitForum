package com.bitforum.service;

import com.bitforum.exception.BusinessException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bitforum.dto.FileUploadResponse;
import com.bitforum.entity.ImageResource;
import com.bitforum.entity.User;
import com.bitforum.mapper.ArticleMapper;
import com.bitforum.mapper.ImageResourceMapper;
import com.bitforum.mapper.UserMapper;

@Service
public class ImageResourceService {
    public static final String AVATAR = "AVATAR";
    public static final String COVER = "COVER";
    public static final String PENDING = "PENDING";
    public static final String LEGACY_REVIEW = "LEGACY_REVIEW";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String SUPERSEDED = "SUPERSEDED";

    @Autowired private FileUploadService fileService;
    @Autowired private ImageResourceMapper imageMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private ArticleMapper articleMapper;

    public record ImageView(Long id, Long ownerId, String kind, String status, Long targetId,
            String originalFilename, Long size, String legacyUrl, LocalDateTime createTime) {}
    public record ImageData(byte[] bytes, String contentType) {}

    @Transactional
    public FileUploadResponse uploadAvatar(MultipartFile file, Long ownerId) {
        return upload(file, ownerId, AVATAR);
    }

    @Transactional
    public FileUploadResponse uploadCover(MultipartFile file, Long ownerId) {
        return upload(file, ownerId, COVER);
    }

    private FileUploadResponse upload(MultipartFile file, Long ownerId, String kind) {
        FileUploadService.StoredImage stored = AVATAR.equals(kind)
                ? fileService.uploadAvatar(file) : fileService.uploadArticleCover(file);
        try {
            ImageResource image = new ImageResource();
            image.setOwnerId(ownerId);
            image.setKind(kind);
            image.setStatus(PENDING);
            image.setStorageKey(stored.storageKey());
            image.setOriginalFilename(stored.originalFilename());
            image.setContentType(stored.contentType());
            image.setSize(stored.size());
            imageMapper.insert(image);
            if (AVATAR.equals(kind)) {
                User user = userMapper.selectById(ownerId);
                if (user == null) throw new BusinessException("用户不存在");
                supersedeOldAvatar(user.getPendingAvatarImageId());
                userMapper.setPendingAvatar(ownerId, image.getId());
            }
            FileUploadResponse response = new FileUploadResponse();
            response.setId(image.getId());
            response.setStatus(PENDING);
            response.setKind(kind);
            response.setOriginalFilename(stored.originalFilename());
            response.setSize(stored.size());
            response.setContentType(stored.contentType());
            deleteOnRollback(stored.storageKey());
            return response;
        } catch (RuntimeException e) {
            fileService.delete(stored.storageKey());
            throw e;
        }
    }

    private void supersedeOldAvatar(Long oldId) {
        if (oldId == null) return;
        ImageResource old = imageMapper.selectById(oldId);
        if (old != null && (PENDING.equals(old.getStatus()) || LEGACY_REVIEW.equals(old.getStatus()))) {
            old.setStatus(SUPERSEDED);
            old.setReviewReason("用户上传了新头像");
            old.setReviewedTime(LocalDateTime.now());
            imageMapper.updateById(old);
        }
    }

    @Transactional
    public ImageResource bindCover(Long ownerId, Long articleId, Long imageId) {
        if (imageId == null) return null;
        ImageResource image = imageMapper.selectForUpdate(imageId);
        if (image == null || !Objects.equals(image.getOwnerId(), ownerId) || !COVER.equals(image.getKind())
                || !(PENDING.equals(image.getStatus()) || APPROVED.equals(image.getStatus()))) {
            throw new BusinessException("封面资源不存在、未获授权或不可使用");
        }
        if (image.getTargetId() != null && !Objects.equals(image.getTargetId(), articleId)) {
            throw new BusinessException("封面已绑定其他文章");
        }
        image.setTargetId(articleId);
        imageMapper.updateById(image);
        return image;
    }

    public boolean isApproved(Long imageId) {
        if (imageId == null) return true;
        ImageResource image = imageMapper.selectById(imageId);
        return image != null && APPROVED.equals(image.getStatus());
    }

    public String publicUrl(ImageResource image) {
        return image != null && APPROVED.equals(image.getStatus())
                ? "/api/images/" + image.getId() + "/content" : null;
    }

    public IPage<ImageView> pending(long pageNum, long pageSize) {
        return imageMapper.selectPage(new Page<>(pageNum, Math.min(Math.max(pageSize, 1), 100)),
                new QueryWrapper<ImageResource>().in("status", PENDING, LEGACY_REVIEW)
                        .orderByAsc("create_time", "id"))
                .convert(image -> view(image, true));
    }

    public List<ImageView> mine(Long ownerId) {
        return imageMapper.selectList(new QueryWrapper<ImageResource>()
                .eq("owner_id", ownerId).orderByDesc("id").last("LIMIT 100"))
                .stream().map(image -> view(image, false)).toList();
    }

    private ImageView view(ImageResource image, boolean admin) {
        return new ImageView(image.getId(), image.getOwnerId(), image.getKind(), image.getStatus(),
                image.getTargetId(), image.getOriginalFilename(), image.getSize(),
                admin ? image.getLegacyUrl() : null, image.getCreateTime());
    }

    @Transactional
    public void decide(Long imageId, Long reviewerId, boolean approved, String reason) {
        ImageResource image = imageMapper.selectForUpdate(imageId);
        if (image == null || !(PENDING.equals(image.getStatus()) || LEGACY_REVIEW.equals(image.getStatus()))) {
            throw new BusinessException("图片待办不存在或已处理");
        }
        if (!approved && !StringUtils.hasText(reason)) {
            throw new BusinessException("驳回原因不能为空");
        }
        if (approved && image.getStorageKey() == null) {
            throw new BusinessException("历史外链不能通过审核，请让作者重新上传站内图片");
        }
        String publicKey = null;
        if (approved) publicKey = fileService.copyToPublic(image.getStorageKey(), image.getKind());
        try {
            image.setStatus(approved ? APPROVED : REJECTED);
            if (approved) image.setStorageKey(publicKey);
            image.setReviewerId(reviewerId);
            image.setReviewReason(reason);
            image.setReviewedTime(LocalDateTime.now());
            imageMapper.updateById(image);
            if (AVATAR.equals(image.getKind())) {
                userMapper.finishPendingAvatar(image.getOwnerId(), imageId, approved, publicUrl(image));
            } else if (approved) {
                articleMapper.publishApprovedCover(imageId, publicUrl(image));
            }
            if (publicKey != null) deleteOnRollback(publicKey);
        } catch (RuntimeException e) {
            if (publicKey != null) fileService.delete(publicKey);
            throw e;
        }
    }

    private void deleteOnRollback(String storageKey) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) fileService.delete(storageKey);
            }
        });
    }

    public ImageData publicImage(Long imageId) {
        ImageResource image = imageMapper.selectById(imageId);
        if (image == null || !APPROVED.equals(image.getStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "图片不可访问");
        }
        return data(image);
    }

    public ImageData preview(Long imageId, Long viewerId) {
        ImageResource image = imageMapper.selectById(imageId);
        if (image == null || REJECTED.equals(image.getStatus()) || SUPERSEDED.equals(image.getStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "图片不可预览");
        }
        User viewer = userMapper.selectById(viewerId);
        if (viewer == null || (!Objects.equals(image.getOwnerId(), viewerId)
                && !UserService.ROLE_ADMIN.equals(viewer.getRole()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权预览图片");
        }
        if (image.getStorageKey() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "外链图片不可预览");
        }
        return data(image);
    }

    private ImageData data(ImageResource image) {
        String type = image.getContentType();
        if (type == null) {
            String key = image.getStorageKey().toLowerCase(java.util.Locale.ROOT);
            type = key.endsWith(".png") ? "image/png" : key.endsWith(".webp") ? "image/webp" : "image/jpeg";
        }
        return new ImageData(fileService.read(image.getStorageKey()), type);
    }
}

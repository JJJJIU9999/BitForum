package com.bitforum.service;

import com.bitforum.exception.BusinessException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bitforum.entity.Article;
import com.bitforum.entity.Comment;
import com.bitforum.entity.TextReviewRecord;
import com.bitforum.entity.User;
import com.bitforum.mapper.ArticleMapper;
import com.bitforum.mapper.CommentMapper;
import com.bitforum.mapper.TextReviewRecordMapper;
import com.bitforum.mapper.UserMapper;

@Service
public class TextReviewService {
    public static final String ARTICLE = "ARTICLE";
    public static final String COMMENT = "COMMENT";
    public static final String PROFILE = "PROFILE";
    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String SUPERSEDED = "SUPERSEDED";

    @Autowired
    private TextRuleService ruleService;
    @Autowired
    private TextReviewRecordMapper reviewMapper;
    @Autowired
    private ArticleMapper articleMapper;
    @Autowired
    private CommentMapper commentMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private NotificationService notificationService;

    public boolean recordIfMatched(String targetType, Long targetId, String text,
            String source, String candidateNickname, String candidateBio) {
        TextRuleService.Match match = ruleService.match(text);
        if (!match.hit()) {
            return false;
        }
        String hash = fingerprint(text);
        TextReviewRecord existing = reviewMapper.selectOne(new QueryWrapper<TextReviewRecord>()
                .eq("target_type", targetType)
                .eq("target_id", targetId)
                .eq("content_hash", hash)
                .eq("rule_version", match.version()));
        if (existing != null) {
            if (!PENDING.equals(existing.getStatus()) && "NEW".equals(source)) {
                throw new BusinessException("该内容在当前规则版本下已有审核结果，请修改后重试");
            }
            return PENDING.equals(existing.getStatus());
        }
        TextReviewRecord record = new TextReviewRecord();
        record.setTargetType(targetType);
        record.setTargetId(targetId);
        record.setContentHash(hash);
        record.setRuleVersion(match.version());
        String matched = String.join("、", match.phrases());
        record.setMatchedRules(matched.length() > 500 ? matched.substring(0, 500) : matched);
        record.setStatus(PENDING);
        record.setSource(source);
        record.setCandidateNickname(candidateNickname);
        record.setCandidateBio(candidateBio);
        try {
            reviewMapper.insert(record);
        } catch (DuplicateKeyException ignored) {
            // 复扫和新提交并发命中同一版本、同一内容时，唯一键保证只生成一条待办。
        }
        return true;
    }

    public boolean eligibleForRescan(String targetType, Long targetId, String text, long version) {
        if (!ruleService.match(text).hit()) {
            return false;
        }
        TextReviewRecord existing = reviewMapper.selectOne(new QueryWrapper<TextReviewRecord>()
                .eq("target_type", targetType).eq("target_id", targetId)
                .eq("content_hash", fingerprint(text)).eq("rule_version", version));
        return existing == null || PENDING.equals(existing.getStatus());
    }

    public Page<TextReviewRecord> pagePending(long pageNum, long pageSize) {
        Page<TextReviewRecord> page = reviewMapper.selectPage(new Page<>(pageNum, pageSize),
                new QueryWrapper<TextReviewRecord>()
                        .eq("status", PENDING)
                        .orderByAsc("create_time", "id"));
        for (TextReviewRecord record : page.getRecords()) {
            record.setTargetText(reviewText(record));
        }
        return page;
    }

    private String reviewText(TextReviewRecord record) {
        if (ARTICLE.equals(record.getTargetType())) {
            Article article = articleMapper.selectById(record.getTargetId());
            return article == null ? "目标已删除" : article.getTitle() + "\n" + article.getContent();
        }
        if (COMMENT.equals(record.getTargetType())) {
            Comment comment = commentMapper.selectById(record.getTargetId());
            return comment == null ? "目标已删除" : comment.getContent();
        }
        if (PROFILE.equals(record.getTargetType())) {
            User user = userMapper.selectById(record.getTargetId());
            if (user == null) return "目标已删除";
            String nickname = "RESCAN".equals(record.getSource()) ? user.getNickname() : record.getCandidateNickname();
            String bio = "RESCAN".equals(record.getSource()) ? user.getBio() : record.getCandidateBio();
            return user.getUsername() + "\n" + Objects.toString(nickname, "") + "\n" + Objects.toString(bio, "");
        }
        return "未知审核目标";
    }

    public boolean hasPendingProfile(Long userId) {
        Long count = reviewMapper.selectCount(new QueryWrapper<TextReviewRecord>()
                .eq("target_type", PROFILE)
                .eq("target_id", userId)
                .eq("status", PENDING));
        return count != null && count > 0;
    }

    @Transactional
    public void finishArticle(Long articleId, Long auditorId, boolean approved, String reason) {
        for (TextReviewRecord record : pendingFor(ARTICLE, articleId)) {
            finish(record, auditorId, approved ? APPROVED : REJECTED, reason);
        }
    }

    @Transactional
    public void closeDeleted(String targetType, Long targetId) {
        for (TextReviewRecord record : reviewMapper.selectList(new QueryWrapper<TextReviewRecord>()
                .eq("target_type", targetType).eq("target_id", targetId).eq("status", PENDING).last("FOR UPDATE"))) {
            finish(record, null, SUPERSEDED, "目标内容已删除");
        }
    }

    @Transactional
    public void supersedeProfile(Long userId, String keepHash, Long keepVersion) {
        for (TextReviewRecord record : pendingFor(PROFILE, userId)) {
            if (!record.getContentHash().equals(keepHash)
                    || !record.getRuleVersion().equals(keepVersion)) {
                finish(record, null, SUPERSEDED, "用户提交了新的资料");
            }
        }
    }

    @Transactional
    public void decide(Long reviewId, Long auditorId, boolean approved, String reason) {
        if (!approved && !StringUtils.hasText(reason)) {
            throw new BusinessException("驳回原因不能为空");
        }
        // 评论审核与采纳/删除/复扫统一先锁父文章，避免审核记录与回答锁反向等待。
        TextReviewRecord candidate = reviewMapper.selectById(reviewId);
        if (candidate != null && COMMENT.equals(candidate.getTargetType())) {
            Comment comment = commentMapper.selectById(candidate.getTargetId());
            if (comment != null) {
                articleMapper.lockById(comment.getArticleId());
                if (comment.getParentCommentId() != null && comment.getParentCommentId() > 0) {
                    commentMapper.lockById(comment.getParentCommentId());
                }
            }
        }
        TextReviewRecord record = reviewMapper.selectForUpdate(reviewId);
        if (record == null || !PENDING.equals(record.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "待办不存在或已处理");
        }
        if (ARTICLE.equals(record.getTargetType())) {
            throw new BusinessException("文章请使用现有文章审核入口");
        }
        if (COMMENT.equals(record.getTargetType())) {
            decideComment(record, approved);
        } else if (PROFILE.equals(record.getTargetType())) {
            decideProfile(record, approved);
        } else {
            throw new BusinessException("未知审核目标");
        }
        finish(record, auditorId, approved ? APPROVED : REJECTED, reason);
    }

    private void decideComment(TextReviewRecord record, boolean approved) {
        Comment comment = commentMapper.lockById(record.getTargetId());
        if (comment == null || !PENDING.equals(comment.getStatus())) {
            throw new BusinessException("评论已不存在或状态已变化");
        }
        Article article = articleMapper.lockById(comment.getArticleId());
        boolean reply = comment.getParentCommentId() != null && comment.getParentCommentId() > 0;
        if (approved && reply) {
            Comment parent = commentMapper.lockById(comment.getParentCommentId());
            if (article == null || !ArticleService.STATUS_PUBLISHED.equals(article.getStatus())
                    || parent == null || !CommentService.isTopLevel(parent)
                    || !comment.getArticleId().equals(parent.getArticleId())
                    || !CommentService.STATUS_PUBLISHED.equals(parent.getStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "父回答或文章未公开，不能批准回复");
            }
        }
        comment.setStatus(approved ? "PUBLISHED" : REJECTED);
        commentMapper.updateById(comment);
        if (approved && article != null && ArticleService.STATUS_PUBLISHED.equals(article.getStatus())) {
            if (reply) notificationService.notifyReply(comment.getId());
            else notificationService.notifyComment(article, comment.getUserId(), comment.getContent());
        }
    }

    private void decideProfile(TextReviewRecord record, boolean approved) {
        User user = userMapper.selectById(record.getTargetId());
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        if (approved) {
            if (!"RESCAN".equals(record.getSource())) {
                user.setNickname(record.getCandidateNickname());
                user.setBio(record.getCandidateBio());
            }
            user.setPublicProfileStatus("PUBLISHED");
        } else if ("RESCAN".equals(record.getSource())) {
            user.setPublicProfileStatus(REJECTED);
        } else if (PENDING.equals(user.getPublicProfileStatus())) {
            // 旧公开资料在历史复扫后被隐藏；驳回新候选值不能把旧违规资料重新放出。
            user.setPublicProfileStatus(REJECTED);
        }
        userMapper.updateById(user);
    }

    private void finish(TextReviewRecord record, Long auditorId, String status, String reason) {
        record.setStatus(status);
        record.setReviewerId(auditorId);
        record.setReason(reason);
        record.setReviewedTime(LocalDateTime.now());
        reviewMapper.updateById(record);
    }

    private java.util.List<TextReviewRecord> pendingFor(String targetType, Long targetId) {
        return reviewMapper.selectList(new QueryWrapper<TextReviewRecord>()
                .eq("target_type", targetType)
                .eq("target_id", targetId)
                .eq("status", PENDING));
    }

    public static String fingerprint(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(TextRuleService.normalize(text).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}

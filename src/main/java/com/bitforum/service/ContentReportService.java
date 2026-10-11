package com.bitforum.service;

import com.bitforum.exception.BusinessException;

import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bitforum.entity.Article;
import com.bitforum.entity.Comment;
import com.bitforum.entity.ContentReport;
import com.bitforum.mapper.ArticleMapper;
import com.bitforum.mapper.CommentMapper;
import com.bitforum.mapper.ContentReportMapper;

@Service
public class ContentReportService {
    public static final String TARGET_ARTICLE = "ARTICLE";
    public static final String TARGET_COMMENT = "COMMENT";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RESOLVED = "RESOLVED";
    public static final String STATUS_REJECTED = "REJECTED";

    @Autowired
    private ContentReportMapper contentReportMapper;
    @Autowired
    private ArticleMapper articleMapper;
    @Autowired
    private CommentMapper commentMapper;

    @Transactional
    public ContentReport reportArticle(Long reporterId, Long articleId, String reason) {
        Article article = articleMapper.selectById(articleId);
        if (article == null || !ArticleService.STATUS_PUBLISHED.equals(article.getStatus())) {
            throw new BusinessException("只能举报已发布文章");
        }
        if (reporterId.equals(article.getUserId())) {
            throw new BusinessException("不能举报自己的文章");
        }
        ensureNoPendingReport(reporterId, TARGET_ARTICLE, articleId);
        return createReport(reporterId, TARGET_ARTICLE, articleId, article.getUserId(), reason);
    }

    @Transactional
    public ContentReport reportComment(Long reporterId, Long commentId, String reason) {
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null || !CommentService.STATUS_PUBLISHED.equals(comment.getStatus())) {
            throw new BusinessException("评论不存在");
        }
        Article article = articleMapper.selectById(comment.getArticleId());
        if (article == null) {
            throw new BusinessException("评论所属文章不存在");
        }
        if (!ArticleService.STATUS_PUBLISHED.equals(article.getStatus())) {
            throw new BusinessException("评论所属文章不是已发布状态");
        }
        if (comment.getParentCommentId() != null && comment.getParentCommentId() > 0) {
            Comment parent = commentMapper.selectById(comment.getParentCommentId());
            if (parent == null || !CommentService.isTopLevel(parent)
                    || !comment.getArticleId().equals(parent.getArticleId())
                    || !CommentService.STATUS_PUBLISHED.equals(parent.getStatus())) {
                throw new BusinessException("评论不存在");
            }
        }
        if (reporterId.equals(comment.getUserId())) {
            throw new BusinessException("不能举报自己的评论");
        }
        ensureNoPendingReport(reporterId, TARGET_COMMENT, commentId);
        return createReport(reporterId, TARGET_COMMENT, commentId, comment.getUserId(), reason);
    }

    public Page<ContentReport> pageMyReports(Long reporterId, long pageNum, long pageSize) {
        Page<ContentReport> page = new Page<>(pageNum, pageSize);
        QueryWrapper<ContentReport> wrapper = new QueryWrapper<>();
        wrapper.eq("reporter_id", reporterId).orderByDesc("create_time");
        return contentReportMapper.selectPage(page, wrapper);
    }

    public Page<ContentReport> pageAdminReports(long pageNum, long pageSize, String status) {
        String reportStatus = StringUtils.hasText(status) ? status : STATUS_PENDING;
        validateStatus(reportStatus);

        Page<ContentReport> page = new Page<>(pageNum, pageSize);
        QueryWrapper<ContentReport> wrapper = new QueryWrapper<>();
        wrapper.eq("status", reportStatus).orderByDesc("create_time");
        return contentReportMapper.selectPage(page, wrapper);
    }

    @Transactional
    public void resolve(Long reportId, Long handlerId, String handleResult) {
        handle(reportId, handlerId, handleResult, STATUS_RESOLVED);
    }

    @Transactional
    public void reject(Long reportId, Long handlerId, String handleResult) {
        handle(reportId, handlerId, handleResult, STATUS_REJECTED);
    }

    private ContentReport createReport(
            Long reporterId,
            String targetType,
            Long targetId,
            Long targetOwnerId,
            String reason) {
        ContentReport report = new ContentReport();
        report.setReporterId(reporterId);
        report.setTargetType(targetType);
        report.setTargetId(targetId);
        report.setTargetOwnerId(targetOwnerId);
        report.setReason(reason);
        report.setStatus(STATUS_PENDING);
        try {
            contentReportMapper.insert(report);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("不能重复提交同一对象的待处理举报");
        }
        return report;
    }

    private void ensureNoPendingReport(Long reporterId, String targetType, Long targetId) {
        Long count = contentReportMapper.selectCount(new QueryWrapper<ContentReport>()
                .eq("reporter_id", reporterId)
                .eq("target_type", targetType)
                .eq("target_id", targetId)
                .eq("status", STATUS_PENDING));
        if (count > 0) {
            throw new BusinessException("不能重复提交同一对象的待处理举报");
        }
    }

    private void handle(Long reportId, Long handlerId, String handleResult, String handledStatus) {
        ContentReport report = contentReportMapper.selectById(reportId);
        if (report == null) {
            throw new BusinessException("举报记录不存在");
        }
        if (!STATUS_PENDING.equals(report.getStatus())) {
            throw new BusinessException("已处理举报不能重复处理");
        }
        report.setStatus(handledStatus);
        report.setHandlerId(handlerId);
        report.setHandleResult(handleResult);
        report.setHandleTime(LocalDateTime.now());
        contentReportMapper.updateById(report);
    }

    private void validateStatus(String status) {
        if (!STATUS_PENDING.equals(status)
                && !STATUS_RESOLVED.equals(status)
                && !STATUS_REJECTED.equals(status)) {
            throw new BusinessException("举报状态不正确");
        }
    }
}

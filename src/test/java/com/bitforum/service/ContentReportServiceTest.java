package com.bitforum.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bitforum.entity.Article;
import com.bitforum.entity.Category;
import com.bitforum.entity.Comment;
import com.bitforum.entity.ContentReport;
import com.bitforum.mapper.ArticleMapper;
import com.bitforum.mapper.CategoryMapper;
import com.bitforum.mapper.CommentMapper;
import com.bitforum.mapper.ContentReportMapper;

@SpringBootTest
@Transactional
public class ContentReportServiceTest {
    @Autowired
    private ContentReportService contentReportService;
    @Autowired
    private ContentReportMapper contentReportMapper;
    @Autowired
    private ArticleMapper articleMapper;
    @Autowired
    private CommentMapper commentMapper;
    @Autowired
    private CategoryMapper categoryMapper;

    @Test
    void userShouldReportOtherUsersPublishedArticle() {
        Article article = createArticle(51001L, ArticleService.STATUS_PUBLISHED);

        ContentReport report = contentReportService.reportArticle(52001L, article.getId(), "内容违规");

        assertNotNull(report.getId());
        assertEquals(52001L, report.getReporterId());
        assertEquals(ContentReportService.TARGET_ARTICLE, report.getTargetType());
        assertEquals(article.getId(), report.getTargetId());
        assertEquals(51001L, report.getTargetOwnerId());
        assertEquals(ContentReportService.STATUS_PENDING, report.getStatus());
    }

    @Test
    void userShouldNotReportOwnArticle() {
        Article article = createArticle(51002L, ArticleService.STATUS_PUBLISHED);

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            contentReportService.reportArticle(51002L, article.getId(), "举报自己的文章");
        });

        assertEquals("不能举报自己的文章", exception.getMessage());
    }

    @Test
    void userShouldNotReportUnpublishedArticle() {
        String[] statuses = {
                ArticleService.STATUS_DRAFT,
                ArticleService.STATUS_PENDING,
                ArticleService.STATUS_REJECTED,
                ArticleService.STATUS_OFFLINE
        };

        for (String status : statuses) {
            Article article = createArticle(51003L, status);

            RuntimeException exception = assertThrows(RuntimeException.class, () -> {
                contentReportService.reportArticle(52003L, article.getId(), "举报非公开文章");
            });

            assertEquals("只能举报已发布文章", exception.getMessage());
        }
    }

    @Test
    void userShouldNotSubmitDuplicatePendingReport() {
        Article article = createArticle(51004L, ArticleService.STATUS_PUBLISHED);

        contentReportService.reportArticle(52004L, article.getId(), "第一次举报");

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            contentReportService.reportArticle(52004L, article.getId(), "重复举报");
        });

        assertEquals("不能重复提交同一对象的待处理举报", exception.getMessage());
    }

    @Test
    void duplicatePendingReportShouldBeRejectedByDatabaseConstraint() {
        Article article = createArticle(51016L, ArticleService.STATUS_PUBLISHED);
        ContentReport firstReport = buildPendingReport(52016L, ContentReportService.TARGET_ARTICLE,
                article.getId(), article.getUserId(), "第一次举报");
        ContentReport duplicateReport = buildPendingReport(52016L, ContentReportService.TARGET_ARTICLE,
                article.getId(), article.getUserId(), "并发重复举报");

        contentReportMapper.insert(firstReport);

        assertThrows(DuplicateKeyException.class, () -> {
            contentReportMapper.insert(duplicateReport);
        });
    }

    @Test
    void userCanReportSameTargetAgainAfterHandled() {
        Article article = createArticle(51005L, ArticleService.STATUS_PUBLISHED);
        ContentReport firstReport = contentReportService.reportArticle(52005L, article.getId(), "第一次举报");
        contentReportService.resolve(firstReport.getId(), 90005L, "举报成立");

        ContentReport secondReport = contentReportService.reportArticle(52005L, article.getId(), "再次举报");

        assertNotNull(secondReport.getId());
        assertEquals(ContentReportService.STATUS_PENDING, secondReport.getStatus());
        assertEquals(2L, contentReportMapper.selectCount(new QueryWrapper<ContentReport>()
                .eq("reporter_id", 52005L)
                .eq("target_type", ContentReportService.TARGET_ARTICLE)
                .eq("target_id", article.getId())));
    }

    @Test
    void userShouldReportOtherUsersComment() {
        Article article = createArticle(51006L, ArticleService.STATUS_PUBLISHED);
        Comment comment = createComment(article.getId(), 53006L);

        ContentReport report = contentReportService.reportComment(52006L, comment.getId(), "恶意评论");

        assertNotNull(report.getId());
        assertEquals(ContentReportService.TARGET_COMMENT, report.getTargetType());
        assertEquals(comment.getId(), report.getTargetId());
        assertEquals(53006L, report.getTargetOwnerId());
    }

    @Test
    void userShouldNotReportOwnComment() {
        Article article = createArticle(51007L, ArticleService.STATUS_PUBLISHED);
        Comment comment = createComment(article.getId(), 52007L);

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            contentReportService.reportComment(52007L, comment.getId(), "举报自己的评论");
        });

        assertEquals("不能举报自己的评论", exception.getMessage());
    }

    @Test
    void reportCommentShouldFailWhenArticleIsMissingOrNotPublished() {
        Comment missingArticleComment = createComment(99999999L, 53008L);

        RuntimeException missingArticleException = assertThrows(RuntimeException.class, () -> {
            contentReportService.reportComment(52008L, missingArticleComment.getId(), "所属文章不存在");
        });
        assertEquals("评论所属文章不存在", missingArticleException.getMessage());

        Article offlineArticle = createArticle(51008L, ArticleService.STATUS_OFFLINE);
        Comment offlineComment = createComment(offlineArticle.getId(), 53009L);

        RuntimeException offlineException = assertThrows(RuntimeException.class, () -> {
            contentReportService.reportComment(52008L, offlineComment.getId(), "所属文章下架");
        });
        assertEquals("评论所属文章不是已发布状态", offlineException.getMessage());
    }

    @Test
    void userShouldOnlyPageOwnReports() {
        Article firstArticle = createArticle(51009L, ArticleService.STATUS_PUBLISHED);
        Article secondArticle = createArticle(51010L, ArticleService.STATUS_PUBLISHED);
        ContentReport ownReport = contentReportService.reportArticle(52009L, firstArticle.getId(), "我的举报");
        contentReportService.reportArticle(52010L, secondArticle.getId(), "别人的举报");

        Page<ContentReport> page = contentReportService.pageMyReports(52009L, 1, 10);

        assertEquals(1L, page.getTotal());
        assertEquals(ownReport.getId(), page.getRecords().get(0).getId());
    }

    @Test
    void adminShouldPageReportsByStatus() {
        Article resolvedArticle = createArticle(51011L, ArticleService.STATUS_PUBLISHED);
        Article pendingArticle = createArticle(51012L, ArticleService.STATUS_PUBLISHED);
        ContentReport resolved = contentReportService.reportArticle(52011L, resolvedArticle.getId(), "已处理举报");
        contentReportService.resolve(resolved.getId(), 90011L, "举报成立");
        ContentReport pending = contentReportService.reportArticle(52012L, pendingArticle.getId(), "待处理举报");

        Page<ContentReport> pendingPage = contentReportService.pageAdminReports(1, 10, ContentReportService.STATUS_PENDING);

        assertTrue(pendingPage.getRecords().stream().anyMatch(report -> report.getId().equals(pending.getId())));
        assertTrue(pendingPage.getRecords().stream().noneMatch(report -> report.getId().equals(resolved.getId())));
    }

    @Test
    void adminShouldResolvePendingReport() {
        Article article = createArticle(51013L, ArticleService.STATUS_PUBLISHED);
        ContentReport report = contentReportService.reportArticle(52013L, article.getId(), "举报成立测试");

        contentReportService.resolve(report.getId(), 90013L, "举报成立，已记录");

        ContentReport handled = contentReportMapper.selectById(report.getId());
        assertEquals(ContentReportService.STATUS_RESOLVED, handled.getStatus());
        assertEquals(90013L, handled.getHandlerId());
        assertEquals("举报成立，已记录", handled.getHandleResult());
        assertNotNull(handled.getHandleTime());
    }

    @Test
    void adminShouldRejectPendingReport() {
        Article article = createArticle(51014L, ArticleService.STATUS_PUBLISHED);
        ContentReport report = contentReportService.reportArticle(52014L, article.getId(), "举报驳回测试");

        contentReportService.reject(report.getId(), 90014L, "举报不成立");

        ContentReport handled = contentReportMapper.selectById(report.getId());
        assertEquals(ContentReportService.STATUS_REJECTED, handled.getStatus());
        assertEquals(90014L, handled.getHandlerId());
        assertEquals("举报不成立", handled.getHandleResult());
    }

    @Test
    void handledReportShouldNotBeHandledAgain() {
        Article article = createArticle(51015L, ArticleService.STATUS_PUBLISHED);
        ContentReport report = contentReportService.reportArticle(52015L, article.getId(), "重复处理测试");
        contentReportService.resolve(report.getId(), 90015L, "已处理");

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            contentReportService.reject(report.getId(), 90016L, "再次处理");
        });

        assertEquals("已处理举报不能重复处理", exception.getMessage());
    }

    private Article createArticle(Long userId, String status) {
        Category category = categoryMapper.selectList(null).get(0);
        Article article = new Article();
        article.setTitle("M5-report-" + UUID.randomUUID());
        article.setContent("M5 举报测试文章");
        article.setUserId(userId);
        article.setCategoryId(category.getId());
        article.setStatus(status);
        articleMapper.insert(article);
        return article;
    }

    private Comment createComment(Long articleId, Long userId) {
        Comment comment = new Comment();
        comment.setArticleId(articleId);
        comment.setUserId(userId);
        comment.setContent("M5 举报测试评论-" + UUID.randomUUID());
        comment.setParentCommentId(0L);
        commentMapper.insert(comment);
        return comment;
    }

    private ContentReport buildPendingReport(
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
        report.setStatus(ContentReportService.STATUS_PENDING);
        return report;
    }
}

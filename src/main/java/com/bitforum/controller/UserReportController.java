package com.bitforum.controller;

import com.bitforum.exception.BusinessException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bitforum.common.Result;
import com.bitforum.dto.ArticleReportRequest;
import com.bitforum.dto.CommentReportRequest;
import com.bitforum.entity.ContentReport;
import com.bitforum.service.ContentReportService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/user/reports")
@Tag(name = "举报治理", description = "用户举报文章、举报评论和查看自己的举报记录")
@SecurityRequirement(name = "bearerAuth")
public class UserReportController {
    @Autowired
    private ContentReportService contentReportService;

    @PostMapping("/article")
    @Operation(summary = "举报文章", description = "只能举报他人的已发布文章")
    public Result<ContentReport> reportArticle(
            @RequestAttribute("userId") Long userId,
            @Valid @RequestBody ArticleReportRequest request) {
        try {
            ContentReport report = contentReportService.reportArticle(
                    userId,
                    request.getArticleId(),
                    request.getReason());
            return Result.ok("举报文章提交成功", report);
        } catch (BusinessException e) {
            return Result.fail(e.getCode(), e.getMessage());
        }
    }

    @PostMapping("/comment")
    @Operation(summary = "举报评论", description = "只能举报他人的评论，且评论所属文章需已发布")
    public Result<ContentReport> reportComment(
            @RequestAttribute("userId") Long userId,
            @Valid @RequestBody CommentReportRequest request) {
        try {
            ContentReport report = contentReportService.reportComment(
                    userId,
                    request.getCommentId(),
                    request.getReason());
            return Result.ok("举报评论提交成功", report);
        } catch (BusinessException e) {
            return Result.fail(e.getCode(), e.getMessage());
        }
    }

    @GetMapping
    @Operation(summary = "分页查询我的举报记录")
    public Result<Page<ContentReport>> pageMyReports(
            @RequestAttribute("userId") Long userId,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize) {
        Page<ContentReport> reportPage = contentReportService.pageMyReports(userId, pageNum, pageSize);
        return Result.ok("我的举报分页查询成功", reportPage);
    }
}

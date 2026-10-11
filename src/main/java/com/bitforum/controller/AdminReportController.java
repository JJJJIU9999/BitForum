package com.bitforum.controller;

import com.bitforum.exception.BusinessException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bitforum.common.Result;
import com.bitforum.dto.ReportHandleRequest;
import com.bitforum.entity.ContentReport;
import com.bitforum.service.ContentReportService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/admin/reports")
@Tag(name = "举报治理", description = "管理员分页查看举报并处理成立或驳回")
@SecurityRequirement(name = "bearerAuth")
public class AdminReportController {
    @Autowired
    private ContentReportService contentReportService;

    @GetMapping
    @Operation(summary = "管理员分页查询举报")
    public Result<Page<ContentReport>> pageReports(
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(defaultValue = "PENDING") String status) {
        try {
            Page<ContentReport> reportPage = contentReportService.pageAdminReports(pageNum, pageSize, status);
            return Result.ok("管理员举报分页查询成功", reportPage);
        } catch (BusinessException e) {
            return Result.fail(e.getCode(), e.getMessage());
        }
    }

    @PutMapping("/resolve")
    @Operation(summary = "管理员处理举报成立", description = "只记录处理结果，不自动下架文章或删除评论")
    public Result<String> resolve(
            @RequestAttribute("userId") Long handlerId,
            @Valid @RequestBody ReportHandleRequest request) {
        try {
            contentReportService.resolve(request.getReportId(), handlerId, request.getHandleResult());
            return Result.ok("举报处理成功", null);
        } catch (BusinessException e) {
            return Result.fail(e.getCode(), e.getMessage());
        }
    }

    @PutMapping("/reject")
    @Operation(summary = "管理员驳回举报")
    public Result<String> reject(
            @RequestAttribute("userId") Long handlerId,
            @Valid @RequestBody ReportHandleRequest request) {
        try {
            contentReportService.reject(request.getReportId(), handlerId, request.getHandleResult());
            return Result.ok("举报驳回成功", null);
        } catch (BusinessException e) {
            return Result.fail(e.getCode(), e.getMessage());
        }
    }
}

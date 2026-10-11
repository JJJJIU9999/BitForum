package com.bitforum.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bitforum.common.Result;
import com.bitforum.dto.AdminDashboardSummaryResponse;
import com.bitforum.service.AdminDashboardService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/admin/dashboard")
@Tag(name = "管理员数据看板", description = "管理员社区运营数据统计汇总")
@SecurityRequirement(name = "bearerAuth")
public class AdminDashboardController {
    @Autowired
    private AdminDashboardService adminDashboardService;

    @GetMapping("/summary")
    @Operation(summary = "查询管理员数据看板汇总", description = "实时聚合用户、文章、评论、板块、收藏、通知、举报和热门文章统计")
    public Result<AdminDashboardSummaryResponse> summary() {
        return Result.ok("管理员数据看板统计成功", adminDashboardService.summary());
    }
}

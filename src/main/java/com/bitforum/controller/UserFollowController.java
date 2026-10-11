package com.bitforum.controller;

import com.bitforum.exception.BusinessException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bitforum.common.Result;
import com.bitforum.dto.FollowStatsResponse;
import com.bitforum.dto.FollowUserResponse;
import com.bitforum.service.UserFollowService;
import com.bitforum.util.JwtUtil;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@Tag(name = "关注与粉丝", description = "用户关注、取消关注、粉丝列表和关注列表")
public class UserFollowController {
    @Autowired
    private UserFollowService userFollowService;
    @Autowired
    private JwtUtil jwtUtil;

    @PostMapping("/api/users/{userId}/follow")
    @Operation(summary = "关注用户", description = "当前登录用户关注目标用户", security = @SecurityRequirement(name = "bearerAuth"))
    public Result<FollowStatsResponse> follow(
            @Parameter(description = "目标用户 ID") @PathVariable Long userId,
            @RequestAttribute("userId") Long currentUserId) {
        try {
            return Result.ok("关注成功", userFollowService.follow(currentUserId, userId));
        } catch (BusinessException e) {
            return Result.fail(e.getCode(), e.getMessage());
        }
    }

    @DeleteMapping("/api/users/{userId}/follow")
    @Operation(summary = "取消关注用户", description = "当前登录用户取消关注目标用户", security = @SecurityRequirement(name = "bearerAuth"))
    public Result<FollowStatsResponse> unfollow(
            @Parameter(description = "目标用户 ID") @PathVariable Long userId,
            @RequestAttribute("userId") Long currentUserId) {
        try {
            return Result.ok("取消关注成功", userFollowService.unfollow(currentUserId, userId));
        } catch (BusinessException e) {
            return Result.fail(e.getCode(), e.getMessage());
        }
    }

    @GetMapping("/api/users/{userId}/followers")
    @Operation(summary = "查看粉丝列表", description = "公开分页查看指定用户的粉丝列表")
    public Result<Page<FollowUserResponse>> followers(
            @Parameter(description = "用户 ID") @PathVariable Long userId,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize) {
        try {
            return Result.ok("粉丝列表查询成功", userFollowService.pageFollowers(userId, pageNum, pageSize));
        } catch (BusinessException e) {
            return Result.fail(e.getCode(), e.getMessage());
        }
    }

    @GetMapping("/api/users/{userId}/following")
    @Operation(summary = "查看关注列表", description = "公开分页查看指定用户关注的用户列表")
    public Result<Page<FollowUserResponse>> following(
            @Parameter(description = "用户 ID") @PathVariable Long userId,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize) {
        try {
            return Result.ok("关注列表查询成功", userFollowService.pageFollowing(userId, pageNum, pageSize));
        } catch (BusinessException e) {
            return Result.fail(e.getCode(), e.getMessage());
        }
    }

    @GetMapping("/api/users/{userId}/follow-stats")
    @Operation(summary = "查询用户关注统计", description = "公开查询关注数、粉丝数和当前登录用户是否已关注")
    public Result<FollowStatsResponse> followStats(
            @Parameter(description = "用户 ID") @PathVariable Long userId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        try {
            return Result.ok("关注统计查询成功", userFollowService.getStats(userId, parseOptionalUserId(authorization)));
        } catch (BusinessException e) {
            return Result.fail(e.getCode(), e.getMessage());
        }
    }

    private Long parseOptionalUserId(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        try {
            return jwtUtil.getUserId(authorization.substring(7));
        } catch (RuntimeException e) {
            return null;
        }
    }
}


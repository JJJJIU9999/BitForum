package com.bitforum.controller;

import com.bitforum.exception.BusinessException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bitforum.common.Result;
import com.bitforum.dto.PublicUserProfileResponse;
import com.bitforum.dto.UserProfileResponse;
import com.bitforum.dto.UserProfileUpdateRequest;
import com.bitforum.entity.Article;
import com.bitforum.service.ArticleService;
import com.bitforum.service.UserService;
import com.bitforum.util.JwtUtil;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@Tag(name = "用户资料 / 用户主页", description = "当前用户资料维护、公开主页和公开文章列表")
public class UserProfileController {
    @Autowired
    private UserService userService;
    @Autowired
    private ArticleService articleService;
    @Autowired
    private JwtUtil jwtUtil;

    @GetMapping("/api/user/profile")
    @Operation(summary = "查询当前用户资料", description = "需要登录，返回当前用户个人资料", security = @SecurityRequirement(name = "bearerAuth"))
    public Result<UserProfileResponse> currentProfile(@RequestAttribute("userId") Long userId) {
        try {
            return Result.ok("个人资料查询成功", userService.getCurrentProfile(userId));
        } catch (BusinessException e) {
            return Result.fail(e.getCode(), e.getMessage());
        }
    }

    @PutMapping("/api/user/profile")
    @Operation(summary = "更新当前用户资料", description = "需要登录，仅更新 nickname、bio；头像单独上传并人工审核", security = @SecurityRequirement(name = "bearerAuth"))
    public Result<UserProfileResponse> updateProfile(
            @RequestAttribute("userId") Long userId,
            @Valid @RequestBody UserProfileUpdateRequest request) {
        try {
            return Result.ok("个人资料更新成功", userService.updateProfile(userId, request));
        } catch (BusinessException e) {
            return Result.fail(e.getCode(), e.getMessage());
        }
    }

    @GetMapping("/api/users/{userId}/profile")
    @Operation(summary = "查询公开用户主页", description = "公开接口，不返回 password")
    public Result<PublicUserProfileResponse> publicProfile(
            @PathVariable Long userId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        try {
            return Result.ok("公开用户主页查询成功", userService.getPublicProfile(userId, parseOptionalUserId(authorization)));
        } catch (BusinessException e) {
            return Result.fail(e.getCode(), e.getMessage());
        }
    }

    @GetMapping("/api/users/{userId}/articles")
    @Operation(summary = "查询用户公开文章", description = "公开接口，仅返回指定用户已发布文章")
    public Result<Page<Article>> publicArticles(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize) {
        if (userService.findById(userId) == null) {
            return Result.fail(404, "用户不存在");
        }
        Page<Article> articlePage = articleService.pagePublishedArticlesByUser(userId, pageNum, pageSize);
        return Result.ok("用户公开文章分页查询成功", articlePage);
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

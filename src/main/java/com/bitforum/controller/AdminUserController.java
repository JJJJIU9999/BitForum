package com.bitforum.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bitforum.common.Result;
import com.bitforum.dto.AdminUserResponse;
import com.bitforum.service.UserService;
import com.bitforum.service.UserService.UserStatusUpdateResult;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/admin/user")
@Tag(name = "管理员用户", description = "管理员用户分页、启用和禁用")
@SecurityRequirement(name = "bearerAuth")
public class AdminUserController {
    @Autowired
    private UserService userService;

    @GetMapping("/page")
    @Operation(summary = "管理员分页查询用户", description = "返回脱敏用户信息，不包含 password")
    public Result<Page<AdminUserResponse>> pageUsers(
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize) {
        // 后台用户列表返回 DTO，不直接返回 User 实体，避免 password 字段被序列化出去。
        Page<AdminUserResponse> userPage = userService.pageUsers(pageNum, pageSize);
        return Result.ok("管理员用户分页查询成功", userPage);
    }

    @PutMapping("/disable")
    @Operation(summary = "管理员禁用用户", description = "不能禁用当前管理员自己")
    public Result<String> disableUser(
            @RequestAttribute("userId") Long currentAdminId,
            @RequestParam Long userId) {
        // currentAdminId 来自 AdminInterceptor，不能信任前端自己传当前管理员是谁。
        UserStatusUpdateResult result = userService.disableUser(currentAdminId, userId);
        if (result == UserStatusUpdateResult.CANNOT_DISABLE_SELF) {
            return Result.fail("不能禁用当前管理员自己");
        }
        if (result == UserStatusUpdateResult.USER_NOT_FOUND) {
            return Result.fail("用户不存在");
        }
        return Result.ok("禁用用户成功", null);
    }

    @PutMapping("/enable")
    @Operation(summary = "管理员启用用户")
    public Result<String> enableUser(@RequestParam Long userId) {
        UserStatusUpdateResult result = userService.enableUser(userId);
        if (result == UserStatusUpdateResult.USER_NOT_FOUND) {
            return Result.fail("用户不存在");
        }
        return Result.ok("启用用户成功", null);
    }
}

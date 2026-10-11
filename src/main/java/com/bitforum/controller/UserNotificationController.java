package com.bitforum.controller;

import com.bitforum.exception.BusinessException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bitforum.common.Result;
import com.bitforum.entity.Notification;
import com.bitforum.service.NotificationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/user/notifications")
@Tag(name = "我的内容", description = "当前登录用户的文章、收藏、通知和举报等个人内容")
@SecurityRequirement(name = "bearerAuth")
public class UserNotificationController {
    @Autowired
    private NotificationService notificationService;

    @GetMapping
    @Operation(summary = "分页查询我的通知")
    public Result<Page<Notification>> pageNotifications(
            @RequestAttribute("userId") Long userId,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize) {
        Page<Notification> page = notificationService.pageNotifications(userId, pageNum, pageSize);
        return Result.ok("通知分页查询成功", page);
    }

    @GetMapping("/unread-count")
    @Operation(summary = "查询我的未读通知数量")
    public Result<Long> unreadCount(@RequestAttribute("userId") Long userId) {
        return Result.ok("未读通知数量查询成功", notificationService.countUnread(userId));
    }

    @PutMapping("/read")
    @Operation(summary = "标记单条通知已读")
    public Result<String> markRead(
            @RequestAttribute("userId") Long userId,
            @RequestParam Long notificationId) {
        try {
            notificationService.markRead(userId, notificationId);
            return Result.ok("通知已读成功", null);
        } catch (BusinessException e) {
            return Result.fail(e.getCode(), e.getMessage());
        }
    }

    @PutMapping("/read-all")
    @Operation(summary = "标记全部通知已读")
    public Result<String> markAllRead(@RequestAttribute("userId") Long userId) {
        notificationService.markAllRead(userId);
        return Result.ok("全部通知已读成功", null);
    }
}

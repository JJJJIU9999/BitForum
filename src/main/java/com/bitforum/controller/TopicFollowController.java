package com.bitforum.controller;

import org.springframework.web.bind.annotation.*;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bitforum.common.Result;
import com.bitforum.dto.*;
import com.bitforum.entity.Article;
import com.bitforum.service.*;
import com.bitforum.util.JwtUtil;

@RestController
public class TopicFollowController {
    private final TopicFollowService topics;
    private final ArticleService articles;
    private final JwtUtil jwt;
    private final UserService users;
    public TopicFollowController(TopicFollowService topics, ArticleService articles, JwtUtil jwt, UserService users) {
        this.topics=topics; this.articles=articles; this.jwt=jwt; this.users=users;
    }
    @GetMapping("/api/category/{categoryId}")
    public Result<TopicResponse> detail(@PathVariable Long categoryId,
            @RequestHeader(value="Authorization", required=false) String authorization) {
        return Result.ok("话题详情", topics.detail(categoryId, optionalUser(authorization)));
    }
    @PostMapping("/api/category/{categoryId}/follow")
    public Result<TopicFollowResponse> follow(@PathVariable Long categoryId, @RequestAttribute("userId") Long userId) {
        return Result.ok("关注成功", topics.follow(userId, categoryId));
    }
    @DeleteMapping("/api/category/{categoryId}/follow")
    public Result<TopicFollowResponse> unfollow(@PathVariable Long categoryId, @RequestAttribute("userId") Long userId) {
        return Result.ok("取消关注成功", topics.unfollow(userId, categoryId));
    }
    @GetMapping("/api/user/followed-topics")
    public Result<Page<TopicResponse>> following(@RequestAttribute("userId") Long userId,
            @RequestParam(defaultValue="1") long pageNum, @RequestParam(defaultValue="10") long pageSize) {
        return Result.ok("我的话题", topics.following(userId, pageNum, pageSize));
    }
    @GetMapping("/api/category/{categoryId}/contents")
    public Result<Page<Article>> contents(@PathVariable Long categoryId,
            @RequestParam(defaultValue="1") long pageNum, @RequestParam(defaultValue="10") long pageSize,
            @RequestParam(required=false) String contentType, @RequestParam(required=false) String keyword) {
        TopicFollowService.validPage(pageNum, pageSize); TopicFollowService.validType(contentType);
        topics.requireEnabled(categoryId);
        return Result.ok("话题公开内容", articles.searchPublishedArticles(keyword, categoryId, pageNum, pageSize, contentType));
    }
    @GetMapping("/api/user/follow-feed")
    public Result<FollowFeedPage> feed(@RequestAttribute("userId") Long userId,
            @RequestParam(defaultValue="8") long pageSize, @RequestParam(required=false) String contentType,
            @RequestParam(required=false) String cursor) {
        return Result.ok("关注动态", articles.followFeed(userId, pageSize, contentType, cursor));
    }
    private Long optionalUser(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) return null;
        try {
            Long id=jwt.getUserId(authorization.substring(7));
            var user=users.findById(id);
            return user != null && Integer.valueOf(UserService.STATUS_ENABLED).equals(user.getStatus()) ? id : null;
        } catch (RuntimeException e) { return null; }
    }
}

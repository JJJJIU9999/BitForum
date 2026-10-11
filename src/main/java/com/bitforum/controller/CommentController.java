package com.bitforum.controller;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bitforum.common.Result;
import com.bitforum.dto.CommentPublishRequest;
import com.bitforum.dto.AnswerLikeRequest;
import com.bitforum.util.JwtUtil;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import com.bitforum.entity.Comment;
import com.bitforum.service.CommentService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;



@RestController
@RequestMapping("/api/comment")
@Tag(name = "评论", description = "文章评论发布和查询")
public class CommentController {
    @Autowired
    private CommentService commentService;
    @Autowired private JwtUtil jwtUtil;

    @PostMapping("/publish")
    @Operation(summary = "发表评论", description = "需要登录，只能评论已发布文章", security = @SecurityRequirement(name = "bearerAuth"))
    public Result<String> publish(
            @RequestAttribute("userId") Long userId,
            @Valid @RequestBody CommentPublishRequest request) {
        // Service 负责校验文章是否存在；Controller 根据结果返回接口响应
        // userId 来自登录拦截器，articleId 和 content 来自评论发布 DTO
        boolean success = commentService.publish(userId, request.getArticleId(), request.getContent(), request.getParentCommentId());
        if (!success) {
            return Result.fail("文章不存在");
        }
        return Result.ok("评论已提交", null);
    }
        
    @GetMapping("/listAll")
    @Operation(summary = "查询文章评论", description = "公开接口，按文章 ID 查询评论列表")
    public Result<List<Comment>> listByArticle(@RequestParam Long articleId,
            @RequestParam(required = false) String sort,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long currentUserId = null;
        if (authorization != null && authorization.startsWith("Bearer ")) {
            try { currentUserId = jwtUtil.getUserId(authorization.substring(7)); }
            catch (RuntimeException ignored) { /* 公开接口：无效身份按匿名 */ }
        }
        List<Comment> commentList = commentService.listByArticleId(articleId, currentUserId, sort);
        return Result.ok("查询成功", commentList);
    }

    @GetMapping("/mine")
    @Operation(summary = "查询我在文章下的评论", description = "需要登录，包含待审及被驳回评论",
            security = @SecurityRequirement(name = "bearerAuth"))
    public Result<List<Comment>> listMine(
            @RequestAttribute("userId") Long userId,
            @RequestParam Long articleId) {
        return Result.ok("查询成功", commentService.listOwnByArticleId(userId, articleId));
    }
    
    @PostMapping("/like")
    @Operation(summary = "点赞公开顶层回答", security = @SecurityRequirement(name = "bearerAuth"))
    public Result<String> like(@RequestAttribute("userId") Long userId,
            @Valid @RequestBody AnswerLikeRequest request) {
        commentService.likeAnswer(userId, request.getCommentId());
        return Result.ok("回答已点赞", null);
    }

    @DeleteMapping("/like")
    @Operation(summary = "取消回答点赞", security = @SecurityRequirement(name = "bearerAuth"))
    public Result<String> unlike(@RequestAttribute("userId") Long userId, @RequestParam Long commentId) {
        commentService.unlikeAnswer(userId, commentId);
        return Result.ok("已取消回答点赞", null);
    }
}

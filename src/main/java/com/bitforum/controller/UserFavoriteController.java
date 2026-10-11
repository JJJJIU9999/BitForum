package com.bitforum.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bitforum.common.Result;
import com.bitforum.entity.Article;
import com.bitforum.service.ArticleService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/user/favorites")
@Tag(name = "我的内容", description = "当前登录用户的文章、收藏、通知和举报等个人内容")
@SecurityRequirement(name = "bearerAuth")
public class UserFavoriteController {
    @Autowired
    private ArticleService articleService;

    @GetMapping
    @Operation(summary = "分页查询我的收藏", description = "只返回仍为已发布状态的收藏文章")
    public Result<Page<Article>> pageMyFavorites(
            @RequestAttribute("userId") Long userId,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize) {
        Page<Article> articlePage = articleService.pageFavoriteArticles(userId, pageNum, pageSize);
        return Result.ok("我的收藏分页查询成功", articlePage);
    }
}

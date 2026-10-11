package com.bitforum.controller;

import com.bitforum.exception.BusinessException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bitforum.common.Result;
import com.bitforum.dto.CategoryCreateRequest;
import com.bitforum.dto.CategoryUpdateRequest;
import com.bitforum.entity.Category;
import com.bitforum.service.CategoryService;
import com.bitforum.service.CategoryService.CategoryDeleteResult;
import com.bitforum.service.CategoryService.CategoryUpdateResult;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/admin/category")
@Tag(name = "管理员板块", description = "管理员板块分页、创建、更新、启用、禁用和删除")
@SecurityRequirement(name = "bearerAuth")
public class AdminCategoryController {
    @Autowired
    private CategoryService categoryService;

    @GetMapping("/page")
    @Operation(summary = "管理员分页查询板块")
    public Result<Page<Category>> pageCategories(
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) Integer status) {
        return Result.ok("管理员板块分页查询成功", categoryService.pageCategories(pageNum, pageSize, status));
    }

    @PostMapping("/create")
    @Operation(summary = "管理员创建板块")
    public Result<Category> create(@Valid @RequestBody CategoryCreateRequest request) {
        try {
            Category category = categoryService.create(
                    request.getName(),
                    request.getDescription(),
                    request.getSortOrder());
            return Result.ok("板块创建成功", category);
        } catch (BusinessException e) {
            return Result.fail(e.getCode(), e.getMessage());
        }
    }

    @PutMapping("/update")
    @Operation(summary = "管理员更新板块")
    public Result<String> update(@Valid @RequestBody CategoryUpdateRequest request) {
        CategoryUpdateResult result = categoryService.update(
                request.getCategoryId(),
                request.getName(),
                request.getDescription(),
                request.getSortOrder());
        if (result == CategoryUpdateResult.NOT_FOUND) {
            return Result.fail(404, "板块不存在");
        }
        if (result == CategoryUpdateResult.NAME_EXISTS) {
            return Result.fail("板块名称已存在");
        }
        return Result.ok("板块更新成功", null);
    }

    @PutMapping("/enable")
    @Operation(summary = "管理员启用板块")
    public Result<String> enable(@RequestParam Long categoryId) {
        CategoryUpdateResult result = categoryService.enable(categoryId);
        if (result == CategoryUpdateResult.NOT_FOUND) {
            return Result.fail(404, "板块不存在");
        }
        return Result.ok("板块启用成功", null);
    }

    @PutMapping("/disable")
    @Operation(summary = "管理员禁用板块")
    public Result<String> disable(@RequestParam Long categoryId) {
        CategoryUpdateResult result = categoryService.disable(categoryId);
        if (result == CategoryUpdateResult.NOT_FOUND) {
            return Result.fail(404, "板块不存在");
        }
        return Result.ok("板块禁用成功", null);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "管理员删除无文章板块")
    public Result<String> delete(@RequestParam Long categoryId) {
        CategoryDeleteResult result = categoryService.delete(categoryId);
        if (result == CategoryDeleteResult.NOT_FOUND) {
            return Result.fail(404, "板块不存在");
        }
        if (result == CategoryDeleteResult.HAS_ARTICLE) {
            return Result.fail("板块下已有文章，不能删除");
        }
        return Result.ok("板块删除成功", null);
    }
}

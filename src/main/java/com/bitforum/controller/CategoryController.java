package com.bitforum.controller;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bitforum.common.Result;
import com.bitforum.entity.Category;
import com.bitforum.service.CategoryService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/category")
@Tag(name = "板块", description = "公共板块列表")
public class CategoryController {
    @Autowired
    private CategoryService categoryService;

    @GetMapping("/list")
    @Operation(summary = "查询启用板块列表", description = "公开接口，仅返回启用板块")
    public Result<List<Category>> listEnabled() {
        return Result.ok("板块列表查询成功", categoryService.listEnabled());
    }
}

package com.bitforum.service;

import com.bitforum.exception.BusinessException;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bitforum.entity.Article;
import com.bitforum.entity.Category;
import com.bitforum.mapper.ArticleMapper;
import com.bitforum.mapper.CategoryMapper;

@Service
public class CategoryService {
    public static final int STATUS_ENABLED = 1;
    public static final int STATUS_DISABLED = 0;

    @Autowired
    private CategoryMapper categoryMapper;

    @Autowired
    private ArticleMapper articleMapper;

    public List<Category> listEnabled() {
        QueryWrapper<Category> wrapper = new QueryWrapper<>();
        wrapper.eq("status", STATUS_ENABLED)
                .orderByAsc("sort_order")
                .orderByDesc("create_time");
        return categoryMapper.selectList(wrapper);
    }

    public Page<Category> pageCategories(long pageNum, long pageSize, Integer status) {
        Page<Category> page = new Page<>(pageNum, pageSize);
        QueryWrapper<Category> wrapper = new QueryWrapper<>();
        if (status != null) {
            wrapper.eq("status", status);
        }
        wrapper.orderByAsc("sort_order").orderByDesc("create_time");
        return categoryMapper.selectPage(page, wrapper);
    }

    public Category findById(Long categoryId) {
        return categoryMapper.selectById(categoryId);
    }

    public Category findEnabledById(Long categoryId) {
        Category category = categoryMapper.selectById(categoryId);
        if (category == null || !Integer.valueOf(STATUS_ENABLED).equals(category.getStatus())) {
            return null;
        }
        return category;
    }

    public Category create(String name, String description, Integer sortOrder) {
        if (nameExists(name, null)) {
            throw new BusinessException("板块名称已存在");
        }
        Category category = new Category();
        category.setName(name);
        category.setDescription(description);
        category.setSortOrder(sortOrder == null ? 0 : sortOrder);
        category.setStatus(STATUS_ENABLED);
        categoryMapper.insert(category);
        return category;
    }

    @Transactional
    public CategoryUpdateResult update(Long categoryId, String name, String description, Integer sortOrder) {
        Category category = categoryMapper.lockById(categoryId);
        if (category == null) {
            return CategoryUpdateResult.NOT_FOUND;
        }
        if (nameExists(name, categoryId)) {
            return CategoryUpdateResult.NAME_EXISTS;
        }
        category.setName(name);
        category.setDescription(description);
        category.setSortOrder(sortOrder == null ? 0 : sortOrder);
        categoryMapper.updateById(category);
        return CategoryUpdateResult.SUCCESS;
    }

    @Transactional
    public CategoryUpdateResult enable(Long categoryId) {
        return updateStatus(categoryId, STATUS_ENABLED);
    }

    @Transactional
    public CategoryUpdateResult disable(Long categoryId) {
        return updateStatus(categoryId, STATUS_DISABLED);
    }

    @Transactional
    public CategoryDeleteResult delete(Long categoryId) {
        Category category = categoryMapper.lockById(categoryId);
        if (category == null) {
            return CategoryDeleteResult.NOT_FOUND;
        }
        Long articleCount = articleMapper.selectCount(
                new QueryWrapper<Article>().eq("category_id", categoryId));
        if (articleCount != null && articleCount > 0) {
            return CategoryDeleteResult.HAS_ARTICLE;
        }
        categoryMapper.deleteById(categoryId);
        return CategoryDeleteResult.SUCCESS;
    }

    private CategoryUpdateResult updateStatus(Long categoryId, Integer status) {
        Category category = categoryMapper.lockById(categoryId);
        if (category == null) {
            return CategoryUpdateResult.NOT_FOUND;
        }
        category.setStatus(status);
        categoryMapper.updateById(category);
        return CategoryUpdateResult.SUCCESS;
    }

    private boolean nameExists(String name, Long excludeId) {
        QueryWrapper<Category> wrapper = new QueryWrapper<>();
        wrapper.eq("name", name);
        if (excludeId != null) {
            wrapper.ne("id", excludeId);
        }
        return categoryMapper.selectCount(wrapper) > 0;
    }

    public enum CategoryUpdateResult {
        SUCCESS,
        NOT_FOUND,
        NAME_EXISTS
    }

    public enum CategoryDeleteResult {
        SUCCESS,
        NOT_FOUND,
        HAS_ARTICLE
    }
}

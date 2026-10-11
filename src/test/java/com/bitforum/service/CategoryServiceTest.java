package com.bitforum.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.bitforum.entity.Article;
import com.bitforum.entity.Category;
import com.bitforum.mapper.ArticleMapper;
import com.bitforum.service.CategoryService.CategoryDeleteResult;
import com.bitforum.service.CategoryService.CategoryUpdateResult;

@SpringBootTest
@Transactional
class CategoryServiceTest {
    @Autowired
    private CategoryService categoryService;

    @Autowired
    private ArticleMapper articleMapper;

    @Test
    void adminShouldCreateCategory() {
        String name = "测试板块-" + UUID.randomUUID();

        Category category = categoryService.create(name, "测试描述", 10);

        assertNotNull(category.getId());
        assertEquals(name, category.getName());
        assertEquals(CategoryService.STATUS_ENABLED, category.getStatus());
    }

    @Test
    void categoryNameShouldBeUnique() {
        String name = "唯一板块-" + UUID.randomUUID();
        categoryService.create(name, "第一次创建", 1);

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            categoryService.create(name, "重复创建", 2);
        });

        assertEquals("板块名称已存在", exception.getMessage());
    }

    @Test
    void disabledCategoryShouldNotBeAvailableForPublish() {
        Category category = categoryService.create("禁用板块-" + UUID.randomUUID(), "禁用测试", 1);

        CategoryUpdateResult result = categoryService.disable(category.getId());

        assertEquals(CategoryUpdateResult.SUCCESS, result);
        assertEquals(null, categoryService.findEnabledById(category.getId()));
    }

    @Test
    void categoryWithArticleShouldNotBeDeleted() {
        Category category = categoryService.create("有文章板块-" + UUID.randomUUID(), "删除限制测试", 1);

        Article article = new Article();
        article.setTitle("板块删除限制文章-" + UUID.randomUUID());
        article.setContent("用于验证有文章的板块不能删除");
        article.setUserId(1L);
        article.setCategoryId(category.getId());
        articleMapper.insert(article);

        CategoryDeleteResult result = categoryService.delete(category.getId());

        assertEquals(CategoryDeleteResult.HAS_ARTICLE, result);
    }
}

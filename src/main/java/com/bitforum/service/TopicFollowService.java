package com.bitforum.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bitforum.entity.*;
import com.bitforum.dto.*;
import com.bitforum.mapper.*;

@Service
public class TopicFollowService {
    private final CategoryMapper categories;
    private final CategoryFollowMapper follows;
    public TopicFollowService(CategoryMapper categories, CategoryFollowMapper follows) {
        this.categories = categories; this.follows = follows;
    }
    public static void validId(Long id) {
        if (id == null || id <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ID必须为正整数");
    }
    public static void validPage(long number, long size) {
        if (number < 1 || size < 1 || size > 50 || number - 1 > Long.MAX_VALUE / size) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分页参数无效，每页最多50条");
    }
    public static String validType(String type) {
        if (type != null && !ArticleService.TYPE_ARTICLE.equals(type) && !ArticleService.TYPE_QUESTION.equals(type))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "内容类型无效");
        return type;
    }
    public Category requireEnabled(Long id) {
        validId(id); return enabled(categories.selectById(id));
    }
    private Category enabled(Category category) {
        if (category == null || !Integer.valueOf(CategoryService.STATUS_ENABLED).equals(category.getStatus()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "话题不存在或不可访问");
        return category;
    }
    public TopicResponse detail(Long id, Long userId) {
        Category c = requireEnabled(id);
        boolean following = userId != null && follows.selectCount(new QueryWrapper<CategoryFollow>()
                .eq("user_id", userId).eq("category_id", id)) > 0;
        return new TopicResponse(c.getId(), c.getName(), c.getDescription(), c.getStatus(), following, null);
    }
    @Transactional
    public TopicFollowResponse follow(Long userId, Long id) {
        validId(id); enabled(categories.lockById(id));
        follows.follow(userId, id);
        return new TopicFollowResponse(id, true);
    }
    @Transactional
    public TopicFollowResponse unfollow(Long userId, Long id) {
        validId(id);
        if (categories.lockById(id) != null) follows.delete(new QueryWrapper<CategoryFollow>()
                .eq("user_id", userId).eq("category_id", id));
        return new TopicFollowResponse(id, false);
    }
    public Page<TopicResponse> following(Long userId, long number, long size) {
        validPage(number, size);
        return follows.pageFollowing(new Page<>(number, size), userId);
    }
}

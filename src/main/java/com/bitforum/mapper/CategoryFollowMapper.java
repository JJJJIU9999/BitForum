package com.bitforum.mapper;

import org.apache.ibatis.annotations.*;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bitforum.dto.TopicResponse;
import com.bitforum.entity.CategoryFollow;

@Mapper
public interface CategoryFollowMapper extends BaseMapper<CategoryFollow> {
    // 只幂等处理本表唯一关系，不使用 INSERT IGNORE 吞掉其他数据错误。
    @Insert("INSERT INTO category_follow(user_id, category_id) VALUES(#{userId}, #{categoryId}) "
            + "ON DUPLICATE KEY UPDATE id=id")
    int follow(@Param("userId") Long userId, @Param("categoryId") Long categoryId);

    @Select("""
            SELECT c.id AS categoryId, c.name, c.description, c.status,
                   true AS followedByCurrentUser, f.created_at AS followedAt
            FROM category_follow f JOIN category c ON c.id=f.category_id
            WHERE f.user_id=#{userId}
            ORDER BY f.created_at DESC, f.id DESC
            """)
    Page<TopicResponse> pageFollowing(Page<TopicResponse> page, @Param("userId") Long userId);
}

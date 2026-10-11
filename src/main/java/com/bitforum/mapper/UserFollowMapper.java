package com.bitforum.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bitforum.dto.FollowUserResponse;
import com.bitforum.entity.UserFollow;

@Mapper
public interface UserFollowMapper extends BaseMapper<UserFollow> {
    @Select("""
            SELECT u.id AS userId,
                   u.username AS username,
                   u.avatar AS avatar,
                   u.nickname AS nickname,
                   u.bio AS bio,
                   u.status AS status,
                   uf.created_at AS followedAt
            FROM user_follow uf
            INNER JOIN user_info u ON u.id = uf.follower_id
            WHERE uf.following_id = #{userId}
            ORDER BY uf.created_at DESC
            """)
    Page<FollowUserResponse> selectFollowers(Page<FollowUserResponse> page, @Param("userId") Long userId);

    @Select("""
            SELECT u.id AS userId,
                   u.username AS username,
                   u.avatar AS avatar,
                   u.nickname AS nickname,
                   u.bio AS bio,
                   u.status AS status,
                   uf.created_at AS followedAt
            FROM user_follow uf
            INNER JOIN user_info u ON u.id = uf.following_id
            WHERE uf.follower_id = #{userId}
            ORDER BY uf.created_at DESC
            """)
    Page<FollowUserResponse> selectFollowing(Page<FollowUserResponse> page, @Param("userId") Long userId);
}


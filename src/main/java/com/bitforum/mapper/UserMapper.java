package com.bitforum.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bitforum.entity.User;

@Mapper
public interface UserMapper extends BaseMapper<User> {
    @Update("UPDATE user_info SET pending_avatar_image_id = #{imageId} WHERE id = #{userId}")
    int setPendingAvatar(@Param("userId") Long userId, @Param("imageId") Long imageId);

    @Update("""
            UPDATE user_info
            SET avatar = CASE WHEN #{approved} THEN #{url} ELSE avatar END,
                pending_avatar_image_id = NULL
            WHERE id = #{userId} AND pending_avatar_image_id = #{imageId}
            """)
    int finishPendingAvatar(@Param("userId") Long userId, @Param("imageId") Long imageId,
            @Param("approved") boolean approved, @Param("url") String url);
}

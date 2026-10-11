package com.bitforum.mapper;

import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bitforum.dto.AnswerLikeStats;
import com.bitforum.entity.AnswerLike;

@Mapper
public interface AnswerLikeMapper extends BaseMapper<AnswerLike> {
    @Insert("INSERT INTO answer_like (comment_id,user_id) VALUES (#{commentId},#{userId}) "
            + "ON DUPLICATE KEY UPDATE comment_id=VALUES(comment_id)")
    int insertOnce(@Param("commentId") Long commentId, @Param("userId") Long userId);

    @Select("""
        <script>
        SELECT comment_id AS commentId, COUNT(*) AS likeCount,
               (SUM(CASE WHEN user_id = #{userId} THEN 1 ELSE 0 END) &gt; 0) AS likedByCurrentUser
        FROM answer_like WHERE comment_id IN
        <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
        GROUP BY comment_id
        </script>
        """)
    List<AnswerLikeStats> stats(@Param("ids") Collection<Long> ids, @Param("userId") Long userId);
}

package com.bitforum.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bitforum.entity.Article;

@Mapper
public interface ArticleMapper extends BaseMapper<Article>{
    // EXISTS preserves one row per article even when both following sources match.
    String FOLLOW_FEED_SQL = """
            <script>
            SELECT a.* FROM article a
            WHERE a.status='PUBLISHED'
              AND (EXISTS (SELECT 1 FROM user_follow uf WHERE uf.follower_id=#{userId} AND uf.following_id=a.user_id)
                   OR EXISTS (SELECT 1 FROM category_follow cf JOIN category c ON c.id=cf.category_id AND c.status=1
                              WHERE cf.user_id=#{userId} AND cf.category_id=a.category_id))
            <if test="contentType != null">AND a.content_type=#{contentType}</if>
            <if test="beforeTime != null">
              AND (COALESCE(a.create_time,'1000-01-01 00:00:00') &lt; #{beforeTime}
                   OR (COALESCE(a.create_time,'1000-01-01 00:00:00') = #{beforeTime} AND a.id &lt; #{beforeId}))
            </if>
            ORDER BY COALESCE(a.create_time,'1000-01-01 00:00:00') DESC, a.id DESC
            LIMIT #{limit}
            </script>
            """;
    @Select(FOLLOW_FEED_SQL)
    java.util.List<Article> selectFollowFeed(@Param("userId") Long userId, @Param("contentType") String contentType,
            @Param("beforeTime") java.time.LocalDateTime beforeTime, @Param("beforeId") Long beforeId,
            @Param("limit") long limit);

    @Select("SELECT * FROM article WHERE id = #{id} FOR UPDATE")
    Article lockById(@Param("id") Long id);

    @Update("UPDATE article SET accepted_answer_id = #{answerId} WHERE id = #{articleId}")
    int setAcceptedAnswer(@Param("articleId") Long articleId, @Param("answerId") Long answerId);

    @Update("UPDATE article SET accepted_answer_id = NULL WHERE id = #{articleId} AND accepted_answer_id = #{answerId}")
    int clearAcceptedAnswer(@Param("articleId") Long articleId, @Param("answerId") Long answerId);

    @Update("UPDATE article SET cover_image_id = #{imageId}, cover_url = #{url} WHERE id = #{articleId}")
    int updateCoverBinding(@Param("articleId") Long articleId, @Param("imageId") Long imageId,
            @Param("url") String url);

    @Update("UPDATE article SET cover_url = #{url} WHERE cover_image_id = #{imageId}")
    int publishApprovedCover(@Param("imageId") Long imageId, @Param("url") String url);

    @Select("""
            SELECT a.*
            FROM article_favorite af
            INNER JOIN article a ON a.id = af.article_id
            WHERE af.user_id = #{userId}
              AND a.status = #{status}
            ORDER BY af.create_time DESC
            """)
    Page<Article> selectFavoriteArticles(
            Page<Article> page,
            @Param("userId") Long userId,
            @Param("status") String status);
}

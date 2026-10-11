package com.bitforum.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bitforum.entity.Comment;

@Mapper
public interface CommentMapper extends BaseMapper<Comment>{
    @Select("SELECT * FROM comment WHERE id = #{id} FOR UPDATE")
    Comment lockById(@Param("id") Long id);
}

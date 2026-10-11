package com.bitforum.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bitforum.entity.Category;

@Mapper
public interface CategoryMapper extends BaseMapper<Category> {
    @org.apache.ibatis.annotations.Select("SELECT * FROM category WHERE id=#{id} FOR UPDATE")
    Category lockById(@org.apache.ibatis.annotations.Param("id") Long id);
}

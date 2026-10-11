package com.bitforum.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bitforum.entity.ArticleFavorite;

@Mapper
public interface ArticleFavoriteMapper extends BaseMapper<ArticleFavorite> {
}


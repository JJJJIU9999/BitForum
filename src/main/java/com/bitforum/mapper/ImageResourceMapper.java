package com.bitforum.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bitforum.entity.ImageResource;

@Mapper
public interface ImageResourceMapper extends BaseMapper<ImageResource> {
    @Select("SELECT * FROM image_resource WHERE id = #{id} FOR UPDATE")
    ImageResource selectForUpdate(@Param("id") Long id);
}

package com.bitforum.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bitforum.entity.TextReviewRecord;

@Mapper
public interface TextReviewRecordMapper extends BaseMapper<TextReviewRecord> {
    @Select("SELECT * FROM text_review_record WHERE id = #{id} FOR UPDATE")
    TextReviewRecord selectForUpdate(Long id);
}

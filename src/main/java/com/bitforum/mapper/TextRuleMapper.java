package com.bitforum.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bitforum.entity.TextRule;

@Mapper
public interface TextRuleMapper extends BaseMapper<TextRule> {
    @Select("SELECT version FROM text_rule_revision WHERE id = 1")
    Long currentVersion();

    @Update("UPDATE text_rule_revision SET version = version + 1 WHERE id = 1")
    int incrementVersion();
}

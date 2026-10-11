package com.bitforum.ai.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bitforum.ai.entity.AiMessage;

@Mapper
public interface AiMessageMapper extends BaseMapper<AiMessage> {
}

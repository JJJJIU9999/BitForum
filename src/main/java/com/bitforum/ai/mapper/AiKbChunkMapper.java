package com.bitforum.ai.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bitforum.ai.entity.AiKbChunk;

/**
 * 知识库分块 Mapper（M15）。
 *
 * 所在包 com.bitforum.ai.mapper 已在 BitForumSpringApplication 的 @MapperScan 列表中。
 */
@Mapper
public interface AiKbChunkMapper extends BaseMapper<AiKbChunk> {
}

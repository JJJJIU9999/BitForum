package com.bitforum.ai.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bitforum.ai.entity.AiModerationRecord;

/**
 * AI 审核记录 Mapper（M16）。
 *
 * 所在包 com.bitforum.ai.mapper 已在 BitForumSpringApplication 的 @MapperScan 列表中。
 */
@Mapper
public interface AiModerationRecordMapper extends BaseMapper<AiModerationRecord> {
}

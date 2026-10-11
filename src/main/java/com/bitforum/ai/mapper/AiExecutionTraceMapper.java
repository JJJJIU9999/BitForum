package com.bitforum.ai.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bitforum.ai.entity.AiExecutionTrace;

/**
 * AI 执行轨迹 Mapper（M18）。
 *
 * 所在包 com.bitforum.ai.mapper 已在 BitForumSpringApplication 的 @MapperScan 列表中，
 * 因此新的轨迹 Mapper 不需要再改启动类（M13 起 AI 域的既有约定）。
 */
@Mapper
public interface AiExecutionTraceMapper extends BaseMapper<AiExecutionTrace> {
}

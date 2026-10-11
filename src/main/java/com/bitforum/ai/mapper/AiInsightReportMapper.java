package com.bitforum.ai.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bitforum.ai.entity.AiInsightReport;

/**
 * AI 运营洞察报告 Mapper（M17）。
 *
 * 所在包 com.bitforum.ai.mapper 已在 BitForumSpringApplication 的 @MapperScan 列表中。
 */
@Mapper
public interface AiInsightReportMapper extends BaseMapper<AiInsightReport> {
}

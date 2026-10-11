package com.bitforum.ai.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bitforum.ai.entity.AiKbDocument;

/**
 * 知识库文档 Mapper（M15）。
 *
 * 所在包 com.bitforum.ai.mapper 已在 BitForumSpringApplication 的 @MapperScan 列表中，
 * 无需再改扫描配置。
 */
@Mapper
public interface AiKbDocumentMapper extends BaseMapper<AiKbDocument> {
}

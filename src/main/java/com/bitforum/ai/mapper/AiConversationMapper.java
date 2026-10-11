package com.bitforum.ai.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bitforum.ai.entity.AiConversation;

@Mapper
public interface AiConversationMapper extends BaseMapper<AiConversation> {

    /**
     * 原子累加会话统计（S5）。
     *
     * <p>把加法下推到 SQL，避免"读出 → Java 相加 → updateById"在并发发送消息时丢失增量
     * （两个请求读到同一个旧值，后写的那次覆盖先写的那次）。
     *
     * <p>`update_time` 由表的 {@code ON UPDATE CURRENT_TIMESTAMP} 维护，这里不重复设置。
     *
     * @param conversationId 会话 id
     * @param addedMessages  本次新增消息数
     * @param addedTokens    本次新增 token 数
     * @return 受影响行数；会话不存在时为 0
     */
    @Update("UPDATE ai_conversation "
            + "SET message_count = message_count + #{addedMessages}, "
            + "    total_tokens = total_tokens + #{addedTokens} "
            + "WHERE id = #{conversationId}")
    int increaseStats(@Param("conversationId") Long conversationId,
                      @Param("addedMessages") int addedMessages,
                      @Param("addedTokens") int addedTokens);
}

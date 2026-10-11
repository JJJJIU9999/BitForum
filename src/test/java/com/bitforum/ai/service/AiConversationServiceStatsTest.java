package com.bitforum.ai.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bitforum.ai.entity.AiConversation;
import com.bitforum.ai.mapper.AiConversationMapper;
import com.bitforum.ai.mapper.AiKbDocumentMapper;
import com.bitforum.ai.mapper.AiMessageMapper;

/**
 * S5：会话统计必须走数据库原子自增的单元测试。
 *
 * <p>这是"实现方式"的守卫：只要有人把 {@code updateConversationStats} 改回
 * "selectById → Java 相加 → updateById"的读-改-写，本用例立刻失败。
 * 增量结果本身的正确性由 {@code AiConversationStatsIntegrationTest} 用真实 SQL 覆盖。
 */
@ExtendWith(MockitoExtension.class)
class AiConversationServiceStatsTest {

    @Mock
    private AiConversationMapper conversationMapper;
    @Mock
    private AiMessageMapper messageMapper;
    @Mock
    private AiKbDocumentMapper kbDocumentMapper;

    @InjectMocks
    private AiConversationService service;

    @Test
    void shouldUseAtomicIncrementInsteadOfReadModifyWrite() {
        service.updateConversationStats(42L, 2, 150);

        verify(conversationMapper).increaseStats(42L, 2, 150);
        verify(conversationMapper, never()).selectById(anyLong());
        verify(conversationMapper, never()).updateById(any(AiConversation.class));
        verify(conversationMapper, never()).update(any(), any());
    }

    /** 会话不存在时由 SQL 影响 0 行处理，不应抛异常、也不应退回读-改-写。 */
    @Test
    void missingConversationShouldNotFallBackToReadModifyWrite() {
        service.updateConversationStats(null, 1, 10);

        verify(conversationMapper).increaseStats(null, 1, 10);
        verify(conversationMapper, never()).selectById(anyLong());
        verify(conversationMapper, never()).updateById(any(AiConversation.class));
    }

    @Test
    void zeroDeltaShouldStillUseAtomicPath() {
        service.updateConversationStats(42L, 0, 0);

        verify(conversationMapper).increaseStats(anyLong(), anyInt(), anyInt());
        verify(conversationMapper, never()).selectById(anyLong());
    }
}

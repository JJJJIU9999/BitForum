package com.bitforum.ai.orchestrator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import com.bitforum.ai.agent.*;
import com.bitforum.ai.entity.*;
import com.bitforum.ai.mapper.*;
import com.bitforum.ai.memory.MysqlChatMemoryRepository;
import com.bitforum.ai.service.AiConversationService;
import com.bitforum.ai.trace.TraceRecorder;
import com.bitforum.ai.usage.AiTokenBudgetGuard;

class ChatSafetyTest {
    @Test
    void ownershipCheckPreventsReadingOrPostingOtherUsersConversation() {
        var conversations = mock(AiConversationMapper.class);
        var messages = mock(AiMessageMapper.class);
        var kb = mock(AiKbDocumentMapper.class);
        var row = new AiConversation(); row.setUserId(7L); row.setId(11L);
        when(conversations.selectById(11L)).thenReturn(row);
        var service = new AiConversationService(conversations, messages, kb);
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> service.listMessages(11L, 8L)).getStatusCode().value());
        var agent = mock(Agent.class); when(agent.type()).thenReturn(AgentType.QA);
        var memory = mock(MysqlChatMemoryRepository.class);
        var orchestrator = new AgentOrchestrator(List.of(agent), service, memory,
                mock(TraceRecorder.class), mock(AiTokenBudgetGuard.class));
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> orchestrator.chat(11L, 8L, "越权提问")).getStatusCode().value());
        verifyNoInteractions(messages, memory, kb);
        verify(agent, never()).execute(any(), any());
    }

    @Test
    void failedBudgetReadPersistsAnAnswerWithoutCallingAgent() {
        var service = mock(AiConversationService.class);
        var row = new AiConversation(); row.setId(11L); row.setUserId(7L);
        row.setAgentType("QA"); row.setMessageCount(2);
        when(service.getOwnedConversation(11L, 7L)).thenReturn(row);
        var saved = new AiMessage(); saved.setId(2L); saved.setRole("assistant");
        when(service.saveMessage(any(), any(), any(), any(), any(), any(), any(), any())).thenAnswer(i -> {
            saved.setContent(i.getArgument(2)); return saved;
        });
        var mapper = mock(AiUsageStatMapper.class);
        when(mapper.selectUserDaily(any(), any())).thenThrow(new IllegalStateException("offline"));
        var budget = new AiTokenBudgetGuard(mapper, true, 200000, new BigDecimal("2"), 2000, 1024);
        var memory = mock(MysqlChatMemoryRepository.class);
        when(memory.findByConversationId("11")).thenReturn(List.of());
        var agent = mock(Agent.class); when(agent.type()).thenReturn(AgentType.QA);
        var orchestrator = new AgentOrchestrator(List.of(agent), service, memory, mock(TraceRecorder.class), budget);
        var response = orchestrator.chat(11L, 7L, "问题");
        assertTrue(response.getContent().contains("本次未调用模型"));
        verify(agent, never()).execute(any(), any());
    }
}

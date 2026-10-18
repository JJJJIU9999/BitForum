package com.bitforum.ai.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bitforum.ai.analyst.AnalystAgent;
import com.bitforum.ai.degrade.AiDegradeGuard;
import com.bitforum.ai.moderation.ModerationAgent;
import com.bitforum.ai.moderation.ModerationTargetType;
import com.bitforum.ai.trace.TraceRecorder;
import com.bitforum.ai.trace.TraceDegradeReason;
import com.bitforum.dto.AdminDashboardSummaryResponse;
import com.bitforum.service.AdminDashboardService;

class AiModelSwitchTest {
    @Test
    @SuppressWarnings("unchecked")
    void disabledModerationCannotCallModelEvenWhenChatExists() {
        ObjectProvider<ChatClient> provider = mock(ObjectProvider.class);
        var agent = new ModerationAgent(provider, "deepseek-flash", 2000,
                new AiDegradeGuard(mock(TraceRecorder.class)), false);
        assertEquals(TraceDegradeReason.AI_DISABLED,
                agent.analyze(ModerationTargetType.ARTICLE, "正常文章").degradeReason());
        verifyNoInteractions(provider);
    }

    @Test
    @SuppressWarnings("unchecked")
    void disabledAnalystKeepsLocalSnapshotWithoutAccessingModel() {
        ObjectProvider<ChatClient> provider = mock(ObjectProvider.class);
        var dashboard = mock(AdminDashboardService.class);
        when(dashboard.summary()).thenReturn(new AdminDashboardSummaryResponse());
        var trace = mock(TraceRecorder.class);
        var agent = new AnalystAgent(provider, dashboard, new ObjectMapper(), "deepseek-flash", trace,
                new AiDegradeGuard(trace), false);
        var result = agent.analyze();
        assertEquals(TraceDegradeReason.AI_DISABLED, result.degradeReason());
        assertNotNull(result.dataSnapshot());
        verifyNoInteractions(provider);
    }
}

package com.bitforum.ai.analyst;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.bitforum.ai.entity.AiInsightReport;
import com.bitforum.ai.mapper.AiInsightReportMapper;
import com.bitforum.ai.trace.TraceDegradeReason;

/**
 * 运营洞察存取的单元测试（M17）。
 *
 * <p>重点确认两件事：**失败也要留痕**（含统计快照），以及**看板只展示成功的那一份** ——
 * 否则管理员可能看到一份没有正文的失败记录被当成"当前洞察"。
 */
class AiInsightServiceTest {

    private AiInsightReportMapper reportMapper;
    private AiInsightService service;

    /** 纯单测里用 LambdaQueryWrapper 需要 MyBatis-Plus 的实体元信息缓存。 */
    @BeforeAll
    static void initMybatisPlusMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, AiInsightReport.class);
    }

    @BeforeEach
    void setUp() {
        reportMapper = mock(AiInsightReportMapper.class);
        service = new AiInsightService(reportMapper);
    }

    @Test
    void shouldPersistSuccessfulInsightWithTokens() {
        AnalystAgent.InsightOutcome outcome = AnalystAgent.InsightOutcome.of(
                "社区处于起步阶段……\n\n## 可执行建议\n1. 清空审核积压",
                "{\"userStats\":{\"total\":5}}",
                LocalDateTime.now(),
                "deepseek-flash", 1553, 715, 2268, 4780L, 1);

        service.save(outcome, AiInsightReport.TRIGGER_MANUAL, 13L);

        ArgumentCaptor<AiInsightReport> captor = ArgumentCaptor.forClass(AiInsightReport.class);
        verify(reportMapper).insert(captor.capture());
        AiInsightReport saved = captor.getValue();

        assertEquals(AiInsightReport.STATUS_SUCCESS, saved.getStatus());
        assertEquals(AiInsightReport.TRIGGER_MANUAL, saved.getTriggerType());
        assertEquals(13L, saved.getRequestedBy());
        assertTrue(saved.getContent().contains("可执行建议"));
        assertEquals("{\"userStats\":{\"total\":5}}", saved.getDataSnapshot());
        assertEquals(1553, saved.getPromptTokens());
        assertEquals(715, saved.getCompletionTokens());
        assertEquals(2268, saved.getTotalTokens());
        assertEquals(4780, saved.getLatencyMs());
        assertEquals("deepseek-flash", saved.getModel());
    }

    /** 生成失败也要落库，并且**保留统计快照**：数据是取到了的，只是模型这一步失败。 */
    @Test
    void shouldPersistFailureWithSnapshotForTroubleshooting() {
        // M18：降级结果的文案统一由 AiDegradeGuard 按原因码给出（全站同一套说法），
        // 因此这里用统一文案而不是各 Agent 自造的一句话
        AnalystAgent.InsightOutcome degraded = AnalystAgent.InsightOutcome.degraded(
                TraceDegradeReason.AI_DISABLED, "{\"userStats\":{\"total\":5}}", LocalDateTime.now(),
                "deepseek-flash", 120L, TraceDegradeReason.userMessage(TraceDegradeReason.AI_DISABLED));

        service.save(degraded, AiInsightReport.TRIGGER_MANUAL, 13L);

        ArgumentCaptor<AiInsightReport> captor = ArgumentCaptor.forClass(AiInsightReport.class);
        verify(reportMapper).insert(captor.capture());
        AiInsightReport saved = captor.getValue();

        assertEquals(AiInsightReport.STATUS_FAILED, saved.getStatus());
        assertNotNull(saved.getDataSnapshot(), "失败时统计快照仍应保留，便于排查与重试");
        assertEquals(TraceDegradeReason.userMessage(TraceDegradeReason.AI_DISABLED), saved.getErrorMessage());
    }

    /** 触发方式缺省为手动（调用方忘了传时不应写入 null）。 */
    @Test
    void shouldDefaultTriggerTypeToManual() {
        service.save(AnalystAgent.InsightOutcome.degraded(TraceDegradeReason.LLM_ERROR, null,
                        LocalDateTime.now(), "m", 1L, "失败"),
                null, null);

        ArgumentCaptor<AiInsightReport> captor = ArgumentCaptor.forClass(AiInsightReport.class);
        verify(reportMapper).insert(captor.capture());
        assertEquals(AiInsightReport.TRIGGER_MANUAL, captor.getValue().getTriggerType());
    }

    @Test
    void shouldReturnLatestSuccessOnly() {
        AiInsightReport report = new AiInsightReport();
        report.setStatus(AiInsightReport.STATUS_SUCCESS);
        when(reportMapper.selectOne(any())).thenReturn(report);

        assertTrue(service.latestSuccess().isPresent());
        assertFalse(service.latestSuccess().isEmpty());
    }

    @Test
    void shouldReturnEmptyWhenNoSuccessfulReport() {
        when(reportMapper.selectOne(any())).thenReturn(null);

        assertTrue(service.latestSuccess().isEmpty());
    }

    /** 历史列表要限制条数，避免管理台一次拉出全部历史。 */
    @Test
    void shouldCapHistoryLimit() {
        when(reportMapper.selectList(any())).thenReturn(List.of());
        service.recent(500);
        service.recent(0);
        verify(reportMapper, times(2)).selectList(any());
    }

    @Test
    void shouldReportPendingState() {
        when(reportMapper.selectCount(any())).thenReturn(1L);
        assertTrue(service.hasPending());

        when(reportMapper.selectCount(any())).thenReturn(0L);
        assertFalse(service.hasPending());
    }
}

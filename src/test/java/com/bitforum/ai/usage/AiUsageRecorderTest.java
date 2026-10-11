package com.bitforum.ai.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.bitforum.ai.entity.AiUsageStat;
import com.bitforum.ai.mapper.AiUsageStatMapper;

/**
 * 用量记录器单元测试（M18）。
 *
 * <p>只测确定性逻辑与"副产品原则"：成本按单价算、token 缺失时的兜底、
 * 以及**写库失败绝不外抛**（用量记录失败不能影响 AI 调用本身的返回）。
 */
class AiUsageRecorderTest {

    private AiUsageStatMapper usageMapper;
    private AiUsageRecorder recorder;

    @BeforeEach
    void setUp() {
        usageMapper = mock(AiUsageStatMapper.class);
        recorder = new AiUsageRecorder(usageMapper, new BigDecimal("2.0"), new BigDecimal("8.0"));
    }

    /** 成本 = 输入 token×输入单价 + 输出 token×输出单价（每百万）。 */
    @Test
    void shouldEstimateCostFromSeparatePrices() {
        // 1000 输入 × 2 元/百万 = 0.002 元；500 输出 × 8 元/百万 = 0.004 元
        assertEquals(new BigDecimal("0.006000"), recorder.estimateCost(1000, 500));
        assertEquals(new BigDecimal("0.000000"), recorder.estimateCost(null, null));
        // 只有输入也应能算
        assertEquals(new BigDecimal("0.002000"), recorder.estimateCost(1000, null));
    }

    @Test
    void shouldPersistUsageWithTodayDateAndFallbackTotal() {
        recorder.record("trace-1", "CHAT", "QA", 42L, "deepseek-chat", 900, 100, null, 1500,
                AiUsageStat.RESULT_SUCCESS);

        ArgumentCaptor<AiUsageStat> captor = ArgumentCaptor.forClass(AiUsageStat.class);
        verify(usageMapper).insert(captor.capture());
        AiUsageStat stat = captor.getValue();

        assertEquals("trace-1", stat.getTraceId());
        assertEquals("CHAT", stat.getScene());
        assertEquals("QA", stat.getAgentType());
        assertEquals(42L, stat.getUserId());
        assertEquals(1000, stat.getTotalTokens(), "total 缺失时应退化为输入 + 输出");
        assertEquals(AiUsageStat.RESULT_SUCCESS, stat.getResult());
        assertEquals(LocalDate.now(), stat.getStatDate(), "按天聚合依赖 stat_date，必须写入当天");
        assertEquals(new BigDecimal("0.002600"), stat.getEstimatedCost(),
                "900×2/百万 + 100×8/百万 = 0.0018 + 0.0008");
    }

    @Test
    void shouldNotThrowWhenPersistFails() {
        when(usageMapper.insert(any(AiUsageStat.class))).thenThrow(new IllegalStateException("数据库不可用"));

        assertDoesNotThrow(() -> recorder.record("trace-2", "INSIGHT", "ANALYST", null, "deepseek-flash",
                10, 5, 15, 100, AiUsageStat.RESULT_DEGRADED));
    }
}

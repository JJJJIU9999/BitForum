package com.bitforum.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.bitforum.ai.analyst.AiInsightGenerationService;
import com.bitforum.ai.analyst.AiInsightService;
import com.bitforum.ai.entity.AiInsightReport;
import com.bitforum.interceptor.AdminInterceptor;

/**
 * 管理员「AI 运营洞察」接口测试（M17）。
 *
 * <p>权限由 {@code AdminInterceptor} 负责（{@code /api/admin/**}），本测试把它替换为放行，
 * 专注验证接口自身的行为：受理、拒绝重复触发、只展示成功报告、状态查询。
 * 管理员身份通过 {@code requestAttr("userId")} 直接注入 —— 真实链路里由登录拦截器设置。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminAiInsightControllerTest {

    private static final String ADMIN_ID_ATTR = "userId";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AiInsightGenerationService generationService;

    @MockitoBean
    private AiInsightService insightService;

    @MockitoBean
    private AdminInterceptor adminInterceptor;

    @Test
    void shouldAcceptGenerationRequestAndReturnPendingRecord() throws Exception {
        when(adminInterceptor.preHandle(any(), any(), any())).thenReturn(true);
        AiInsightReport pending = report(7L, AiInsightReport.STATUS_PENDING, null);
        when(generationService.trigger(13L)).thenReturn(Optional.of(pending));

        mockMvc.perform(post("/api/admin/ai/insight/generate").requestAttr(ADMIN_ID_ATTR, 13L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(7))
                .andExpect(jsonPath("$.data.status").value(AiInsightReport.STATUS_PENDING));
    }

    /** 连点保护：已有生成中任务时返回失败提示，而不是再排队一次烧额度。 */
    @Test
    void shouldRejectWhenGenerationAlreadyRunning() throws Exception {
        when(adminInterceptor.preHandle(any(), any(), any())).thenReturn(true);
        when(generationService.trigger(13L)).thenReturn(Optional.empty());

        // Result.fail 在项目里统一映射为 400（见 ResultHttpStatusAdvice）
        mockMvc.perform(post("/api/admin/ai/insight/generate").requestAttr(ADMIN_ID_ATTR, 13L))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.message").value("已有一份运营洞察正在生成，请稍候"));
    }

    @Test
    void shouldReturnLatestSuccessfulReport() throws Exception {
        when(adminInterceptor.preHandle(any(), any(), any())).thenReturn(true);
        when(insightService.latestSuccess()).thenReturn(
                Optional.of(report(3L, AiInsightReport.STATUS_SUCCESS, "社区处于起步阶段……")));

        mockMvc.perform(get("/api/admin/ai/insight/latest").requestAttr(ADMIN_ID_ATTR, 13L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(3))
                .andExpect(jsonPath("$.data.content").value("社区处于起步阶段……"));
    }

    /** 从没生成过时也要是 200 + 空 data，前端才能显示"暂无报告，可点击生成"。 */
    @Test
    void shouldReturnNullDataWhenNoReportYet() throws Exception {
        when(adminInterceptor.preHandle(any(), any(), any())).thenReturn(true);
        when(insightService.latestSuccess()).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/admin/ai/insight/latest").requestAttr(ADMIN_ID_ATTR, 13L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void shouldExposeRunningStatusForPolling() throws Exception {
        when(adminInterceptor.preHandle(any(), any(), any())).thenReturn(true);
        when(insightService.pending()).thenReturn(
                Optional.of(report(7L, AiInsightReport.STATUS_PENDING, null)));

        mockMvc.perform(get("/api/admin/ai/insight/status").requestAttr(ADMIN_ID_ATTR, 13L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(AiInsightReport.STATUS_PENDING));
    }

    @Test
    void shouldListHistory() throws Exception {
        when(adminInterceptor.preHandle(any(), any(), any())).thenReturn(true);
        when(insightService.recent(10)).thenReturn(List.of(
                report(3L, AiInsightReport.STATUS_SUCCESS, "正文"),
                report(2L, AiInsightReport.STATUS_FAILED, null)));

        mockMvc.perform(get("/api/admin/ai/insight/history").requestAttr(ADMIN_ID_ATTR, 13L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[1].status").value(AiInsightReport.STATUS_FAILED));
    }

    private AiInsightReport report(Long id, String status, String content) {
        AiInsightReport report = new AiInsightReport();
        report.setId(id);
        report.setStatus(status);
        report.setContent(content);
        report.setTriggerType(AiInsightReport.TRIGGER_MANUAL);
        report.setRequestedBy(13L);
        report.setDataTime(LocalDateTime.now());
        return report;
    }
}

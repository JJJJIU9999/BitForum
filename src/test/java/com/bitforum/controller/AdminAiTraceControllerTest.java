package com.bitforum.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.bitforum.ai.entity.AiExecutionTrace;
import com.bitforum.ai.mapper.AiExecutionTraceMapper;
import com.bitforum.entity.User;
import com.bitforum.mapper.UserMapper;
import com.bitforum.service.UserService;
import com.bitforum.util.JwtUtil;

/**
 * 管理员 AI 执行轨迹接口测试（M18）。
 *
 * <p>覆盖接口层职责：路径与鉴权、按场景/状态过滤、详情里的步骤 JSON 是否被解析成数组、
 * 以及查不到时的 404。轨迹的**写入**由 {@code TraceRecorderIntegrationTest} 与
 * 各链路的集成测试覆盖。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminAiTraceControllerTest {

    private static final String ADMIN_TOKEN = "trace-admin-token";
    private static final String USER_TOKEN = "trace-user-token";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private AiExecutionTraceMapper traceMapper;

    @MockitoBean
    private JwtUtil jwtUtil;

    private String traceId;

    @BeforeEach
    void prepare() {
        Long adminId = createUser("trace-test-admin", UserService.ROLE_ADMIN);
        Long userId = createUser("trace-test-user", "USER");
        when(jwtUtil.getUserId(ADMIN_TOKEN)).thenReturn(adminId);
        when(jwtUtil.getUserId(USER_TOKEN)).thenReturn(userId);
        traceId = insertTrace();
    }

    @Test
    void shouldRejectAnonymousRequest() throws Exception {
        mockMvc.perform(get("/api/admin/ai/traces")).andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectNonAdminUser() throws Exception {
        mockMvc.perform(get("/api/admin/ai/traces").header("Authorization", "Bearer " + USER_TOKEN))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldReturnPageForAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/ai/traces").header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.records[0].traceId").value(traceId))
                // 列表页不返回步骤明细：轨迹表会随使用量增长，列表每行都带 JSON 会让响应迅速膨胀
                .andExpect(jsonPath("$.data.records[0].steps").doesNotExist());
    }

    @Test
    void shouldFilterBySceneAndStatus() throws Exception {
        mockMvc.perform(get("/api/admin/ai/traces")
                        .param("scene", AiExecutionTrace.SCENE_CHAT)
                        .param("status", AiExecutionTrace.STATUS_DEGRADED)
                        .header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.records[0].degradeReason").value("LLM_TIMEOUT"));

        mockMvc.perform(get("/api/admin/ai/traces")
                        .param("scene", AiExecutionTrace.SCENE_INSIGHT)
                        .header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.length()").value(0));
    }

    @Test
    void shouldReturnStepsInDetail() throws Exception {
        mockMvc.perform(get("/api/admin/ai/traces/" + traceId)
                        .header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.steps.length()").value(2))
                .andExpect(jsonPath("$.data.steps[0].type").value("ROUTE"))
                .andExpect(jsonPath("$.data.steps[0].name").value("QA"))
                .andExpect(jsonPath("$.data.steps[1].type").value("LLM_CALL"))
                .andExpect(jsonPath("$.data.steps[1].totalTokens").value(1020));
    }

    @Test
    void shouldReturnNotFoundForUnknownTrace() throws Exception {
        mockMvc.perform(get("/api/admin/ai/traces/not-exist-trace")
                        .header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isNotFound());
    }

    // ==================== 辅助方法 ====================

    private String insertTrace() {
        String id = UUID.randomUUID().toString().replace("-", "");
        AiExecutionTrace trace = new AiExecutionTrace();
        trace.setTraceId(id);
        trace.setScene(AiExecutionTrace.SCENE_CHAT);
        trace.setAgentType("QA");
        trace.setUserId(88001L);
        trace.setConversationId(1L);
        trace.setStatus(AiExecutionTrace.STATUS_DEGRADED);
        trace.setRoute("按会话 agent_type 路由到「问答助手」");
        trace.setModel("deepseek-chat");
        trace.setPromptTokens(900);
        trace.setCompletionTokens(120);
        trace.setTotalTokens(1020);
        trace.setLatencyMs(1800);
        trace.setStepCount(2);
        trace.setSteps("""
                [{"seq":1,"type":"ROUTE","name":"QA","detail":"路由到问答助手","latencyMs":1,"totalTokens":null},
                 {"seq":2,"type":"LLM_CALL","name":"deepseek-chat","detail":"prompt=900","latencyMs":1500,"totalTokens":1020}]""");
        trace.setDegradeReason("LLM_TIMEOUT");
        trace.setMessage("AI 响应超时，已使用降级结果，请稍后重试。");
        trace.setCreateTime(LocalDateTime.now());
        trace.setUpdateTime(LocalDateTime.now());
        traceMapper.insert(trace);
        return id;
    }

    private Long createUser(String username, String role) {
        User user = new User();
        user.setUsername(username);
        user.setPassword("$2a$10$trace-controller-test-placeholder");
        user.setNickname(username);
        user.setRole(role);
        user.setStatus(UserService.STATUS_ENABLED);
        userMapper.insert(user);
        return user.getId();
    }
}

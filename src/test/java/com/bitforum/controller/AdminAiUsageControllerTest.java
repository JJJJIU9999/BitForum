package com.bitforum.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.bitforum.ai.dto.AiUsageOverviewResponse;
import com.bitforum.ai.entity.AiUsageStat;
import com.bitforum.ai.mapper.AiUsageStatMapper;
import com.bitforum.ai.usage.AiUsageQueryService;
import com.bitforum.entity.User;
import com.bitforum.mapper.UserMapper;
import com.bitforum.service.UserService;
import com.bitforum.util.JwtUtil;

/**
 * 管理员 AI 用量概览接口测试（M18）。
 *
 * <p>覆盖接口层与聚合 SQL：鉴权、按 Agent/按天的聚合结果、计价口径是否随响应返回。
 * 聚合走的是真实 MySQL（注解 SQL），因此这里也顺带验证了列别名与 DTO 的映射是否正确 ——
 * 这类错误在纯 mock 测试里看不出来。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminAiUsageControllerTest {

    private static final String ADMIN_TOKEN = "usage-admin-token";
    private static final String USER_TOKEN = "usage-user-token";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private AiUsageStatMapper usageMapper;
    @Autowired
    private AiUsageQueryService usageQueryService;

    @MockitoBean
    private JwtUtil jwtUtil;

    @BeforeEach
    void prepare() {
        Long adminId = createUser("usage-test-admin", UserService.ROLE_ADMIN);
        Long userId = createUser("usage-test-user", "USER");
        when(jwtUtil.getUserId(ADMIN_TOKEN)).thenReturn(adminId);
        when(jwtUtil.getUserId(USER_TOKEN)).thenReturn(userId);

        insertUsage("QA", 88011L, 1000, 200, "0.003600");
        insertUsage("MODERATION", 88012L, 500, 100, "0.001800");
    }

    @Test
    void shouldRejectAnonymousRequest() throws Exception {
        mockMvc.perform(get("/api/admin/ai/usage/overview")).andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectNonAdminUser() throws Exception {
        mockMvc.perform(get("/api/admin/ai/usage/overview")
                        .header("Authorization", "Bearer " + USER_TOKEN))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldAggregateUsageForAdmin() throws Exception {
        // 接口层：结构、计价口径、鉴权通过后的正常返回
        mockMvc.perform(get("/api/admin/ai/usage/overview")
                        .param("days", "7")
                        .header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.days").value(7))
                .andExpect(jsonPath("$.data.totals.calls").isNumber())
                .andExpect(jsonPath("$.data.byAgent").isArray())
                .andExpect(jsonPath("$.data.daily").isArray())
                .andExpect(jsonPath("$.data.topUsers").isArray())
                // 计价口径随响应返回：页面上的成本是估算值，单价必须可见
                .andExpect(jsonPath("$.data.inputPricePerMillion").exists())
                .andExpect(jsonPath("$.data.outputPricePerMillion").exists())
                .andExpect(jsonPath("$.data.priceNote").isNotEmpty())
                // M18 收尾：预算口径也要可见，管理端才能解释"某个用户为什么被限流"
                .andExpect(jsonPath("$.data.budget.enabled").value(true))
                .andExpect(jsonPath("$.data.budget.dailyTokenLimit").exists())
                .andExpect(jsonPath("$.data.budget.maxInputChars").exists());

        // 聚合内容：不对"库里有几行"做强断言（同一天可能有其它测试/演示数据），
        // 改为断言本次插入的两行确实被聚合进来了
        AiUsageOverviewResponse overview = usageQueryService.overview(7);
        assertTrue(overview.getTotals().getCalls() >= 2, "两次调用应被统计：" + overview.getTotals().getCalls());
        assertTrue(overview.getTotals().getPromptTokens() >= 1500);
        assertTrue(overview.getByAgent().stream()
                        .anyMatch(item -> "QA".equals(item.getAgentType())),
                "按 Agent 聚合里应出现本次插入的 QA：" + overview.getByAgent());
        assertTrue(overview.getByAgent().stream()
                        .anyMatch(item -> "MODERATION".equals(item.getAgentType())),
                "按 Agent 聚合里应出现本次插入的 MODERATION（这正是 M18 补上的缺口）");
        assertTrue(overview.getDaily().stream()
                        .anyMatch(item -> LocalDate.now().equals(item.getStatDate())),
                "按天聚合里应包含今天");
    }

    @Test
    void shouldClampDaysIntoAllowedRange() {
        assertEquals(1, usageQueryService.overview(0).getDays());
        assertEquals(90, usageQueryService.overview(9999).getDays());
    }

    // ==================== 辅助方法 ====================

    private void insertUsage(String agentType, Long userId, int prompt, int completion, String cost) {
        AiUsageStat stat = new AiUsageStat();
        stat.setTraceId("usage-test-" + agentType);
        stat.setScene("CHAT");
        stat.setAgentType(agentType);
        stat.setUserId(userId);
        stat.setModel("deepseek-chat");
        stat.setPromptTokens(prompt);
        stat.setCompletionTokens(completion);
        stat.setTotalTokens(prompt + completion);
        stat.setLatencyMs(1200);
        stat.setResult(AiUsageStat.RESULT_SUCCESS);
        stat.setEstimatedCost(new BigDecimal(cost));
        stat.setStatDate(LocalDate.now());
        usageMapper.insert(stat);
    }

    private Long createUser(String username, String role) {
        User user = new User();
        user.setUsername(username);
        user.setPassword("$2a$10$usage-controller-test-placeholder");
        user.setNickname(username);
        user.setRole(role);
        user.setStatus(UserService.STATUS_ENABLED);
        userMapper.insert(user);
        return user.getId();
    }
}

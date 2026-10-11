package com.bitforum.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.bitforum.ai.entity.AiModerationRecord;
import com.bitforum.ai.mapper.AiModerationRecordMapper;
import com.bitforum.entity.User;
import com.bitforum.mapper.UserMapper;
import com.bitforum.service.UserService;
import com.bitforum.util.JwtUtil;

/**
 * 管理员 AI 审核接口测试（M16）。
 *
 * <p>覆盖接口层职责：路径与鉴权、查询筛选、人工反馈与标记已处理的写入效果，
 * 以及非法反馈值被拒绝。审核判断本身由 {@code ModerationServiceTest} 与真实调用冒烟覆盖。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminAiModerationControllerTest {

    private static final String ADMIN_TOKEN = "mod-admin-token";
    private static final String USER_TOKEN = "mod-user-token";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private AiModerationRecordMapper recordMapper;

    @MockitoBean
    private JwtUtil jwtUtil;

    private Long recordId;

    @BeforeEach
    void prepare() {
        Long adminId = createUser("mod-test-admin", UserService.ROLE_ADMIN);
        Long userId = createUser("mod-test-user", "USER");
        when(jwtUtil.getUserId(ADMIN_TOKEN)).thenReturn(adminId);
        when(jwtUtil.getUserId(USER_TOKEN)).thenReturn(userId);
        recordId = insertPendingRecord();
    }

    @Test
    void recordsShouldRejectAnonymousRequest() throws Exception {
        mockMvc.perform(get("/api/admin/ai/moderation/records")).andExpect(status().isUnauthorized());
    }

    @Test
    void recordsShouldRejectNonAdminUser() throws Exception {
        mockMvc.perform(get("/api/admin/ai/moderation/records").header("Authorization", "Bearer " + USER_TOKEN))
                .andExpect(status().isForbidden());
    }

    @Test
    void recordsShouldReturnPageForAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/ai/moderation/records")
                        .header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray());
    }

    @Test
    void pendingFilterShouldReturnHighPriorityRecordsFirst() throws Exception {
        mockMvc.perform(get("/api/admin/ai/moderation/records")
                        .param("pendingOnly", "true")
                        .header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].action").value("HIGH_PRIORITY_REVIEW"))
                .andExpect(jsonPath("$.data.records[0].decision").value("REJECT"));
    }

    @Test
    void feedbackShouldBePersistedForAdmin() throws Exception {
        mockMvc.perform(post("/api/admin/ai/moderation/records/" + recordId + "/feedback")
                        .header("Authorization", "Bearer " + ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"feedback\":\"CORRECT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        AiModerationRecord updated = recordMapper.selectById(recordId);
        assertEquals(AiModerationRecord.FEEDBACK_CORRECT, updated.getFeedback(), "反馈应写入数据库");
        assertEquals(Boolean.FALSE, updated.getHandled(), "反馈不应顺带改变处理状态");
    }

    @Test
    void invalidFeedbackValueShouldBeRejected() throws Exception {
        mockMvc.perform(post("/api/admin/ai/moderation/records/" + recordId + "/feedback")
                        .header("Authorization", "Bearer " + ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"feedback\":\"MAYBE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void markHandledShouldClosePendingRecord() throws Exception {
        mockMvc.perform(post("/api/admin/ai/moderation/records/" + recordId + "/handle")
                        .header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isOk());

        AiModerationRecord updated = recordMapper.selectById(recordId);
        assertEquals(Boolean.TRUE, updated.getHandled(), "应标记为已处理");
        assertEquals(AiModerationRecord.TARGET_COMMENT, updated.getTargetType());
    }

    // ==================== 辅助方法 ====================

    private Long createUser(String username, String role) {
        User user = new User();
        user.setUsername(username);
        user.setPassword("$2a$10$moderation-controller-test-placeholder");
        user.setNickname(username);
        user.setRole(role);
        user.setStatus(UserService.STATUS_ENABLED);
        userMapper.insert(user);
        return user.getId();
    }

    /** 插入一条高优先级待处理记录，用于验证排序与操作。 */
    private Long insertPendingRecord() {
        AiModerationRecord record = new AiModerationRecord();
        record.setTargetType(AiModerationRecord.TARGET_COMMENT);
        record.setTargetId(9001L);
        record.setTargetPreview("测试用评论内容");
        record.setAuthorId(9002L);
        record.setDecision("REJECT");
        record.setConfidence(0.95);
        record.setHarmfulScore(1.0);
        record.setPromotionScore(0.0);
        record.setFraudScore(0.0);
        record.setSpamScore(0.2);
        record.setSensitiveScore(0.0);
        record.setRiskScore(1.0);
        record.setMaxDimension("harmful");
        record.setMaxDimensionScore(1.0);
        record.setAction("HIGH_PRIORITY_REVIEW");
        record.setActionReason("测试数据");
        record.setPriority(2);
        record.setHandled(false);
        record.setModel("deepseek-flash");
        record.setLatencyMs(1000);
        recordMapper.insert(record);
        return record.getId();
    }
}

package com.bitforum.ai.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.bitforum.ai.dto.AiConversationResponse;
import com.bitforum.ai.dto.AiMessageResponse;
import com.bitforum.ai.orchestrator.AgentOrchestrator;
import com.bitforum.ai.service.AiConversationService;
import com.bitforum.entity.User;
import com.bitforum.service.UserService;
import com.bitforum.util.JwtUtil;

/**
 * AiController 接口契约测试（M13）。
 *
 * 采用与既有 UserNotificationControllerTest 相同的策略：mock 掉 Service 层与 JwtUtil，
 * 只验证接口路径、登录拦截、参数校验与响应结构，不触发真实大模型调用。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtUtil jwtUtil;
    @MockitoBean
    private UserService userService;
    @MockitoBean
    private AiConversationService conversationService;
    @MockitoBean
    private AgentOrchestrator orchestrator;

    @Test
    void createConversationShouldRequireLogin() throws Exception {
        mockMvc.perform(post("/api/ai/conversations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void listConversationsShouldRequireLogin() throws Exception {
        mockMvc.perform(get("/api/ai/conversations"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void sendMessageShouldRequireLogin() throws Exception {
        mockMvc.perform(post("/api/ai/conversations/1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"你好\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void userShouldCreateConversation() throws Exception {
        mockEnabledUser("ai-token", 82001L);
        when(conversationService.createConversation(eq(82001L), any())).thenReturn(conversation(1L, "新对话"));

        mockMvc.perform(post("/api/ai/conversations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .header("Authorization", "Bearer ai-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(1));
    }

    @Test
    void userShouldListOwnConversations() throws Exception {
        mockEnabledUser("ai-token", 82002L);
        when(conversationService.listConversations(82002L))
                .thenReturn(List.of(conversation(1L, "第一个会话"), conversation(2L, "第二个会话")));

        mockMvc.perform(get("/api/ai/conversations")
                .header("Authorization", "Bearer ai-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].title").value("第一个会话"));
    }

    @Test
    void userShouldSendMessageAndGetAnswer() throws Exception {
        mockEnabledUser("ai-token", 82003L);
        AiMessageResponse answer = new AiMessageResponse();
        answer.setId(10L);
        answer.setRole("assistant");
        answer.setContent("文章提交后会进入待审核状态。");
        answer.setLatencyMs(520);
        when(orchestrator.chat(1L, 82003L, "文章审核流程是什么？")).thenReturn(answer);

        mockMvc.perform(post("/api/ai/conversations/1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"文章审核流程是什么？\"}")
                .header("Authorization", "Bearer ai-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.role").value("assistant"))
                .andExpect(jsonPath("$.data.content").value("文章提交后会进入待审核状态。"));
    }

    @Test
    void emptyMessageShouldBeRejectedByValidation() throws Exception {
        mockEnabledUser("ai-token", 82004L);

        mockMvc.perform(post("/api/ai/conversations/1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"   \"}")
                .header("Authorization", "Bearer ai-token"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void overlongMessageShouldBeRejectedByValidation() throws Exception {
        mockEnabledUser("ai-token", 82005L);
        String tooLong = "问".repeat(2001);

        mockMvc.perform(post("/api/ai/conversations/1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"" + tooLong + "\"}")
                .header("Authorization", "Bearer ai-token"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void userShouldListMessagesOfOwnConversation() throws Exception {
        mockEnabledUser("ai-token", 82006L);
        AiMessageResponse message = new AiMessageResponse();
        message.setRole("user");
        message.setContent("你好");
        when(conversationService.listMessages(1L, 82006L)).thenReturn(List.of(message));

        mockMvc.perform(get("/api/ai/conversations/1/messages")
                .header("Authorization", "Bearer ai-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].content").value("你好"));
    }

    // ==================== M4：异常响应统一 ====================

    /**
     * M4：聊天链路出现未知异常时必须返回 HTTP 500 + 固定安全文案。
     *
     * <p>修复前 `Result.fail(e.getMessage())` 会把未知异常降级成 HTTP 400，
     * 并把内部异常消息原样回显给客户端；这条用例同时锁住"状态码"与"不泄露"。
     */
    @Test
    void unknownChatErrorShouldReturn500WithoutLeakingInternalMessage() throws Exception {
        mockEnabledUser("ai-token", 82007L);
        String internalSecret = "内部细节：jdbc:mysql://prod-db:3306/bit_forum?user=root";
        when(orchestrator.chat(1L, 82007L, "触发未知异常"))
                .thenThrow(new IllegalStateException(internalSecret));

        mockMvc.perform(post("/api/ai/conversations/1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"触发未知异常\"}")
                        .header("Authorization", "Bearer ai-token"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("服务器内部错误，请稍后尝试"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("jdbc:mysql"))));
    }

    /** M4：会话不存在（404）与越权（403）的语义保持不变。 */
    @Test
    void responseStatusExceptionsShouldKeepTheirHttpSemantics() throws Exception {
        mockEnabledUser("ai-token", 82008L);
        when(orchestrator.chat(1L, 82008L, "不存在的会话"))
                .thenThrow(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "会话不存在"));
        when(orchestrator.chat(1L, 82008L, "别人的会话"))
                .thenThrow(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.FORBIDDEN, "无权访问该会话"));

        mockMvc.perform(post("/api/ai/conversations/1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"不存在的会话\"}")
                        .header("Authorization", "Bearer ai-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));

        mockMvc.perform(post("/api/ai/conversations/1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"别人的会话\"}")
                        .header("Authorization", "Bearer ai-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    // ==================== 辅助方法 ====================

    /** 模拟一个已登录且状态正常的普通用户。 */
    private void mockEnabledUser(String token, Long userId) {
        User user = new User();
        user.setId(userId);
        user.setStatus(UserService.STATUS_ENABLED);
        user.setRole("USER");

        when(jwtUtil.getUserId(token)).thenReturn(userId);
        when(userService.findById(userId)).thenReturn(user);
    }

    private AiConversationResponse conversation(Long id, String title) {
        AiConversationResponse response = new AiConversationResponse();
        response.setId(id);
        response.setTitle(title);
        response.setAgentType("QA");
        response.setMessageCount(0);
        response.setTotalTokens(0);
        return response;
    }
}

package com.bitforum.ai.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bitforum.ai.entity.AiConversation;
import com.bitforum.ai.entity.AiExecutionTrace;
import com.bitforum.ai.entity.AiMessage;
import com.bitforum.ai.entity.AiUsageStat;
import com.bitforum.ai.mapper.AiConversationMapper;
import com.bitforum.ai.mapper.AiExecutionTraceMapper;
import com.bitforum.ai.mapper.AiMessageMapper;
import com.bitforum.ai.mapper.AiUsageStatMapper;
import com.bitforum.ai.rag.RagService;
import com.bitforum.ai.trace.TraceDegradeReason;
import com.bitforum.entity.User;
import com.bitforum.mapper.UserMapper;
import com.bitforum.service.UserService;
import com.bitforum.util.JwtUtil;

/**
 * 运行时模型不可用 —— **HTTP 层**故障注入（M18 收口阶段 3B）。
 *
 * <p><b>与组件级测试的分工</b>：{@code QaAgentFaultInjectionIntegrationTest} 验证"Agent 返回了什么、
 * 轨迹里写了什么"；本类验证"**用户实际收到的 HTTP 响应**"——{@code Result<T>} 结构、HTTP 状态、
 * 响应体是否泄露底层细节，以及**非 AI 主链路是否仍正常**。
 *
 * <p><b>注入方式</b>：应用上下文正常启动后，把 DeepSeek 的 base-url 指向不可达的回环端口
 * {@code http://127.0.0.1:9}（占位 api-key、重试 1 次）——**不读取也不修改 `.env`、不使用真实凭据、
 * 不访问外网**。所有覆盖都只作用于本测试的 Spring 上下文。
 *
 * <p><b>为什么这算"运行时"故障</b>：api-key 与 base-url 都是运行期属性，应用**照常启动**；
 * 故障只发生在真正发起模型请求的那一刻，这正是任务书要求验证的场景
 * （"不能通过把 API Key 设为空让应用启动失败来完成"）。
 */
@SpringBootTest(properties = {
        "spring.ai.deepseek.chat.enabled=true",
        "spring.ai.deepseek.api-key=fault-injection-placeholder",
        "spring.ai.deepseek.base-url=http://127.0.0.1:9",
        "spring.ai.retry.max-attempts=1",
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
@AutoConfigureMockMvc
class AiFaultInjectionHttpTest {

    private static final Logger log = LoggerFactory.getLogger(AiFaultInjectionHttpTest.class);

    private static final String TOKEN = "m18-fault-injection-http-token";
    private static final Long USER_ID = 99094L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private AiConversationMapper conversationMapper;
    @Autowired
    private AiMessageMapper messageMapper;
    @Autowired
    private AiExecutionTraceMapper traceMapper;
    @Autowired
    private AiUsageStatMapper usageMapper;

    @MockitoBean
    private JwtUtil jwtUtil;
    /** 检索替身：让失败**只**来自模型端点，避免"检索失败降级"混进本用例的断言。 */
    @MockitoBean
    private RagService ragService;

    private Long conversationId;

    @BeforeEach
    void prepare() {
        when(jwtUtil.getUserId(TOKEN)).thenReturn(USER_ID);
        when(ragService.retrieve(anyString()))
                .thenReturn(new RagService.RetrievalResult(List.of(), List.of(), 0));

        User user = new User();
        user.setId(USER_ID);
        user.setUsername("m18_fault_injection_user");
        user.setPassword("$2a$10$m18-fault-injection-placeholder");
        user.setNickname("M18 故障注入用户");
        user.setRole("USER");
        user.setStatus(UserService.STATUS_ENABLED);
        userMapper.insert(user);

        AiConversation conversation = new AiConversation();
        conversation.setUserId(USER_ID);
        conversation.setTitle("M18 故障注入会话");
        conversation.setAgentType("QA");
        conversation.setMessageCount(0);
        conversation.setTotalTokens(0);
        conversationMapper.insert(conversation);
        conversationId = conversation.getId();
    }

    @AfterEach
    void cleanUp() {
        messageMapper.delete(new LambdaQueryWrapper<AiMessage>()
                .eq(AiMessage::getConversationId, conversationId));
        conversationMapper.deleteById(conversationId);
        traceMapper.delete(new LambdaQueryWrapper<AiExecutionTrace>()
                .eq(AiExecutionTrace::getConversationId, conversationId));
        usageMapper.delete(new LambdaQueryWrapper<AiUsageStat>()
                .eq(AiUsageStat::getUserId, USER_ID));
        userMapper.deleteById(USER_ID);
    }

    /**
     * 故障只应影响 AI 能力：注入期间，**公开的文章接口照常返回数据**。
     *
     * <p>这条断言是任务书 3B 的"论坛非 AI 主链路仍正常"要求的最小可复现证据。
     */
    @Test
    void shouldKeepNonAiArticleEndpointWorkingWhileModelIsUnavailable() throws Exception {
        mockMvc.perform(get("/api/article/listAll"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());
    }

    /**
     * AI 请求：HTTP 200 + `Result<T>` 约定 + 统一降级文案；响应体**不得**泄露底层细节。
     */
    @Test
    void shouldDegradeAiChatWithFriendlyMessageAndNoLeakedDetail() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/ai/conversations/" + conversationId + "/messages")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"M18 故障注入：模型端点不可达时会怎样？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.role").value("assistant"))
                // 统一降级文案：与 TraceDegradeReason 的固定话术完全一致
                .andExpect(jsonPath("$.data.content")
                        .value(TraceDegradeReason.userMessage(TraceDegradeReason.LLM_ERROR)))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertNoLeakedDetail(body);

        // 轨迹必须如实标记降级，且 reason 可枚举、message 为统一文案
        AiExecutionTrace trace = traceMapper.selectOne(new LambdaQueryWrapper<AiExecutionTrace>()
                .eq(AiExecutionTrace::getConversationId, conversationId)
                .orderByDesc(AiExecutionTrace::getId)
                .last("LIMIT 1"));
        assertNotNull(trace, "本轮对话应留下执行轨迹");
        assertEquals(AiExecutionTrace.STATUS_DEGRADED, trace.getStatus());
        assertEquals(TraceDegradeReason.LLM_ERROR, trace.getDegradeReason());
        assertEquals(TraceDegradeReason.userMessage(TraceDegradeReason.LLM_ERROR), trace.getMessage());
        assertNoLeakedDetail(trace.getSteps() == null ? "" : trace.getSteps());

        // 降级仍然记一条 0 token 的用量明细（便于审计"今天降级了多少次"），但不产生费用
        AiUsageStat usage = usageMapper.selectOne(new LambdaQueryWrapper<AiUsageStat>()
                .eq(AiUsageStat::getUserId, USER_ID)
                .orderByDesc(AiUsageStat::getId)
                .last("LIMIT 1"));
        assertNotNull(usage, "降级应留下用量明细（token 为 0）");
        assertEquals(0, usage.getTotalTokens());
        assertEquals(AiUsageStat.RESULT_DEGRADED, usage.getResult());

        log.info("M18_FAULT_INJECTION 降级响应已脱敏：status={}，reason={}，usageTokens={}",
                trace.getStatus(), trace.getDegradeReason(), usage.getTotalTokens());
    }

    /**
     * 响应体与轨迹都不得出现：注入地址、连接错误文本、Java 异常名或堆栈帧。
     * （凭据类关键词一并检查，防止未来改动把敏感信息带进用户可见响应。）
     */
    private void assertNoLeakedDetail(String text) {
        assertFalse(text.contains("127.0.0.1"), "不应暴露注入的模型地址：" + text);
        assertFalse(text.toLowerCase().contains("connection refused"), "不应暴露连接错误文本");
        assertFalse(text.contains("Exception"), "不应暴露 Java 异常名");
        assertFalse(text.contains("at com.bitforum"), "不应暴露堆栈帧");
        assertFalse(text.contains("deepseek-placeholder"), "不应暴露 api-key 占位值");
        assertFalse(text.toLowerCase().contains("bearer "), "不应回显 Authorization");
    }
}

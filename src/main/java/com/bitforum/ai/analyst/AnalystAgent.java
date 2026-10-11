package com.bitforum.ai.analyst;

import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.bitforum.ai.degrade.AiDegradeGuard;
import com.bitforum.ai.trace.TraceDegradeReason;
import com.bitforum.ai.trace.TraceRecorder;
import com.bitforum.dto.AdminDashboardSummaryResponse;
import com.bitforum.service.AdminDashboardService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 运营分析 Agent（M17）。
 *
 * <p><b>职责边界：只负责"生成"，不负责"何时生成、是否落库"。</b>
 * 与 {@code ModerationAgent} 一样不实现 {@code Agent} 接口 —— 那个接口面向多轮对话，
 * 而运营分析是被一次操作触发的一次性分析，套上去会让接口语义失真。
 *
 * <p><b>关键设计：先取一次统计，同时用作模型输入与落库快照。</b>
 * {@code AdminDashboardService.summary()} 是实时聚合查询，调用两次可能拿到两份不同的数据。
 * 因此这里只取一次，把它既交给 {@link AnalystTools} 给模型读，又序列化成 {@code dataSnapshot}
 * 随报告一起存。这样报告里出现的每个数字都能和快照逐条对账 —— 只存正文的话，
 * 事后没人能回答"报告里那个数字当时对不对"。
 *
 * <p><b>提示词依据 T10 实测</b>（findings.md 6.14）：模型调用工具 1 次/轮，
 * 引用的数字 10/10、10/12 命中真实统计（未命中的 2 个是合理百分比换算），
 * 且会主动声明"工具未提供的数据暂无"。因此提示词保留了"数字只能来自工具返回"这条硬约束。
 *
 * <p><b>不抛异常</b>：AI 不可用时返回降级结果并由调用方决定如何处理，
 * 论坛主流程不能因为 AI 故障而不可用。
 */
@Component
public class AnalystAgent {

    private static final Logger log = LoggerFactory.getLogger(AnalystAgent.class);

    /**
     * 一次运营分析的结果。
     *
     * @param content      模型生成的洞察正文；降级时为空
     * @param dataSnapshot 本次分析使用的统计快照（JSON）。**降级时同样有值** ——
     *                     统计是本地聚合的，与模型是否可用无关，保留它便于排查与后续重试
     * @param dataTime     快照对应的时刻
     * @param toolCallCount 模型调用统计工具的次数；为 0 说明它没看数据就写结论
     * @param degraded     是否走了降级链路
     */
    public record InsightOutcome(
            String content,
            String dataSnapshot,
            LocalDateTime dataTime,
            String model,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens,
            long latencyMillis,
            int toolCallCount,
            boolean degraded,
            String errorMessage,
            /** 降级原因码（M18）；正常结果为 null，见 TraceDegradeReason */
            String degradeReason) {

        public static InsightOutcome of(String content, String dataSnapshot, LocalDateTime dataTime,
                                        String model, Integer promptTokens, Integer completionTokens,
                                        Integer totalTokens, long latencyMillis, int toolCallCount) {
            return new InsightOutcome(content, dataSnapshot, dataTime, model, promptTokens,
                    completionTokens, totalTokens, latencyMillis, toolCallCount, false, null, null);
        }

        /** 生成失败：正文为空，但统计快照仍然保留 */
        public static InsightOutcome degraded(String reason, String dataSnapshot, LocalDateTime dataTime,
                                              String model, long latencyMillis, String errorMessage) {
            return new InsightOutcome(null, dataSnapshot, dataTime, model, null, null, null,
                    latencyMillis, 0, true, abbreviate(errorMessage), reason);
        }

        private static String abbreviate(String text) {
            if (text == null) {
                return null;
            }
            return text.length() <= 500 ? text : text.substring(0, 500);
        }
    }

    private static final String SYSTEM_PROMPT = """
            你是 BitForum 技术社区的运营分析助手，服务对象是社区管理员。

            系统提供工具用于获取社区运营数据看板汇总。分析要求：

            1. **必须先调用工具获取真实数据**，再基于数据写分析；不要凭空推测任何数字。
            2. 输出结构（用 Markdown）：
               - 先用一段话概述社区当前的整体状况；
               - 再用二级标题「值得注意的信号」列出 2~4 个要点（例如待处理积压、内容产出趋势、互动情况）；
               - 最后用二级标题「可执行建议」给出 2~3 条建议，每条说明依据。
            3. **数字纪律**：引用的每个数字都必须来自工具返回的统计。不确定或没有的数据，
               直接说"暂无该项数据"，绝不编造、不估算。
            4. 用中文，面向非技术背景的管理员，避免术语堆砌。
            5. 篇幅控制在 600 字以内：这是看板上的一张卡片，不是长篇报告。
            """;

    private final ObjectProvider<ChatClient> chatClientProvider;
    private final AdminDashboardService dashboardService;
    private final ObjectMapper objectMapper;
    private final String modelName;
    private final TraceRecorder traceRecorder;
    private final AiDegradeGuard degradeGuard;

    public AnalystAgent(ObjectProvider<ChatClient> chatClientProvider,
                        AdminDashboardService dashboardService,
                        ObjectMapper objectMapper,
                        @Value("${bitforum.ai.analyst.model-name:deepseek-flash}") String modelName,
                        TraceRecorder traceRecorder,
                        AiDegradeGuard degradeGuard) {
        this.chatClientProvider = chatClientProvider;
        this.dashboardService = dashboardService;
        this.objectMapper = objectMapper;
        this.modelName = modelName;
        this.traceRecorder = traceRecorder;
        this.degradeGuard = degradeGuard;
    }

    /**
     * 生成一份运营洞察。
     *
     * <p>本方法不抛异常：任何失败都返回 {@link InsightOutcome#degraded}，
     * 由调用方决定是记录失败还是稍后重试。
     */
    public InsightOutcome analyze() {
        long startedAt = System.currentTimeMillis();
        LocalDateTime dataTime = LocalDateTime.now();

        // 1. 只取一次统计：同一份数据既给模型读，也作为落库快照（保证可对账）
        AdminDashboardSummaryResponse summary;
        try {
            summary = dashboardService.summary();
        } catch (RuntimeException exception) {
            log.error("运营统计聚合失败，无法生成洞察", exception);
            // 注意这里的原因码是 DATA_UNAVAILABLE 而不是 LLM_*：统计是本地聚合的，
            // 失败与模型无关。把两者混为一谈会让"AI 到底出没出问题"看不出来
            return InsightOutcome.degraded(TraceDegradeReason.DATA_UNAVAILABLE, null, dataTime, modelName,
                    System.currentTimeMillis() - startedAt,
                    degradeGuard.degrade(TraceDegradeReason.DATA_UNAVAILABLE, exception.getMessage()));
        }

        String snapshot;
        try {
            snapshot = objectMapper.writeValueAsString(summary);
        } catch (JsonProcessingException exception) {
            log.error("运营统计快照序列化失败", exception);
            return InsightOutcome.degraded(TraceDegradeReason.DATA_UNAVAILABLE, null, dataTime, modelName,
                    System.currentTimeMillis() - startedAt,
                    degradeGuard.degrade(TraceDegradeReason.DATA_UNAVAILABLE, exception.getMessage()));
        }

        // 2. 模型不可用时仍然保留快照：统计是本地算的，与 AI 是否可用无关
        ChatClient chatClient = chatClientProvider.getIfAvailable();
        if (chatClient == null) {
            log.debug("ChatClient 不可用，运营洞察走降级");
            return InsightOutcome.degraded(TraceDegradeReason.AI_DISABLED, snapshot, dataTime, modelName,
                    System.currentTimeMillis() - startedAt,
                    degradeGuard.degrade(TraceDegradeReason.AI_DISABLED,
                            "ChatClient 不可用（未配置 DEEPSEEK_API_KEY 或未启用 AI）"));
        }

        AnalystTools tools = new AnalystTools(summary);
        try {
            // M18：与 QA 链路同一处理 —— 工具执行循环在 provider 内部完成，
            // 只有包装成带轨迹采集的回调才能记录"模型查了哪些数据"（T12 实测结论）
            ToolCallback[] toolCallbacks = traceRecorder.wrapTools(tools);
            ChatClient.ChatClientRequestSpec requestSpec = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user("请分析当前社区的运营状况，并给出建议。");
            if (toolCallbacks.length > 0) {
                requestSpec = requestSpec.toolCallbacks(toolCallbacks);
            }
            ChatResponse response = requestSpec.call().chatResponse();

            long latency = System.currentTimeMillis() - startedAt;
            String content = response == null || response.getResult() == null
                    || response.getResult().getOutput() == null
                            ? ""
                            : response.getResult().getOutput().getText();

            if (content == null || content.isBlank()) {
                log.warn("运营洞察生成结果为空");
                return InsightOutcome.degraded(TraceDegradeReason.EMPTY_RESPONSE, snapshot, dataTime, modelName,
                        latency, degradeGuard.degrade(TraceDegradeReason.EMPTY_RESPONSE, "模型返回空内容"));
            }
            if (tools.callCount() == 0) {
                // 不失败，但要留痕：没看数据就写结论的报告不可信
                log.warn("模型未调用统计工具即生成结论，报告可信度存疑");
            }

            log.info(">>> 运营洞察生成完成：调用统计工具 {} 次，耗时 {} ms，快照 {} 字符",
                    tools.callCount(), latency, snapshot.length());

            return InsightOutcome.of(content, snapshot, dataTime, modelName,
                    promptTokens(response), completionTokens(response), totalTokens(response),
                    latency, tools.callCount());
        } catch (RuntimeException exception) {
            long latency = System.currentTimeMillis() - startedAt;
            log.error("运营洞察生成失败", exception);
            String reason = degradeGuard.classify(exception);
            return InsightOutcome.degraded(reason, snapshot, dataTime, modelName, latency,
                    degradeGuard.degrade(reason, exception.getMessage()));
        }
    }

    private Integer promptTokens(ChatResponse response) {
        return usageValue(response, true);
    }

    private Integer completionTokens(ChatResponse response) {
        return usageValue(response, false);
    }

    private Integer totalTokens(ChatResponse response) {
        if (response == null || response.getMetadata() == null || response.getMetadata().getUsage() == null) {
            return null;
        }
        return response.getMetadata().getUsage().getTotalTokens();
    }

    private Integer usageValue(ChatResponse response, boolean prompt) {
        if (response == null || response.getMetadata() == null || response.getMetadata().getUsage() == null) {
            return null;
        }
        return prompt
                ? response.getMetadata().getUsage().getPromptTokens()
                : response.getMetadata().getUsage().getCompletionTokens();
    }
}

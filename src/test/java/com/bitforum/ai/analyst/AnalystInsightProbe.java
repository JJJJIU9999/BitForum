package com.bitforum.ai.analyst;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.bitforum.dto.AdminDashboardSummaryResponse;
import com.bitforum.service.AdminDashboardService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * M17 前置验证探针（T10）：AnalystAgent 的「数字保真」风险。
 *
 * <p><b>为什么验这一件事</b>：AnalystAgent 的做法是把 {@code AdminDashboardService} 的统计
 * 包装成工具交给模型，由模型写一段运营洞察。这里真正会翻车的地方不是"模型会不会写文章"，
 * 而是<b>它会不会把数字说错</b> —— 运营洞察里每一个数字都来自工具返回的 JSON，
 * 一旦模型记错、算错或编造，管理员看到的报告就是错的，而且很难发现。
 *
 * <p>M14 只验证过"工具能被正确调用"，从未验证过"工具返回一大段统计后，模型引用其中的数字是否可靠"。
 *
 * <p>统计口径：
 * <ol>
 *   <li><b>是否真的调用了工具</b>（没调用就说明提示词/工具描述有问题，模型只能凭空编）；</li>
 *   <li><b>数字命中率</b>：把模型输出里的所有数字与工具返回的真实统计做对照。
 *       命中 = 该数字确实出现在统计里；未命中 = 需要人工判断（可能是合理派生，如加总或百分比，
 *       也可能是幻觉）。探针只报告、不武断判错。</li>
 *   <li>Token 消耗与耗时，用于估算成本。</li>
 * </ol>
 *
 * <p>默认不执行（消耗真实 API 额度）：
 *
 * <pre>
 *   export DEEPSEEK_CHAT_ENABLED=true
 *   export DEEPSEEK_API_KEY="$(grep '^DEEPSEEK_API_KEY=' .env | cut -d= -f2-)"
 *   ./mvnw -s maven-settings.xml -Dtest=AnalystInsightProbe test
 * </pre>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_CHAT_ENABLED", matches = "true")
class AnalystInsightProbe {

    private static final Logger log = LoggerFactory.getLogger(AnalystInsightProbe.class);

    private static final int ROUNDS = 2;

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");

    private static final String SYSTEM_PROMPT = """
            你是 BitForum 技术社区的运营分析助手，服务对象是社区管理员。

            系统提供工具用于获取社区运营数据看板汇总。分析要求：

            1. **必须先调用工具获取真实数据**，再基于数据写分析；不要凭空推测任何数字。
            2. 输出结构：
               - 用一段话概述社区当前的整体状况；
               - 用要点列出 2~4 个值得注意的信号（例如待处理积压、内容产出趋势、互动情况）；
               - 给出 2~3 条可执行建议，每条说明依据。
            3. **数字纪律**：引用的每个数字都必须来自工具返回的统计。不确定或没有的数据，
               直接说"暂无该项数据"，绝不编造、不估算。
            4. 用中文，面向非技术背景的管理员，避免术语堆砌。
            """;

    /**
     * 看板统计工具（探针内的临时实现）。
     *
     * <p>刻意与正式实现保持同样的形态：**一个工具返回完整统计**，
     * 而不是拆成七八个小工具 —— 先验证"返回一大段 JSON"这条路本身是否可靠。
     * 工具粒度本身也是待验证项，见本节结论。
     */
    static class DashboardTools {

        private final AdminDashboardSummaryResponse summary;
        private int callCount = 0;

        DashboardTools(AdminDashboardSummaryResponse summary) {
            this.summary = summary;
        }

        int callCount() {
            return callCount;
        }

        @Tool(description = """
                获取社区运营数据看板的完整汇总统计，包含：
                用户（总数、普通用户数、管理员数、启用数、禁用数）、
                文章（总数、草稿数、待审核数、已发布数、已驳回数、已下架数、今日新增、近 7 天新增）、
                评论（总数、今日新增、近 7 天新增）、
                板块（总数、启用数、禁用数）、收藏总数、
                通知（总数、未读数）、
                举报（总数、待处理数、已处理数、已驳回数、今日新增、近 7 天新增）、
                以及热门文章排行（文章 id、标题、热度分、浏览量、点赞数）。
                需要了解社区现状、写作运营分析或回答任何站内统计问题时调用本工具。
                """)
        public AdminDashboardSummaryResponse getDashboardSummary() {
            callCount++;
            return summary;
        }
    }

    @Autowired
    private ObjectProvider<ChatClient> chatClientProvider;

    @Autowired
    private AdminDashboardService adminDashboardService;

    @Test
    void shouldQuoteDashboardNumbersFaithfully() {
        ChatClient chatClient = chatClientProvider.getIfAvailable();
        if (chatClient == null) {
            throw new IllegalStateException("ChatClient 不可用：需要 DEEPSEEK_CHAT_ENABLED=true 与有效 API Key");
        }

        // 真实统计数据来自本地库（不是构造样本），这样核对才有意义
        AdminDashboardSummaryResponse summary = adminDashboardService.summary();
        String referenceJson;
        try {
            referenceJson = new ObjectMapper().writeValueAsString(summary);
        } catch (Exception exception) {
            throw new IllegalStateException("统计对象序列化失败", exception);
        }
        Set<String> referenceNumbers = extractNumbers(referenceJson);

        log.info(">>> T10 工具将返回的统计 JSON（约 {} 字符）：{}", referenceJson.length(), referenceJson);
        log.info(">>> 统计中出现的数字集合：{}", referenceNumbers);

        List<String> report = new ArrayList<>();
        for (int round = 1; round <= ROUNDS; round++) {
            DashboardTools tools = new DashboardTools(summary);
            long startedAt = System.currentTimeMillis();
            try {
                ChatResponse response = chatClient.prompt()
                        .system(SYSTEM_PROMPT)
                        .user("请分析当前社区的运营状况，并给出建议。")
                        .tools(tools)
                        .call()
                        .chatResponse();

                long latency = System.currentTimeMillis() - startedAt;
                String content = response == null || response.getResult() == null
                        || response.getResult().getOutput() == null
                                ? ""
                                : response.getResult().getOutput().getText();

                Set<String> outputNumbers = extractNumbers(content == null ? "" : content);
                Set<String> hit = new LinkedHashSet<>(outputNumbers);
                hit.retainAll(referenceNumbers);
                Set<String> miss = new LinkedHashSet<>(outputNumbers);
                miss.removeAll(referenceNumbers);

                String line = ("第 %d 轮：调用工具=%s 工具次数=%d 输出数字=%d 命中统计=%d 未命中=%s "
                        + "耗时=%dms promptTokens=%s completionTokens=%s")
                        .formatted(round,
                                tools.callCount() > 0 ? "是" : "**否**",
                                tools.callCount(),
                                outputNumbers.size(),
                                hit.size(),
                                miss,
                                latency,
                                promptTokens(response),
                                completionTokens(response));
                log.info(">>> {}", line);
                log.info(">>> 第 {} 轮模型输出全文：\n{}", round, content);
                report.add(line);
            } catch (RuntimeException exception) {
                String line = "第 %d 轮：调用失败（%s）".formatted(round, exception.getMessage());
                log.error(">>> {}", line, exception);
                report.add(line);
            }
        }

        log.info("""
                
                ================= T10 探针汇总（{} 轮真实调用） =================
                {}
                ==================================================================
                """, ROUNDS, String.join("\n", report));
    }

    /** 提取文本中出现的所有数字（含小数），用于与真实统计对照。 */
    private Set<String> extractNumbers(String text) {
        Set<String> numbers = new LinkedHashSet<>();
        if (text == null) {
            return numbers;
        }
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            numbers.add(matcher.group());
        }
        return numbers;
    }

    private Integer promptTokens(ChatResponse response) {
        return response == null || response.getMetadata() == null || response.getMetadata().getUsage() == null
                ? null
                : response.getMetadata().getUsage().getPromptTokens();
    }

    private Integer completionTokens(ChatResponse response) {
        return response == null || response.getMetadata() == null || response.getMetadata().getUsage() == null
                ? null
                : response.getMetadata().getUsage().getCompletionTokens();
    }
}

package com.bitforum.ai.moderation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.bitforum.ai.degrade.AiDegradeGuard;
import com.bitforum.ai.trace.TraceDegradeReason;

/**
 * 内容审核 Agent（M16）。
 *
 * <p>对一篇文章或一条评论做五维风险评估，返回结构化结论。
 * 与 {@code QaAgent} 不同，本类**不实现 {@code Agent} 接口**：
 * 那个接口面向"多轮对话"（入参是会话上下文与历史消息），而审核是被业务事件触发的
 * 一次性分析，没有对话语义。强行套用会让接口语义失真。
 *
 * <p><b>只负责判断，不负责动作</b>：本类只产出 {@link ModerationAssessment}（AI 判断），
 * 是否自动放行、是否生成待处理记录由 {@code ModerationService} 按确定性规则决定。
 * 这样即使审核策略调整（例如改变自动放行阈值），也不需要改动提示词与模型调用。
 *
 * <p>提示词依据 T7 实测结果设计（findings.md 6.12）：实验表明"只要求保守、不给判据"会让模型
 * 把明确违规也判成 REVIEW（8 条样本只有 5 条正确）；给出明确判据后 8/8 正确。
 * 因此这里同时写明两件事：明确违规要敢于判 REJECT，模糊内容不要误伤。
 */
@Component
public class ModerationAgent {

    private static final Logger log = LoggerFactory.getLogger(ModerationAgent.class);

    /**
     * 一次审核分析的结果；{@code degraded} 为 true 时 {@code assessment} 为 null。
     *
     * <p>M18 增加 token 三项：审核链路的用量此前**完全没有被记录过**
     * （{@code ai_moderation_record} 里根本没有 token 字段），
     * 导致"按 Agent 统计 token"在审核这一路上永远是空的。
     * 这里从 {@code ChatResponse} 取出来，交给轨迹与用量埋点统一落库。
     */
    public record ModerationOutcome(
            ModerationAssessment assessment,
            String model,
            long latencyMillis,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens,
            boolean degraded,
            String errorMessage,
            /** 降级原因码（M18）；正常结果与"内容为空"之外的降级都会带上，见 TraceDegradeReason */
            String degradeReason) {

        public static ModerationOutcome of(ModerationAssessment assessment, String model, long latencyMillis,
                                           Integer promptTokens, Integer completionTokens,
                                           Integer totalTokens) {
            return new ModerationOutcome(assessment, model, latencyMillis, promptTokens, completionTokens,
                    totalTokens, false, null, null);
        }

        /** 分析失败：不产生判断，由正常人工流程兜底 */
        public static ModerationOutcome degraded(String reason, String errorMessage, String model,
                                                 long latencyMillis) {
            return new ModerationOutcome(null, model, latencyMillis, null, null, null, true,
                    abbreviate(errorMessage), reason);
        }

        private static String abbreviate(String text) {
            if (text == null) {
                return null;
            }
            return text.length() <= 255 ? text : text.substring(0, 255);
        }
    }

    /**
     * 审核提示词。
     *
     * <p>判据与 `docs/graduation/ai-agent-upgrade/m16-eval-protocol.md`（评测规范 v1）对齐：
     * 规范定义"什么算违规"，本提示词负责让模型按该口径判断。
     * 为对齐规范而调整提示词是允许的；反过来为迁就模型而放宽规范则不允许（需走变更流程）。
     *
     * <p>三点设计意图：
     * <ol>
     *   <li>把 REJECT 的适用情形写具体 —— A/B 对照实验证明不给判据时模型会"一律转人工"；</li>
     *   <li>把 PASS 的边界也写清楚（单个短互动、观点性批评、无关闲聊），避免误伤正常内容；</li>
     *   <li>明确要求不依赖上下文臆测，避免模型自行脑补。</li>
     * </ol>
     */
    private static final String SYSTEM_PROMPT = """
            你是 BitForum 技术社区的内容审核助手。请从五个维度评估给定内容的合规风险。

            五个维度（每个给 0~1 的分数，越高表示越可疑）：
            - harmful：人身攻击、侮辱、歧视、仇恨、暴力威胁。特别注意：
              针对**学历、职业、性别、地域、年龄等身份特征**的贬低、"劝退"或嘲讽同样属于歧视，
              即使语气像"建议"也应按歧视处理（例如"大专生就别碰分布式了"属于歧视而非技术建议）。
            - promotion：广告营销与站外引流（含微信/QQ/手机号等联系方式、免费领资料、返利、内部名额、
              导流到个人主页或外部链接）
            - fraud：诈骗与钓鱼（索取账号密码/银行卡/身份证、虚假中奖、虚假补贴、投资"稳赚"承诺）
            - spam：明显重复刷屏（同一内容反复刷、连续重复字符 8 个以上）、纯乱码或无意义的符号串
            - sensitive：涉政敏感、违法违规、色情低俗、泄露他人隐私（手机号、住址、身份证等）

            判定档位 decision：
            - REJECT：任一维度存在**明确的**违规证据（上列情形有确凿体现）
            - REVIEW：有风险迹象但特征不明确，或信息不足以判断。典型情况包括：
              · 语义模糊的引流（暗示"资料在我主页""可以私信我"，但没有联系方式、没有利益诱饵）；
              · 含站外链接、且内容本身有技术价值，是否属于营销需要人工判断；
              · 疑似但证据不足的诈骗或广告话术。
            - PASS：属于以下任一情形 ——
              · 正常的技术讨论、提问、经验分享、复盘；
              · 对观点或方案的负面评价（否定**观点**不等于人身攻击；但贬低**人的身份特征**属于歧视，应判 REJECT）；
              · 单个短互动（"顶一下""感谢分享""学到了"），单次不构成灌水；
              · 与主题无关但不违规的日常闲聊；
              · 推荐自己或他人的开源项目、文章，且不含联系方式与利益诱饵。

            要求：
            1. 明确违规要敢于判 REJECT，不要一律转人工；同时不要误伤正常内容，语义模糊的判 REVIEW。
            2. confidence 表示你对本次判定的把握程度（0~1）。
            3. 每个维度都要给出简短理由；summary 用一句话概括整体结论。
            4. 只依据给定内容本身判断，不要脑补上下文或用户历史。
            """;

    private final ObjectProvider<ChatClient> chatClientProvider;
    private final String modelName;
    private final int maxContentChars;
    private final AiDegradeGuard degradeGuard;

    public ModerationAgent(
            ObjectProvider<ChatClient> chatClientProvider,
            @Value("${bitforum.ai.moderation.model-name:deepseek-flash}") String modelName,
            @Value("${bitforum.ai.moderation.max-content-chars:2000}") int maxContentChars,
            AiDegradeGuard degradeGuard) {
        this.chatClientProvider = chatClientProvider;
        this.modelName = modelName;
        this.maxContentChars = maxContentChars;
        this.degradeGuard = degradeGuard;
    }

    /**
     * 对一段内容做风险分析。
     *
     * <p>本方法**不抛异常**：审核是增强能力，AI 不可用时返回降级结果，
     * 由调用方按原有流程（人工审核）处理，绝不影响内容发布主流程。
     */
    public ModerationOutcome analyze(ModerationTargetType targetType, String content) {
        long startedAt = System.currentTimeMillis();

        if (!StringUtils.hasText(content)) {
            return ModerationOutcome.degraded(TraceDegradeReason.NOTHING_TO_DO,
                    "内容为空，无需审核", modelName, 0L);
        }

        ChatClient chatClient = chatClientProvider.getIfAvailable();
        if (chatClient == null) {
            // M18：降级文案与原因码由 Guard 统一给出（"为什么降级"在全站是同一套说法）
            String message = degradeGuard.degrade(TraceDegradeReason.AI_DISABLED,
                    "ChatClient 不可用（未配置 DEEPSEEK_API_KEY 或未启用 AI）");
            return ModerationOutcome.degraded(TraceDegradeReason.AI_DISABLED, message, modelName,
                    System.currentTimeMillis() - startedAt);
        }

        try {
            // M18：改为 responseEntity(...)，因为要同时拿到结构化结果与 ChatResponse 里的
            // token 用量 —— entity(...) 只返回解析后的对象，usage 会在这里丢掉
            var responseEntity = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(buildUserPrompt(targetType, content))
                    .call()
                    .responseEntity(ModerationAssessment.class);

            long latency = System.currentTimeMillis() - startedAt;
            ChatResponse response = responseEntity == null ? null : responseEntity.response();
            ModerationAssessment assessment = responseEntity == null ? null : responseEntity.entity();
            if (assessment == null) {
                return ModerationOutcome.degraded(TraceDegradeReason.EMPTY_RESPONSE,
                        degradeGuard.degrade(TraceDegradeReason.EMPTY_RESPONSE, "模型返回空结果"),
                        modelName, latency);
            }

            ModerationAssessment normalized = normalize(assessment);
            log.info(">>> 审核完成：type={}，decision={}，confidence={}，riskScore={}，最高维度={}，耗时 {} ms",
                    targetType, normalized.decisionEnum(), normalized.confidence(),
                    normalized.maxScore(), normalized.maxDimension(), latency);

            return ModerationOutcome.of(normalized, modelName, latency,
                    usageValue(response, true), usageValue(response, false), usageValue(response, null));
        } catch (RuntimeException exception) {
            long latency = System.currentTimeMillis() - startedAt;
            log.error("内容审核分析失败：type={}，content={}", targetType, abbreviate(content), exception);
            String reason = degradeGuard.classify(exception);
            return ModerationOutcome.degraded(reason,
                    degradeGuard.degrade(reason, exception.getMessage()), modelName, latency);
        }
    }

    /**
     * 从响应里取 token 用量。
     *
     * @param prompt true 取输入、false 取输出、null 取合计
     */
    private Integer usageValue(ChatResponse response, Boolean prompt) {
        if (response == null || response.getMetadata() == null || response.getMetadata().getUsage() == null) {
            return null;
        }
        var usage = response.getMetadata().getUsage();
        if (prompt == null) {
            return usage.getTotalTokens();
        }
        return prompt ? usage.getPromptTokens() : usage.getCompletionTokens();
    }

    private String buildUserPrompt(ModerationTargetType targetType, String content) {
        return """
                内容类型：%s
                内容：
                %s
                """.formatted(targetType.getDisplayName(), truncate(content));
    }

    /**
     * 规整模型输出：把越界的分数钳制到 0~1。
     *
     * <p>防御模型偶尔返回 1.2、-0.1 这类值 —— 它们会污染综合分与阈值判定，
     * 而写入 DECIMAL(4,3) 列时也不该因为脏数据失败。
     */
    private ModerationAssessment normalize(ModerationAssessment raw) {
        return new ModerationAssessment(
                raw.decision(),
                clamp(raw.confidence()),
                clamp(raw.harmfulScore()), raw.harmfulReason(),
                clamp(raw.promotionScore()), raw.promotionReason(),
                clamp(raw.fraudScore()), raw.fraudReason(),
                clamp(raw.spamScore()), raw.spamReason(),
                clamp(raw.sensitiveScore()), raw.sensitiveReason(),
                raw.summary());
    }

    private double clamp(double value) {
        if (Double.isNaN(value)) {
            return 1d;
        }
        return Math.max(0d, Math.min(1d, value));
    }

    /** 超长内容截断，避免单次请求 token 失控（正文只用于风险判断，不需要全文）。 */
    private String truncate(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= maxContentChars ? content : content.substring(0, maxContentChars) + "…";
    }

    private String abbreviate(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= 60 ? content : content.substring(0, 60) + "…";
    }
}

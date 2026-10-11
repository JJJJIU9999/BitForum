package com.bitforum.ai.usage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.bitforum.ai.entity.AiUsageStat;
import com.bitforum.ai.mapper.AiUsageStatMapper;

/**
 * AI 用量记录器（M18）。
 *
 * <p><b>唯一的写入入口</b>：由 {@code TraceRecorder} 在轨迹收尾时调用。
 * 放在同一条链路上是刻意的 —— M18 决策 Q1 要解决的就是"审核与推荐理由的 token
 * 从来没有被记录过"这个问题；如果用量还是各 Agent 自己写，同样的遗漏会再次发生。
 *
 * <p>与轨迹一样遵守"副产品原则"：写库失败只记日志，绝不影响 AI 调用本身的返回。
 *
 * <p><b>成本是估算</b>：单价来自配置（默认按 DeepSeek 公开价格填写），
 * 只用于"哪个 Agent 更贵、今天花了多少"这类相对判断，不作为账单依据。
 */
@Service
public class AiUsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(AiUsageRecorder.class);

    private static final BigDecimal MILLION = new BigDecimal("1000000");

    private final AiUsageStatMapper usageMapper;
    private final BigDecimal inputPricePerMillion;
    private final BigDecimal outputPricePerMillion;

    public AiUsageRecorder(AiUsageStatMapper usageMapper,
                           @Value("${bitforum.ai.usage.input-price-per-million:2.0}")
                           BigDecimal inputPricePerMillion,
                           @Value("${bitforum.ai.usage.output-price-per-million:8.0}")
                           BigDecimal outputPricePerMillion) {
        this.usageMapper = usageMapper;
        this.inputPricePerMillion = inputPricePerMillion;
        this.outputPricePerMillion = outputPricePerMillion;
    }

    /**
     * 记录一次调用的用量。
     *
     * @param result 见 {@link AiUsageStat} 的 RESULT_* 常量
     */
    public void record(String traceId, String scene, String agentType, Long userId, String model,
                       Integer promptTokens, Integer completionTokens, Integer totalTokens,
                       Integer latencyMs, String result) {
        int prompt = promptTokens == null ? 0 : promptTokens;
        int completion = completionTokens == null ? 0 : completionTokens;
        int total = totalTokens == null ? prompt + completion : totalTokens;

        AiUsageStat stat = new AiUsageStat();
        stat.setTraceId(traceId);
        stat.setScene(scene);
        stat.setAgentType(agentType);
        stat.setUserId(userId);
        stat.setModel(model);
        stat.setPromptTokens(prompt);
        stat.setCompletionTokens(completion);
        stat.setTotalTokens(total);
        stat.setLatencyMs(latencyMs);
        stat.setResult(result);
        stat.setEstimatedCost(estimateCost(prompt, completion));
        stat.setStatDate(LocalDate.now());
        try {
            usageMapper.insert(stat);
        } catch (RuntimeException exception) {
            log.warn("AI 用量写入失败（不影响主流程）：traceId={}, scene={}", traceId, scene, exception);
        }
    }

    /**
     * 按配置单价估算单次调用费用。
     *
     * <p>输入与输出单价分开：两者的量级和价格都不同，用一个混合单价会让"长上下文问答"
     * 与"短输入长输出"这两种负载看起来一样贵。
     */
    public BigDecimal estimateCost(Integer promptTokens, Integer completionTokens) {
        BigDecimal prompt = BigDecimal.valueOf(promptTokens == null ? 0 : promptTokens);
        BigDecimal completion = BigDecimal.valueOf(completionTokens == null ? 0 : completionTokens);
        return prompt.multiply(inputPricePerMillion)
                .add(completion.multiply(outputPricePerMillion))
                .divide(MILLION, 6, RoundingMode.HALF_UP);
    }

    public BigDecimal inputPricePerMillion() {
        return inputPricePerMillion;
    }

    public BigDecimal outputPricePerMillion() {
        return outputPricePerMillion;
    }
}

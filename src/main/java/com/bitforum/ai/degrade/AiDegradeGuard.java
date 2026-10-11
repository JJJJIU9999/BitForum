package com.bitforum.ai.degrade;

import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.bitforum.ai.trace.TraceDegradeReason;
import com.bitforum.ai.trace.TraceRecorder;

/**
 * AI 降级的统一入口（M18）。
 *
 * <p><b>它解决什么问题</b>：M13-M17 期间四个 Agent **各写各的降级** ——
 * 各自的 try/catch、各自的兜底文案、各自判断"模型是不是没启用"。
 * 结果是同一个故障（比如模型超时）在问答里显示一句、在审核里记另一句、
 * 在推荐里干脆只体现在一个 boolean 上。M18 验收第 3 条要的是
 * "AI 不可用时全站 AI 功能有**统一**的降级表现，且用户能看懂**为什么**降级"，
 * 这件事靠四个 Agent 各自自觉是做不到的。
 *
 * <p><b>统一的三件事</b>：
 * <ol>
 *   <li><b>原因码</b>：由 {@link #classify(Throwable)} 把异常归类成有限的原因码
 *       （超时 / 错误），"AI 未启用""返回空内容"等由调用方显式给出 ——
 *       原因码可枚举，才谈得上统计与对齐；</li>
 *   <li><b>文案</b>：同一原因码在任何 Agent、任何场景下都是同一句话
 *       （{@link TraceDegradeReason#userMessage}）；</li>
 *   <li><b>记录</b>：降级一律写进当前执行轨迹（{@link TraceRecorder#degrade}），
 *       管理端因此能看到"这次为什么降级"，而不是只知道"结果有点怪"。</li>
 * </ol>
 *
 * <p><b>它不是"重试"或"熔断"</b>：本项目不做熔断器（没有多实例与流量规模，
 * 引入熔断只会增加不可解释的状态）；Guard 只负责"失败时怎么如实地说、统一地退"。
 */
@Service
public class AiDegradeGuard {

    private static final Logger log = LoggerFactory.getLogger(AiDegradeGuard.class);

    private final TraceRecorder traceRecorder;

    public AiDegradeGuard(TraceRecorder traceRecorder) {
        this.traceRecorder = traceRecorder;
    }

    // ==================== 记录 + 文案 ====================

    /**
     * 记录一次降级并返回统一的用户可见文案。
     *
     * @param reason 原因码，取值见 {@link TraceDegradeReason}
     */
    public String degrade(String reason) {
        return degrade(reason, null);
    }

    /**
     * 记录一次降级并返回统一文案。
     *
     * @param detail 追加的排查信息（可为 null）。**不直接展示给用户** ——
     *               轨迹里保留它，用户看到的是按原因码生成的固定文案，
     *               避免把"connection reset"这类内部信息抛给终端用户。
     */
    public String degrade(String reason, String detail) {
        traceRecorder.degrade(reason, detail);
        String message = TraceDegradeReason.userMessage(reason);
        log.debug("AI 降级：reason={}，detail={}", reason, detail);
        return message;
    }

    /** 只取统一文案，不记轨迹（调用方已经记过时用）。 */
    public String message(String reason) {
        return TraceDegradeReason.userMessage(reason);
    }

    // ==================== 包住一次模型调用 ====================

    /**
     * 执行一次可能失败的动作；失败时统一记录降级并交给 {@code onDegrade} 产出返回值。
     *
     * <p>用它替换各 Agent 里"手写 try/catch + 拼降级文案"的重复代码：
     * 异常分类、原因码、轨迹记录都在这里做，调用方只关心"失败时返回什么"。
     *
     * @param action    正常路径（通常是"调模型 + 解析结果"）
     * @param onDegrade 降级路径：入参是已归类的**原因码**，返回该 Agent 自己的降级结果类型
     */
    public <T> T call(Supplier<T> action, Function<String, T> onDegrade) {
        try {
            return action.get();
        } catch (RuntimeException exception) {
            String reason = classify(exception);
            log.warn("AI 调用失败，按统一降级处理：reason={}，message={}", reason, exception.getMessage());
            traceRecorder.degrade(reason, exception.getMessage());
            return onDegrade.apply(reason);
        }
    }

    /**
     * 把异常归类成原因码。
     *
     * <p>只区分"超时"与"其它错误"两档：更细的分类（限流、鉴权失败、额度不足）
     * 需要模型供应商的专用异常类型，而 DeepSeek 的错误信息都以文本返回 ——
     * 强行用字符串匹配去细分只会在供应商改文案时静默失效。
     * 需要更细时，detail 里保留了原始信息，排查时看轨迹即可。
     */
    public String classify(Throwable throwable) {
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth++ < 10) {
            if (current instanceof SocketTimeoutException || current instanceof TimeoutException) {
                return TraceDegradeReason.LLM_TIMEOUT;
            }
            String simpleName = current.getClass().getSimpleName().toLowerCase();
            String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase();
            if (simpleName.contains("timeout") || message.contains("timeout")
                    || message.contains("timed out") || message.contains("超时")) {
                return TraceDegradeReason.LLM_TIMEOUT;
            }
            current = current.getCause();
        }
        return TraceDegradeReason.LLM_ERROR;
    }
}

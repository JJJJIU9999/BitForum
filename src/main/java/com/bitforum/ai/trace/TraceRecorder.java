package com.bitforum.ai.trace;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bitforum.ai.entity.AiExecutionTrace;
import com.bitforum.ai.entity.AiUsageStat;
import com.bitforum.ai.mapper.AiExecutionTraceMapper;
import com.bitforum.ai.usage.AiUsageRecorder;

/**
 * 执行轨迹记录器（M18 核心组件）。
 *
 * <p><b>职责</b>：把一次 AI 调用从"路由"到"最后一步"的全过程记进
 * {@code ai_execution_trace}，包括每步耗时与 token；跨线程/跨进程的异步段
 * 用同一个 traceId 续写同一行。
 *
 * <p><b>三条硬约束（都来自项目已踩过的坑）</b>：
 * <ol>
 *   <li><b>轨迹是副产品，绝不能影响主流程</b>：所有落库操作都吞掉异常、只记日志。
 *       这与 M16「{@code action_reason} 只是记录」、M17「推荐记录是副产品」同一原则。
 *       写轨迹失败时，用户该拿到的回答必须照常返回。</li>
 *   <li><b>没有会话时静默跳过</b>：不是每次 AI 行为都需要轨迹（例如未接入埋点的旧路径），
 *       {@link #step} 在无会话时什么都不做，调用方不必判空。</li>
 *   <li><b>跨线程必须显式交接</b>：T13 实测证明 ThreadLocal 不包装就传不进线程池，
 *       因此异步链路用 {@link #startDetached}（新建但不绑定线程）与
 *       {@link #attach}（按 traceId 在目标线程挂载）两个显式入口，
 *       而不是指望上下文自动传播。</li>
 * </ol>
 *
 * <p><b>落库形态</b>：开始写一行 {@code RUNNING}，结束 {@code UPDATE} 同一行。
 * 这样即使应用在异步段中途重启，管理端也能看到"哪次调用没跑完"（与 M17
 * {@code ai_insight_report} 的 PENDING 思路一致）。
 */
@Service
public class TraceRecorder {

    private static final Logger log = LoggerFactory.getLogger(TraceRecorder.class);

    /** 单条 detail 的最大长度：步骤是给人看的摘要，不是原始日志，超长会撑爆 steps JSON。 */
    private static final int MAX_DETAIL_LENGTH = 500;

    private final AiExecutionTraceMapper traceMapper;
    private final ObjectMapper objectMapper;
    private final AiUsageRecorder usageRecorder;

    public TraceRecorder(AiExecutionTraceMapper traceMapper, ObjectMapper objectMapper,
                         AiUsageRecorder usageRecorder) {
        this.traceMapper = traceMapper;
        this.objectMapper = objectMapper;
        this.usageRecorder = usageRecorder;
    }

    // ==================== 开始 ====================

    /**
     * 开始一条轨迹并绑定到当前线程（同步链路用）。
     *
     * @param scene         场景，取值见 {@link AiExecutionTrace} 的 SCENE_*
     * @param agentType     Agent 类型（QA / MODERATION / ANALYST / RECOMMEND）
     * @param userId        触发者；匿名或系统触发传 null
     * @param conversationId AI 会话 id；非对话场景传 null
     * @param refType       业务引用类型；无用引用传 null
     * @param refId         业务引用 id
     */
    public TraceSession start(String scene, String agentType, Long userId, Long conversationId,
                              String refType, Long refId) {
        TraceSession session = createSession(scene, agentType, userId, conversationId, refType, refId);
        TraceContext.set(session);
        return session;
    }

    /**
     * 开始一条轨迹但**不绑定线程**：用于"父线程发起、子线程执行"的异步链路。
     *
     * <p>父线程只负责把 traceId 交给子线程（MQ 消息头 / 任务闭包），
     * 由子线程 attach 后自行收尾，因此父线程不能持有上下文，否则会在请求结束后泄漏。
     */
    public TraceSession startDetached(String scene, String agentType, Long userId, Long conversationId,
                                      String refType, Long refId) {
        return createSession(scene, agentType, userId, conversationId, refType, refId);
    }

    private TraceSession createSession(String scene, String agentType, Long userId, Long conversationId,
                                       String refType, Long refId) {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        TraceSession session = new TraceSession(traceId, scene, agentType, userId, conversationId,
                refType, refId, false, null, System.currentTimeMillis());

        AiExecutionTrace entity = new AiExecutionTrace();
        entity.setTraceId(traceId);
        entity.setScene(scene);
        entity.setAgentType(agentType);
        entity.setUserId(userId);
        entity.setConversationId(conversationId);
        entity.setRefType(refType);
        entity.setRefId(refId);
        entity.setStatus(AiExecutionTrace.STATUS_RUNNING);
        entity.setStepCount(0);
        try {
            traceMapper.insert(entity);
        } catch (RuntimeException exception) {
            log.warn("执行轨迹写入失败（不影响主流程）：traceId={}, scene={}", traceId, scene, exception);
        }
        return session;
    }

    /**
     * 按 traceId 把既有轨迹挂载到当前线程（异步链路续写用）。
     *
     * <p>典型用法：MQ 消费者从消息头拿到 traceId / 洞察线程池任务携带 traceId，
     * 在子线程里 attach 后照常 {@link #step} 与 {@link #finishSuccess}，
     * 最终 UPDATE 的是**同一行**，因此管理端看到的是一条完整链路，
     * 而不是两段互不相干的记录。
     *
     * @return 挂载成功返回会话；traceId 为空或记录不存在时返回 null（调用方自行决定是否新建）
     */
    public TraceSession attach(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return null;
        }
        AiExecutionTrace entity;
        try {
            entity = traceMapper.selectOne(new LambdaQueryWrapper<AiExecutionTrace>()
                    .eq(AiExecutionTrace::getTraceId, traceId)
                    .last("LIMIT 1"));
        } catch (RuntimeException exception) {
            log.warn("执行轨迹挂载失败（不影响主流程）：traceId={}", traceId, exception);
            return null;
        }
        if (entity == null) {
            return null;
        }

        TraceSession session = new TraceSession(traceId, entity.getScene(), entity.getAgentType(),
                entity.getUserId(), entity.getConversationId(), entity.getRefType(), entity.getRefId(),
                true, parseSteps(entity.getSteps()), startMillis(entity.getCreateTime()));
        TraceContext.set(session);
        return session;
    }

    /**
     * 按 traceId 把既有轨迹挂载到当前线程；挂不上就新建一条（异步链路的统一入口）。
     *
     * <p>为什么需要它：MQ 消费者与线程池任务都会遇到两种情形 ——
     * 上游确实留了 traceId（续写同一行），或者这条消息来自更早的版本/其它触发点（没有 traceId）。
     * 让每个调用方各写一遍"有则 attach、无则 start"的分支，很容易写漏一处。
     *
     * @param traceId 上游传来的 traceId，可为 null
     */
    public TraceSession attachOrStart(String traceId, String scene, String agentType, Long userId,
                                      Long conversationId, String refType, Long refId) {
        TraceSession attached = attach(traceId);
        if (attached != null) {
            return attached;
        }
        return start(scene, agentType, userId, conversationId, refType, refId);
    }

    // ==================== 记录步骤 ====================

    /** 记一步（无 token）。 */
    public void step(String type, String name, String detail) {
        step(type, name, detail, 0L, null);
    }

    /** 记一步（带耗时与 token）；当前线程没有轨迹时静默跳过。 */
    public void step(String type, String name, String detail, long latencyMs, Integer totalTokens) {
        TraceSession session = TraceContext.current();
        if (session == null) {
            return;
        }
        session.addStep(type, name, truncate(detail), Math.max(0L, latencyMs), totalTokens);
    }

    /**
     * 用一次调用的实际耗时记一步：调用方只需传开始时刻，避免每处都写
     * {@code System.currentTimeMillis() - startedAt} 这种易错重复代码。
     */
    public void stepSince(String type, String name, String detail, long startedAtMillis, Integer totalTokens) {
        step(type, name, detail, System.currentTimeMillis() - startedAtMillis, totalTokens);
    }

    // ==================== 结束 ====================

    /**
     * 标记本次调用降级，并记一步 {@code DEGRADE}。
     *
     * <p>降级可以发生在任意一层（检索失败、模型不可用、返回空内容），
     * 各层只负责如实标记，最终状态由收尾时统一决定 —— 这样"全站统一的降级表现"
     * 才是结构上成立的，而不是靠四个 Agent 各自自觉。
     */
    public void degrade(String reason, String detail) {
        TraceSession session = TraceContext.current();
        if (session == null) {
            return;
        }
        // 轨迹会被管理端直接展示，不能把 provider 的 URL、连接错误或认证文本当作 message 落库。
        // 原始 detail 只写日志；对外与轨迹均使用可枚举原因码的统一文案。
        String userMessage = TraceDegradeReason.userMessage(reason);
        if (detail != null && !detail.isBlank()) {
            log.debug("AI 降级内部详情：reason={}，detail={}", reason, detail);
        }
        session.markDegraded(reason, userMessage);
        step(TraceStepType.DEGRADE, reason == null ? TraceDegradeReason.LLM_ERROR : reason, userMessage);
    }

    /**
     * 收尾：状态由会话上是否标记过降级决定（标过 → {@code DEGRADED}，否则 {@code SUCCESS}）。
     *
     * <p>这是同步链路（对话、推荐）的常规收尾方式，调用方不需要自己判断状态。
     */
    public void finish(String model, Integer promptTokens, Integer completionTokens, Integer totalTokens) {
        TraceSession session = TraceContext.current();
        if (session == null) {
            return;
        }
        if (session.degraded()) {
            complete(session, AiExecutionTrace.STATUS_DEGRADED, model, promptTokens, completionTokens,
                    totalTokens, session.degradeReason(), session.degradeMessage());
            return;
        }
        complete(session, AiExecutionTrace.STATUS_SUCCESS, model, promptTokens, completionTokens,
                totalTokens, null, null);
    }

    /** 正常结束。 */
    public void finishSuccess(String model, Integer promptTokens, Integer completionTokens,
                              Integer totalTokens) {
        complete(TraceContext.current(), AiExecutionTrace.STATUS_SUCCESS, model,
                promptTokens, completionTokens, totalTokens, null, null);
    }

    /**
     * 降级结束：记录原因码与用户可读说明。
     *
     * <p>message 传空时按原因码生成默认文案（{@link TraceDegradeReason#userMessage}），
     * 保证管理端永远能回答"为什么降级"。
     */
    public void finishDegraded(String reason, String message, String model,
                               Integer promptTokens, Integer completionTokens, Integer totalTokens) {
        complete(TraceContext.current(), AiExecutionTrace.STATUS_DEGRADED, model,
                promptTokens, completionTokens, totalTokens, reason,
                message == null || message.isBlank() ? TraceDegradeReason.userMessage(reason) : message);
    }

    /** 异常结束。 */
    public void finishFailed(String message) {
        complete(TraceContext.current(), AiExecutionTrace.STATUS_FAILED, null, null, null, null, null,
                truncate(message));
    }

    /** 结束指定会话（用于拿不到 ThreadLocal 的收尾路径，例如异步任务的 finally）。 */
    public void finishSession(TraceSession session, String status, String model, Integer promptTokens,
                              Integer completionTokens, Integer totalTokens, String degradeReason,
                              String message) {
        complete(session, status, model, promptTokens, completionTokens, totalTokens, degradeReason, message);
    }

    private void complete(TraceSession session, String status, String model, Integer promptTokens,
                          Integer completionTokens, Integer totalTokens, String degradeReason, String message) {
        if (session == null) {
            return;
        }
        int latencyMs = (int) Math.min(Integer.MAX_VALUE, session.elapsedMillis());
        try {
            AiExecutionTrace update = new AiExecutionTrace();
            update.setStatus(status);
            update.setModel(model);
            update.setPromptTokens(promptTokens);
            update.setCompletionTokens(completionTokens);
            update.setTotalTokens(totalTokens);
            update.setLatencyMs(latencyMs);
            update.setStepCount(session.stepCount());
            update.setSteps(writeSteps(session.steps()));
            // route 字段不是单独维护的：它就是轨迹里第一条 ROUTE 步骤的说明。
            // 实体单独留一列是为了列表页一眼看到"分派给谁、有没有回退"，不必解析 JSON。
            update.setRoute(session.steps().stream()
                    .filter(step -> TraceStepType.ROUTE.equals(step.type()))
                    .map(TraceStep::detail)
                    .findFirst()
                    .orElse(null));
            update.setDegradeReason(degradeReason);
            update.setMessage(truncate(message));

            traceMapper.update(update, new LambdaQueryWrapper<AiExecutionTrace>()
                    .eq(AiExecutionTrace::getTraceId, session.traceId()));
        } catch (RuntimeException exception) {
            log.warn("执行轨迹收尾写入失败（不影响主流程）：traceId={}", session.traceId(), exception);
        } finally {
            // 只有当前线程正在记录这条轨迹时才清理，避免把别的会话清掉
            TraceSession current = TraceContext.current();
            if (current != null && current.traceId().equals(session.traceId())) {
                TraceContext.clear();
            }
        }

        // M18 用量明细：与轨迹写入**各自独立**。两者都是副产品，
        // 任何一方失败都不应连累另一方，更不应影响 AI 调用本身的返回。
        // 放在这里还有一个好处：四个 Agent 都必然经过 complete，
        // 因此不可能出现"某个 Agent 忘了记 token"（这正是 Q1 选统一埋点的原因）。
        usageRecorder.record(session.traceId(), session.scene(), session.agentType(), session.userId(),
                model, promptTokens, completionTokens, totalTokens, latencyMs, usageResultOf(status));
    }

    /** 轨迹状态 → 用量结果码。 */
    private String usageResultOf(String status) {
        if (AiExecutionTrace.STATUS_SUCCESS.equals(status)) {
            return AiUsageStat.RESULT_SUCCESS;
        }
        if (AiExecutionTrace.STATUS_DEGRADED.equals(status)) {
            return AiUsageStat.RESULT_DEGRADED;
        }
        return AiUsageStat.RESULT_FAILED;
    }

    /** 清理当前线程上下文（异常路径兜底）。 */
    public void clear() {
        TraceContext.clear();
    }

    // ==================== 工具装配 ====================

    /**
     * 把 M14 的工具对象数组包装成"带轨迹采集"的 {@code ToolCallback} 数组。
     *
     * <p>T12 实测（findings.md 6.18）：Spring AI 的工具执行循环在 provider 内部完成，
     * 最终响应里已经没有工具调用链（{@code hasToolCalls()=false}），
     * 因此**只能在装配处包一层**才能拿到"工具名 / 入参 / 返回值 / 单步耗时"。
     * 实测也确认了传入的 ToolCallback 实例会被原样使用，所以包装是有效的。
     *
     * @param tools 工具对象（带 {@code @Tool} 注解的 bean）
     * @return 包装后的回调；tools 为空时返回空数组，调用方应跳过 {@code toolCallbacks(...)}
     */
    public ToolCallback[] wrapTools(Object... tools) {
        if (tools == null || tools.length == 0) {
            return new ToolCallback[0];
        }
        ToolCallback[] raw = ToolCallbacks.from(tools);
        ToolCallback[] wrapped = new ToolCallback[raw.length];
        for (int i = 0; i < raw.length; i++) {
            wrapped[i] = new TracingToolCallback(raw[i], this);
        }
        return wrapped;
    }

    // ==================== 内部工具 ====================

    private String writeSteps(List<TraceStep> steps) {
        try {
            return objectMapper.writeValueAsString(steps);
        } catch (Exception exception) {
            log.warn("执行轨迹步骤序列化失败：{}", exception.getMessage());
            return null;
        }
    }

    private List<TraceStep> parseSteps(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<TraceStep>>() {
            });
        } catch (Exception exception) {
            log.warn("执行轨迹步骤反序列化失败：{}", exception.getMessage());
            return List.of();
        }
    }

    private long startMillis(LocalDateTime createTime) {
        if (createTime == null) {
            return System.currentTimeMillis();
        }
        return createTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= MAX_DETAIL_LENGTH ? text : text.substring(0, MAX_DETAIL_LENGTH) + "…";
    }
}

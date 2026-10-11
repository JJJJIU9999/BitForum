package com.bitforum.ai.trace;

/**
 * 轨迹步骤类型（M18）。
 *
 * <p>步骤类型是**给前端渲染时间线用的图标/配色依据**，也是"这一步在干什么"的机器可读标签。
 * 刻意用一组有限的常量而不是自由字符串：前端按类型决定图标，自由字符串会导致每加一个 Agent
 * 前端就要改一次。
 */
public final class TraceStepType {

    /** 路由：把请求分派给哪个 Agent（含回退说明） */
    public static final String ROUTE = "ROUTE";
    /** 检索：RAG 知识库检索（命中片段数、相似度门槛） */
    public static final String RETRIEVE = "RETRIEVE";
    /** 模型调用：一次 LLM 请求（模型名、token、耗时） */
    public static final String LLM_CALL = "LLM_CALL";
    /** 工具调用：模型请求执行某个工具（入参、返回值、单步耗时） */
    public static final String TOOL_CALL = "TOOL_CALL";
    /** 召回：推荐的多路召回（各路候选数） */
    public static final String RECALL = "RECALL";
    /** 融合：RRF 融合与 Top-N 截断 */
    public static final String FUSION = "FUSION";
    /** 落库/收尾：把结果写回业务表 */
    public static final String PERSIST = "PERSIST";
    /** 异步交接：把任务交给线程池或 MQ */
    public static final String ASYNC = "ASYNC";
    /** 降级：记录一次降级事件及其原因 */
    public static final String DEGRADE = "DEGRADE";

    private TraceStepType() {
    }
}

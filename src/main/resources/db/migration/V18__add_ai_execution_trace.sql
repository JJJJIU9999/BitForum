-- Flyway 第 18 个迁移脚本：AI 执行轨迹表（M18）
-- 约定说明：
--   1. 本脚本不修改任何历史迁移 V1-V17。
--   2. 表名沿用项目现有小写下划线风格，主键统一 BIGINT AUTO_INCREMENT。
--   3. 与 user(id) / ai_conversation(id) / article(id) 的关联只加索引、不建物理外键（与既有表一致）。
--
-- 【这张表解决什么问题】M18 的验收第 1 条："任意一次 AI 调用都能查到完整执行轨迹
--   （路由 → 工具调用链 → 每步耗时 → token）"。在此之前：
--     · token 与整体耗时散落在 ai_message / ai_insight_report；
--     · **工具调用链与每步耗时从未被采集**（T12 实测：工具循环在 provider 内部完成，
--       业务代码拿到的最终响应里 hasToolCalls()=false，链已经消失）；
--     · 异步链路（洞察线程池、索引/审核 MQ）与触发它的请求之间没有任何关联字段。
--   本表把这些统一到"一行 = 一次 AI 调用"。
--
-- 【为什么是单表 + steps JSON，而不是主表/步骤表两张】
--   见 m18-decision-brief.md Q2：轨迹的用途是"查得到 + 演示得出来"，
--   一次调用写一行、异步链路回来 UPDATE 同一行，写入次数最少；
--   前端取一行即可渲染整条时间线。代价是不能按工具名直接做 SQL 统计
--   （需要 JSON 解析），这在 M18 的验收范围内可以接受。
--
-- 【trace_id 是关联异步链路的唯一钥匙】
--   同步链路：请求线程生成 trace_id（TraceContext 的 ThreadLocal）；
--   跨进程：MQ 消息头携带 trace_id（T13 实测可用，且不需要改任何消息体类）；
--   跨线程：提交任务时捕获、执行前恢复（T13 实测：不包装必然丢失）。
--   异步段结束时按 trace_id **UPDATE 同一行**，而不是另写一条孤立的记录。

CREATE TABLE IF NOT EXISTS ai_execution_trace (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,

    -- ==================== 身份与关联 ====================
    -- 一次 AI 调用的全局唯一 id（32 位十六进制，无连字符）
    trace_id VARCHAR(64) NOT NULL,
    -- 触发场景：CHAT（AI 助手对话）/ MODERATION（内容审核）/
    --           INSIGHT（运营洞察）/ RECOMMEND（推荐理由）
    scene VARCHAR(32) NOT NULL,
    -- Agent 类型：QA / MODERATION / ANALYST / RECOMMEND
    agent_type VARCHAR(32) NOT NULL,
    -- 触发者；匿名访客或系统触发为空
    user_id BIGINT NULL,
    -- 关联的 AI 会话（仅对话场景）
    conversation_id BIGINT NULL,
    -- 通用业务引用：审核对象（ARTICLE / COMMENT）或推荐的来源文章
    -- 刻意不拆成 target_type/target_id + source_article_id 两组列：
    -- 轨迹的定位是"这次调用针对什么"，一组通用引用足够，且新场景不需要改表结构。
    ref_type VARCHAR(32) NULL,
    ref_id BIGINT NULL,

    -- ==================== 执行结果 ====================
    -- RUNNING（进行中，异步段还没回来）/ SUCCESS（正常完成）/
    -- DEGRADED（走了降级链路，主流程仍可用）/ FAILED（异常）
    status VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
    -- 路由结果的说明，例如"按会话 agent_type 命中 QA""未找到 Agent 实现，回退 QA"
    route VARCHAR(128) NULL,
    model VARCHAR(64) NULL,

    -- ==================== 用量 ====================
    prompt_tokens INT NULL,
    completion_tokens INT NULL,
    total_tokens INT NULL,
    -- 本次调用的端到端耗时（毫秒）：同步链路 = 请求内耗时；
    -- 异步链路 = 触发到异步段结束的总耗时
    latency_ms INT NULL,

    -- ==================== 步骤明细 ====================
    -- 步骤条数（冗余字段：列表页不必解析 JSON 就能展示"N 步"）
    step_count INT NOT NULL DEFAULT 0,
    -- 步骤数组 JSON：[{seq,type,name,detail,latencyMs,totalTokens}]
    -- 一次调用的完整链路都在这里，顺序即执行顺序
    steps TEXT NULL,

    -- ==================== 降级信息（M18 验收第 3 条）====================
    -- 降级原因码（机器可读，取值见 TraceResult.Reason）：LLM_DISABLED / LLM_TIMEOUT /
    -- LLM_ERROR / EMPTY_RESPONSE / RETRIEVE_FAILED 等
    degrade_reason VARCHAR(64) NULL,
    -- 面向人的说明：前端直接展示"为什么这次降级了"
    message VARCHAR(500) NULL,

    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    -- trace_id 唯一：既防重复插入，也是异步链路按 id 续写（UPDATE）的依据
    UNIQUE KEY uk_aiet_trace_id (trace_id),
    -- 轨迹页默认视图：按场景过滤 + 时间倒序
    KEY idx_aiet_scene_time (scene, create_time),
    -- 查某个用户的 AI 调用历史
    KEY idx_aiet_user_time (user_id, create_time),
    -- 排查"卡在 RUNNING 的调用"与按状态筛选
    KEY idx_aiet_status_time (status, update_time)
);

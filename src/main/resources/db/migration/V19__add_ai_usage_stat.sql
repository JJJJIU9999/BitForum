-- Flyway 第 19 个迁移脚本：AI 用量统计表（M18）
-- 约定说明：
--   1. 本脚本不修改任何历史迁移 V1-V18。
--   2. 表名沿用项目现有小写下划线风格，主键统一 BIGINT AUTO_INCREMENT。
--   3. 与 user(id) / ai_execution_trace(trace_id) 的关联只加索引、不建物理外键（与既有表一致）。
--
-- 【为什么需要这张表，而不是直接从既有表聚合】
--   M18 决策 Q1（见 m18-decision-brief.md）实测确认了缺口：
--     · ai_message（QA）与 ai_insight_report（洞察）有 token；
--     · **ai_recommend_log 与 ai_moderation_record 根本没有 token 字段** ——
--       也就是说"按 用户 / Agent / 天 聚合 token 与费用"这件事，只靠既有表
--       只能覆盖 2 个 Agent，MODERATION 与 RECOMMEND 永远是空的。
--   因此本表是**统一埋点的明细表**：一行 = 一次真实的大模型调用，
--   四个 Agent 都从同一处写入（TraceRecorder 收尾时），顺带把审核与推荐理由的
--   token 补采上来。
--
-- 【为什么粒度是"一次调用"而不是"一天的汇总"】
--   明细可以聚合出任何维度（天 / Agent / 用户 / 场景），而汇总表只能回答它被设计来回答的问题；
--   而且"能算出单次成本"（M18 验收第 2 条）本来就要求保留单次记录。
--   数据量按 AI 调用次数增长，与业务数据量同一量级，不需要额外的汇总表。
--
-- 【成本是估算】
--   单价放在 application.yml（bitforum.ai.usage.*），按公开价格填默认值。
--   价格会变，因此这里存的 estimated_cost 只是**当时按配置算出来的估算值**，
--   字段名刻意带 estimated 前缀，避免日后被当成账单数据。

CREATE TABLE IF NOT EXISTS ai_usage_stat (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,

    -- ==================== 关联 ====================
    -- 对应的执行轨迹 id；同一 trace 的同步段与异步段只写一行用量
    trace_id VARCHAR(64) NULL,
    -- 场景：CHAT / MODERATION / INSIGHT / RECOMMEND
    scene VARCHAR(32) NOT NULL,
    -- Agent 类型：QA / MODERATION / ANALYST / RECOMMEND
    agent_type VARCHAR(32) NOT NULL,
    -- 触发者；匿名访客或系统触发为空（按用户聚合时用得上）
    user_id BIGINT NULL,
    -- 产生用量的模型标识
    model VARCHAR(64) NULL,

    -- ==================== 用量 ====================
    prompt_tokens INT NOT NULL DEFAULT 0,
    completion_tokens INT NOT NULL DEFAULT 0,
    total_tokens INT NOT NULL DEFAULT 0,
    latency_ms INT NULL,
    -- 本次调用的结果：SUCCESS / DEGRADED / FAILED。
    -- 降级与失败同样记录（token 可能已经消耗），但统计时要能区分出来。
    result VARCHAR(16) NOT NULL,
    -- 按配置单价估算的费用（元），保留 6 位小数；单价变动只影响此后写入的行
    estimated_cost DECIMAL(12,6) NOT NULL DEFAULT 0,

    -- ==================== 聚合用 ====================
    -- 统计日期（按自然日）。独立成列而不是靠 create_time 现算：
    -- 按天聚合是最常用的维度，独立列可以直接走索引，也让"跨时区/跨天边界"的语义确定。
    stat_date DATE NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- 按天看趋势
    KEY idx_aius_date (stat_date),
    -- 按用户看消耗（成本归属、异常用量排查）
    KEY idx_aius_user_date (user_id, stat_date),
    -- 按 Agent 看消耗（哪个 Agent 最贵）
    KEY idx_aius_agent_date (agent_type, stat_date),
    -- 按场景看消耗
    KEY idx_aius_scene_date (scene, stat_date),
    -- 由轨迹反查用量
    KEY idx_aius_trace (trace_id)
);

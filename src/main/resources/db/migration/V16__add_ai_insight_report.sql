-- Flyway 第 16 个迁移脚本：AI 运营洞察报告表（M17）
-- 约定说明：
--   1. 本脚本不修改任何历史迁移 V1-V15。
--   2. 表名沿用项目现有小写下划线风格，主键统一 BIGINT AUTO_INCREMENT。
--   3. 与 user(id) 的关联只加索引、不建物理外键（与既有表一致）。
--
-- 【核心设计】报告正文与数据快照分开保存：
--   content       = 模型生成的运营洞察正文；
--   data_snapshot = 生成这份报告时喂给模型的原始统计（JSON）。
--   两者必须同时留存 —— 否则事后无法回答"报告里那个数字当时对不对"。
--   运营数据每天都在变，只存正文等于把结论和依据拆开，复盘时无从核对。
--
-- 【与触发方式解耦】trigger_type 预留 MANUAL / SCHEDULED 两种取值：
--   本模块先实现"管理员手动触发"，表结构对将来的定时生成同样适用，
--   因此触发方式的取舍不需要改动本表。

CREATE TABLE IF NOT EXISTS ai_insight_report (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,

    -- ==================== 报告状态 ====================
    -- PENDING（已受理待生成）/ SUCCESS（已生成）/ FAILED（生成失败）
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    -- 触发方式：MANUAL（管理员手动触发）/ SCHEDULED（定时生成，预留）
    trigger_type VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
    -- 触发人（管理员 id）；将来由定时任务触发时为空
    requested_by BIGINT NULL,

    -- ==================== 报告内容 ====================
    -- 模型生成的运营洞察正文（自然语言，含结论与建议）
    content TEXT NULL,
    -- 生成报告时喂给模型的统计快照（JSON）。与正文同时保存，
    -- 保证"报告里说的数字"与"当时的真实数据"可以逐条对账。
    data_snapshot TEXT NULL,
    -- 数据快照对应的时刻。看板统计是实时聚合的，没有时间区间概念，
    -- 因此只记一个时刻，而不是 start/end 区间。
    data_time DATETIME NULL,

    -- ==================== 运行信息 ====================
    -- 产生该报告的模型标识，便于换模型后识别历史数据
    model VARCHAR(64) NULL,
    prompt_tokens INT NULL,
    completion_tokens INT NULL,
    total_tokens INT NULL,
    latency_ms INT NULL,
    -- 生成失败时的原因（status = FAILED 时填写）
    error_message VARCHAR(500) NULL,

    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    -- 管理台取"最近一份成功报告"与历史列表：按状态过滤 + 时间倒序
    KEY idx_aiir_status_time (status, create_time),
    -- 查某个管理员触发过的报告
    KEY idx_aiir_requester (requested_by, create_time)
);

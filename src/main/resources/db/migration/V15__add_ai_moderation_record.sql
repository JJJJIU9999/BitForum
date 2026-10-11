-- Flyway 第 15 个迁移脚本：AI 内容审核记录表（M16）
-- 约定说明：
--   1. 本脚本不修改任何历史迁移 V1-V14。
--   2. 表名沿用项目现有小写下划线风格，主键统一 BIGINT AUTO_INCREMENT。
--   3. 与 article(id) / comment(id) 的关联只加索引、不建物理外键（与既有表一致）。
--
-- 【核心设计】Decision 与 Action 解耦（M16 实施决策，见 task_plan.md）：
--   decision  = AI 的**判断**（PASS / REVIEW / REJECT），由模型输出；
--   action    = 系统的**动作**（是否自动放行、是否生成待处理），由 Java 按确定性规则决定。
--   两者刻意分开保存：AI 判断可能正确但系统因开关关闭而不动作，
--   也可能 AI 判断失败（ANALYSIS_FAILED）而系统仍走正常人工流程。
--   把两者合并成一个字段会导致"AI 说了什么"和"系统做了什么"无法区分，事后无法复盘。

CREATE TABLE IF NOT EXISTS ai_moderation_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,

    -- ==================== 审核对象 ====================
    -- 对象类型：ARTICLE / COMMENT
    target_type VARCHAR(16) NOT NULL,
    target_id BIGINT NOT NULL,
    -- 内容摘要（截断保存），管理台列表直接展示，无需回查业务表
    target_preview VARCHAR(200) NOT NULL,
    -- 内容作者，便于管理台定位与统计
    author_id BIGINT NULL,

    -- ==================== AI 判断（模型输出） ====================
    -- 三档判断：PASS（放行）/ REVIEW（转人工）/ REJECT（拒绝）
    decision VARCHAR(16) NOT NULL,
    -- 模型对自身判断的置信度 0~1
    confidence DECIMAL(4,3) NOT NULL,
    -- 五个风险维度评分 0~1，越高越可疑。
    -- 刻意用固定列而不是 JSON：T7 实测发现让模型输出"维度列表"时数量不稳定（4/5/6 个都出现过），
    -- 固定字段才能在数据库层面强制五维齐全。
    harmful_score DECIMAL(4,3) NOT NULL,
    promotion_score DECIMAL(4,3) NOT NULL,
    fraud_score DECIMAL(4,3) NOT NULL,
    spam_score DECIMAL(4,3) NOT NULL,
    sensitive_score DECIMAL(4,3) NOT NULL,
    -- 各维度的判定理由（模型给出），排查误判时使用
    harmful_reason VARCHAR(255) NULL,
    promotion_reason VARCHAR(255) NULL,
    fraud_reason VARCHAR(255) NULL,
    spam_reason VARCHAR(255) NULL,
    sensitive_reason VARCHAR(255) NULL,
    -- 模型给出的整体结论摘要
    summary VARCHAR(500) NULL,

    -- ==================== 系统计算（确定性，非模型输出） ====================
    -- 综合风险分 = 五维最大值。刻意不用模型给的综合分：
    -- 模型自评分数不可复现，而阈值判定必须是确定性的。
    risk_score DECIMAL(4,3) NOT NULL,
    -- 风险最高的维度名与分值，便于管理台一眼看出问题类型
    max_dimension VARCHAR(16) NOT NULL,
    max_dimension_score DECIMAL(4,3) NOT NULL,

    -- ==================== 系统动作（与 AI 判断解耦） ====================
    -- 动作类型：AUTO_APPROVED / NO_ACTION / PENDING_REVIEW / HIGH_PRIORITY_REVIEW / ANALYSIS_FAILED
    action VARCHAR(32) NOT NULL,
    -- 采取该动作的原因，例如"判定 PASS 且置信度 0.96 ≥ 阈值 0.90 且开关开启"
    action_reason VARCHAR(255) NULL,
    -- 待处理优先级：0 无需处理 / 1 普通 / 2 高优先（REJECT）
    priority TINYINT NOT NULL DEFAULT 0,
    -- 是否已被管理员处理
    handled TINYINT NOT NULL DEFAULT 0,
    handled_by BIGINT NULL,
    handled_time DATETIME NULL,

    -- ==================== 运行信息 ====================
    -- 产生该判断的模型标识，便于换模型后识别历史数据
    model VARCHAR(64) NULL,
    latency_ms INT NULL,
    -- 分析失败时的原因（此时 decision 记录为 REVIEW 由人工兜底）
    error_message VARCHAR(255) NULL,

    -- ==================== 人工反馈闭环 ====================
    -- 管理员标记：CORRECT（AI 判断正确）/ WRONG（判断错误）
    feedback VARCHAR(16) NULL,
    feedback_by BIGINT NULL,
    feedback_time DATETIME NULL,

    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    -- 按对象查历史审核记录
    KEY idx_aimod_target (target_type, target_id),
    -- 管理台待处理列表：未处理 + 高优先在前 + 按时间倒序
    KEY idx_aimod_pending (handled, priority, create_time),
    -- 按判断结果统计（评测与看板）
    KEY idx_aimod_decision (decision, create_time)
);

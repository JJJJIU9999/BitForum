-- Flyway 第 13 个迁移脚本：AI Agent 模块的会话与消息表（M13）
-- 约定说明：
--   1. 本脚本不修改任何历史迁移 V1-V12。
--   2. 表名沿用项目现有小写下划线风格，主键统一 BIGINT AUTO_INCREMENT。
--   3. 与 user_info(id) / article(id) 的关联只加索引，不建物理外键：
--      与 M1-M12 既有表设计保持一致，避免级联删除影响业务数据。

-- AI 会话表：一次对话上下文对应一行，由用户在前端新建会话时创建
CREATE TABLE IF NOT EXISTS ai_conversation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    title VARCHAR(100) NOT NULL,
    -- Agent 类型：QA / MODERATION / ANALYST / RECOMMEND，M13 只使用 QA
    agent_type VARCHAR(32) NOT NULL DEFAULT 'QA',
    message_count INT NOT NULL DEFAULT 0,
    -- 该会话累计消耗的 Token，用于 M18 的成本统计
    total_tokens BIGINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    -- 用户查看自己的会话列表：按用户过滤 + 按更新时间倒序
    KEY idx_ai_conversation_user_update (user_id, update_time)
);

-- AI 消息表：保存会话中的每一条消息，同时承载工具调用与 Token 统计
CREATE TABLE IF NOT EXISTS ai_message (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL,
    -- 消息角色：user / assistant / system / tool
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    -- 工具调用原始 JSON（M14 起使用）；普通对话消息为 NULL
    tool_calls TEXT NULL,
    -- 命中的知识库文档 id（M15 RAG 引用溯源使用）
    retrieved_doc_ids VARCHAR(512) NULL,
    prompt_tokens INT NULL,
    completion_tokens INT NULL,
    total_tokens INT NULL,
    -- 该条消息的处理耗时（毫秒），用于 M18 执行轨迹可视化
    latency_ms INT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 按会话加载历史消息：会话过滤 + 按时间正序
    KEY idx_ai_message_conversation_create (conversation_id, create_time)
);

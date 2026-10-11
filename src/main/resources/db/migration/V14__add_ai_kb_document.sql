-- Flyway 第 14 个迁移脚本：RAG 知识库的文档表与分块表（M15）
-- 约定说明：
--   1. 本脚本不修改任何历史迁移 V1-V13。
--   2. 表名沿用项目现有小写下划线风格，主键统一 BIGINT AUTO_INCREMENT。
--   3. 与 article(id) 的关联只加索引、不建物理外键：与 M1-M12 既有表及 V13 保持一致，
--      避免级联删除影响业务数据。
--   4. 向量本体存放在 Redis Stack（RediSearch），MySQL 只保存映射、原文与状态：
--      检索由 RediSearch 的 KNN 完成，原文保留在 MySQL 用于引用展示与全量重建。

-- 知识库文档表：一篇已发布文章对应一行
CREATE TABLE IF NOT EXISTS ai_kb_document (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    -- 来源文章 id；唯一约束保证同一篇文章不会被重复索引（全量重建时按此 upsert）
    article_id BIGINT NOT NULL,
    title VARCHAR(50) NOT NULL,
    category_id BIGINT NULL,
    author_id BIGINT NOT NULL,
    -- 索引时的文章状态；只有 PUBLISHED 的文章进入知识库
    status VARCHAR(20) NOT NULL,
    publish_time DATETIME NOT NULL,
    -- 「标题 + 正文」的内容指纹（SHA-256 十六进制，64 字符）。
    -- 文章保存时先比对指纹：指纹未变化就跳过重新嵌入，避免无意义的算力开销。
    content_hash VARCHAR(64) NOT NULL,
    -- 该文档被切分的分块数，便于统计与校验
    chunk_count INT NOT NULL DEFAULT 0,
    -- 索引状态：PENDING（待索引）/ INDEXED（已入库）/ FAILED（失败，可重试）
    index_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    -- 计算本文档向量所用的嵌入模型标识（例如 bge-base-zh-v1.5）。
    -- 换模型后向量维度与语义空间都会变，据此识别需要全量重建的历史数据。
    embedding_model VARCHAR(64) NULL,
    -- 失败原因，便于管理端展示与排查
    last_error VARCHAR(255) NULL,
    index_time DATETIME NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_kb_document_article (article_id),
    -- 管理端统计「还有多少篇待索引 / 失败」：按状态过滤并取最近更新
    KEY idx_ai_kb_document_status (index_status, update_time)
);

-- 知识库分块表：一篇文档切成的若干片段，每段对应 Redis 中的一条向量
CREATE TABLE IF NOT EXISTS ai_kb_chunk (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    document_id BIGINT NOT NULL,
    -- 冗余 article_id：检索命中后可直接构造引用链接，无需回查 document
    article_id BIGINT NOT NULL,
    -- 分块序号，从 0 开始
    chunk_index INT NOT NULL,
    -- 分块原文（含标题上下文），用于回填给模型的引用片段
    content TEXT NOT NULL,
    char_count INT NOT NULL,
    -- 该分块在 Redis 中的向量 id（Spring AI 生成），删除与重建时用于精确定位
    vector_id VARCHAR(128) NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 同一文档内分块序号唯一，防止重复写入
    UNIQUE KEY uk_ai_kb_chunk_document_index (document_id, chunk_index),
    -- 按文章删除或统计分块
    KEY idx_ai_kb_chunk_article (article_id)
);

-- Flyway 第 17 个迁移脚本：AI 推荐记录表（M17）
-- 约定说明：
--   1. 本脚本不修改任何历史迁移 V1-V16。
--   2. 表名沿用项目现有小写下划线风格，主键统一 BIGINT AUTO_INCREMENT。
--   3. 与 article(id) / user(id) 的关联只加索引、不建物理外键（与既有表一致）。
--
-- 【用途】这张表服务于 M17 的验收："给出 Top-N 推荐，并与纯热榜基线对比命中率"。
-- 要能对比，就必须把"推荐了什么、为什么推、用了哪几路信号"完整记下来 ——
-- 只存一个最终列表，事后既解释不了排序，也复现不了结果。
--
-- 【与排序方式解耦】推荐列表可能是"模型自选"或"应用定序后模型只写理由"，
-- 两种方式在本表里**记录字段完全相同**：推荐文章、排名、分数、召回来源、理由。
-- 谁做的选择不影响表结构，因此排序方式的取舍不需要改动本表。
--
-- 【基线对比的做法】基线不占用单独字段，而是用 experiment_tag 区分批次：
--   同一批候选上分别写入推荐系统与 BASELINE_HOT（纯热榜基线）两组记录，
--   评估脚本按 experiment_tag 分组统计命中率即可。
--   experiment_tag 为空的记录属于线上真实请求，不参与实验统计。

CREATE TABLE IF NOT EXISTS ai_recommend_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,

    -- ==================== 请求上下文 ====================
    -- 场景：ARTICLE_DETAIL（文章详情页的相关推荐）/
    --       ASSISTANT（AI 助手回答末尾的相关帖）/ ADMIN（管理台预览）
    scene VARCHAR(32) NOT NULL,
    -- 发起请求的用户；未登录访客为空（此时只能走非个性化召回）
    user_id BIGINT NULL,
    -- 相关推荐的来源文章，即"因为你在看这篇，所以推荐这些"；
    -- AI 助手等没有来源文章的场景为空
    source_article_id BIGINT NULL,

    -- ==================== 推荐结果 ====================
    article_id BIGINT NOT NULL,
    -- 排名，从 1 开始（rank 是 MySQL 8 保留字，故命名为 rank_no）
    rank_no INT NOT NULL,
    -- 融合分数：由 Java 按确定性规则计算，**不由模型给出**
    -- （排序必须可复现，模型自评分数不可复现，与 M16 综合风险分同一原则）
    score DECIMAL(12,6) NULL,
    -- 该文章命中了哪几路召回，逗号分隔：vector / hot / follow
    recall_sources VARCHAR(64) NULL,
    -- 各路原始信号明细（JSON）。保留它是为了事后能复现排序、调整权重时能回溯，
    -- 而不是只看一个融合后的分数。
    score_detail VARCHAR(500) NULL,
    -- 推荐理由（模型生成）。为空有两种情况：模型调用降级，或该场景不生成理由
    -- （例如未登录访客的非个性化推荐）。
    reason VARCHAR(300) NULL,

    -- ==================== 评估用 ====================
    -- 实验批次标识：空 = 线上真实请求；非空 = 某次离线评测（如 BASELINE_HOT）
    experiment_tag VARCHAR(32) NULL,
    -- 是否命中评估真值（评估脚本回填）；NULL = 尚未判定
    -- 列名不用 is_hit：MyBatis-Plus 对 isXxx 形式的布尔字段会按 getter 反推属性名，
    -- 容易与列名对不上；项目既有布尔列（如 ai_moderation_record.handled）也是这个风格。
    hit TINYINT NULL,

    -- ==================== 运行信息 ====================
    model VARCHAR(64) NULL,
    latency_ms INT NULL,
    -- 是否走了降级链路：模型不可用时仍能用确定性排序给出推荐，只是没有理由。
    -- 与 M16 的 decision/action 解耦同一思路 —— "推荐了什么"与"理由有没有生成"是两件事。
    degraded TINYINT NOT NULL DEFAULT 0,

    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- 查某个用户的推荐历史（个性化效果分析）
    KEY idx_airl_user_time (user_id, create_time),
    -- 查某篇文章的"相关推荐"历史
    KEY idx_airl_source (source_article_id, create_time),
    -- 评测：按实验批次取回一组推荐并统计命中率
    KEY idx_airl_experiment (experiment_tag, rank_no),
    -- 统计某篇文章被推荐过多少次
    KEY idx_airl_article (article_id, create_time)
);

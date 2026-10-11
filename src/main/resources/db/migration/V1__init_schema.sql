-- Flyway 第一个迁移脚本：初始化项目需要的三张业务表
-- 文件名 V1__init_schema.sql 表示版本 1，应用启动时 Flyway 会按版本顺序执行

-- 对应 User.java / @TableName("user_info")
CREATE TABLE IF NOT EXISTS user_info (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(50) NOT NULL,
    password VARCHAR(100) NOT NULL,
    avatar VARCHAR(255),
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    -- 用户名必须唯一，防止并发注册或重复注册产生脏数据
    UNIQUE KEY uk_user_info_username (username)
);

-- 对应 Article.java / @TableName("article")
CREATE TABLE IF NOT EXISTS article (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    title VARCHAR(50) NOT NULL,
    content TEXT NOT NULL,
    user_id BIGINT NOT NULL,
    view_count INT DEFAULT 0,
    like_count INT DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    -- 常用查询字段建索引：按作者查文章、按发布时间排序
    KEY idx_article_user_id (user_id),
    KEY idx_article_create_time (create_time)
);

-- 对应 Comment.java / @TableName("comment")
CREATE TABLE IF NOT EXISTS comment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    content TEXT NOT NULL,
    user_id BIGINT NOT NULL,
    article_id BIGINT NOT NULL,
    parent_comment_id BIGINT DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    -- 评论常按 article_id 查询；user_id 方便后续查某个用户的评论
    KEY idx_comment_article_id (article_id),
    KEY idx_comment_user_id (user_id)
);

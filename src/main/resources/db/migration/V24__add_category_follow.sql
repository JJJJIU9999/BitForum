-- 仅新增话题关注；既有内容、作者关注及历史迁移保持不变。
CREATE TABLE category_follow (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    category_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_category_follow_user_category (user_id, category_id),
    KEY idx_category_follow_user_time (user_id, created_at, id),
    KEY idx_category_follow_category (category_id),
    CONSTRAINT fk_category_follow_category FOREIGN KEY (category_id)
        REFERENCES category(id) ON DELETE CASCADE
);

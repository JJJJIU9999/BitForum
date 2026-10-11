-- 回答点赞持久关系；不修改历史回答/回复内容或既有通知约束。
CREATE TABLE answer_like (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    comment_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_answer_like_comment_user (comment_id, user_id),
    KEY idx_answer_like_user (user_id)
);

CREATE INDEX idx_comment_article_parent_status_time
    ON comment (article_id, parent_comment_id, status, create_time, id);

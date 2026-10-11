CREATE TABLE IF NOT EXISTS user_follow (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    follower_id BIGINT NOT NULL,
    following_id BIGINT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_follow_follower_following (follower_id, following_id),
    KEY idx_user_follow_follower_id (follower_id),
    KEY idx_user_follow_following_id (following_id)
);


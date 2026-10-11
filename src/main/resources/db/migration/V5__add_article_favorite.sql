CREATE TABLE IF NOT EXISTS article_favorite (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    article_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_article_favorite_user_article (user_id, article_id),
    KEY idx_article_favorite_article_id (article_id),
    KEY idx_article_favorite_user_create_time (user_id, create_time)
);


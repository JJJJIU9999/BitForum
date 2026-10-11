CREATE TABLE IF NOT EXISTS notification (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    receiver_id BIGINT NOT NULL,
    sender_id BIGINT NULL,
    type VARCHAR(32) NOT NULL,
    title VARCHAR(100) NOT NULL,
    content VARCHAR(500) NOT NULL,
    article_id BIGINT NULL,
    read_status TINYINT NOT NULL DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_notification_receiver_read_create (receiver_id, read_status, create_time),
    KEY idx_notification_article_id (article_id),
    KEY idx_notification_type (type)
);


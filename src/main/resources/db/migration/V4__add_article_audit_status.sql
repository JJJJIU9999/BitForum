ALTER TABLE article
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED' AFTER category_id;

UPDATE article
SET status = 'PUBLISHED'
WHERE status IS NULL;

CREATE INDEX idx_article_status_create_time
    ON article(status, create_time);

CREATE INDEX idx_article_user_status_create_time
    ON article(user_id, status, create_time);

CREATE TABLE IF NOT EXISTS article_audit_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    article_id BIGINT NOT NULL,
    auditor_id BIGINT NOT NULL,
    audit_status VARCHAR(20) NOT NULL,
    reason VARCHAR(255),
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_audit_article_id (article_id),
    KEY idx_audit_auditor_id (auditor_id),
    KEY idx_audit_status_create_time (audit_status, create_time)
);

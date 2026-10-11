CREATE TABLE text_rule_revision (
    id TINYINT PRIMARY KEY,
    version BIGINT NOT NULL
);

INSERT INTO text_rule_revision (id, version) VALUES (1, 0);

CREATE TABLE text_rule (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    phrase VARCHAR(255) NOT NULL,
    normalized_phrase VARCHAR(255) NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 1,
    created_by BIGINT NOT NULL,
    updated_by BIGINT NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_text_rule_normalized_phrase (normalized_phrase)
);

CREATE TABLE text_rule_operation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    rule_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    action VARCHAR(20) NOT NULL,
    old_phrase VARCHAR(255),
    new_phrase VARCHAR(255),
    old_enabled TINYINT,
    new_enabled TINYINT,
    rule_version BIGINT NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_text_rule_operation_rule_time (rule_id, create_time)
);

ALTER TABLE comment
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED' AFTER content,
    ADD KEY idx_comment_article_status_time (article_id, status, create_time);

ALTER TABLE user_info
    ADD COLUMN public_profile_status VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED' AFTER bio;

CREATE TABLE text_review_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    target_type VARCHAR(20) NOT NULL,
    target_id BIGINT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    rule_version BIGINT NOT NULL,
    matched_rules VARCHAR(500) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    source VARCHAR(20) NOT NULL,
    candidate_nickname VARCHAR(50),
    candidate_bio VARCHAR(255),
    reviewer_id BIGINT,
    reason VARCHAR(255),
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_time DATETIME,
    UNIQUE KEY uk_text_review_content_version (target_type, target_id, content_hash, rule_version),
    KEY idx_text_review_status_time (status, create_time),
    KEY idx_text_review_target (target_type, target_id)
);

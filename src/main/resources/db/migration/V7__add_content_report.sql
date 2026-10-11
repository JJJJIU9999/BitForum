CREATE TABLE IF NOT EXISTS content_report (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    reporter_id BIGINT NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_id BIGINT NOT NULL,
    target_owner_id BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    handler_id BIGINT NULL,
    handle_result VARCHAR(500) NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    handle_time DATETIME NULL,
    KEY idx_report_reporter_target_status (reporter_id, target_type, target_id, status),
    KEY idx_report_status_create (status, create_time),
    KEY idx_report_target (target_type, target_id),
    KEY idx_report_target_owner (target_owner_id)
);


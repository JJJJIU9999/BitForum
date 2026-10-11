ALTER TABLE notification
    ADD COLUMN source_message_id VARCHAR(64) NULL AFTER article_id,
    ADD UNIQUE KEY uk_notification_source_message_id (source_message_id);

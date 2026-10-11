ALTER TABLE content_report
    ADD COLUMN pending_report_unique_key VARCHAR(128)
        GENERATED ALWAYS AS (
            CASE
                WHEN status = 'PENDING'
                    THEN CONCAT(reporter_id, ':', target_type, ':', target_id)
                ELSE NULL
            END
        ) STORED,
    ADD UNIQUE KEY uk_content_report_pending_target (pending_report_unique_key);

-- 问题复用文章，回答复用评论；历史内容保持 ARTICLE。
ALTER TABLE article
    ADD COLUMN content_type VARCHAR(20) NOT NULL DEFAULT 'ARTICLE' AFTER status,
    ADD COLUMN accepted_answer_id BIGINT NULL AFTER content_type,
    ADD KEY idx_article_type_status_time (content_type, status, create_time);

-- Preserve existing article/question bodies verbatim; only explicitly selected content uses Markdown.
ALTER TABLE article
    ADD COLUMN content_format VARCHAR(16) NOT NULL DEFAULT 'PLAIN_TEXT' AFTER content;

CREATE TABLE IF NOT EXISTS category (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(50) NOT NULL,
    description VARCHAR(255),
    sort_order INT NOT NULL DEFAULT 0,
    status TINYINT NOT NULL DEFAULT 1,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_category_name (name),
    KEY idx_category_status_sort (status, sort_order)
);

INSERT INTO category (name, description, sort_order, status)
SELECT '默认板块', '系统默认板块，用于承接已有文章', 0, 1
WHERE NOT EXISTS (
    SELECT 1 FROM category WHERE name = '默认板块'
);

ALTER TABLE article
    ADD COLUMN category_id BIGINT NULL AFTER user_id;

UPDATE article
SET category_id = (SELECT id FROM category WHERE name = '默认板块' LIMIT 1)
WHERE category_id IS NULL;

ALTER TABLE article
    MODIFY category_id BIGINT NOT NULL;

CREATE INDEX idx_article_category_create_time
    ON article(category_id, create_time);

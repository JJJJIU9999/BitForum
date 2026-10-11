CREATE TABLE image_resource (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    owner_id BIGINT NOT NULL,
    kind VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    storage_key VARCHAR(255),
    legacy_url VARCHAR(255),
    target_id BIGINT,
    original_filename VARCHAR(255),
    content_type VARCHAR(40),
    size BIGINT,
    reviewer_id BIGINT,
    review_reason VARCHAR(255),
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_time DATETIME,
    KEY idx_image_resource_status_time (status, create_time),
    KEY idx_image_resource_owner_kind (owner_id, kind),
    KEY idx_image_resource_target (kind, target_id)
);

ALTER TABLE user_info ADD COLUMN pending_avatar_image_id BIGINT NULL AFTER avatar;
ALTER TABLE article ADD COLUMN cover_image_id BIGINT NULL AFTER cover_url;

-- 历史引用先登记再隐藏。文件保持原样，只有人工通过后才建立新的受控公开 URL。
INSERT INTO image_resource (owner_id, kind, status, storage_key, legacy_url, target_id, original_filename)
SELECT id, 'AVATAR', 'LEGACY_REVIEW',
       CASE WHEN avatar LIKE '/uploads/%' THEN SUBSTRING(avatar, 10) ELSE NULL END,
       avatar, id, SUBSTRING_INDEX(avatar, '/', -1)
FROM user_info WHERE avatar IS NOT NULL AND TRIM(avatar) <> '';

UPDATE user_info u JOIN image_resource i ON i.kind = 'AVATAR' AND i.target_id = u.id
SET u.pending_avatar_image_id = i.id, u.avatar = NULL
WHERE i.status = 'LEGACY_REVIEW';

INSERT INTO image_resource (owner_id, kind, status, storage_key, legacy_url, target_id, original_filename)
SELECT user_id, 'COVER', 'LEGACY_REVIEW',
       CASE WHEN cover_url LIKE '/uploads/%' THEN SUBSTRING(cover_url, 10) ELSE NULL END,
       cover_url, id, SUBSTRING_INDEX(cover_url, '/', -1)
FROM article WHERE cover_url IS NOT NULL AND TRIM(cover_url) <> '';

UPDATE article a JOIN image_resource i ON i.kind = 'COVER' AND i.target_id = a.id
SET a.cover_image_id = i.id, a.cover_url = NULL
WHERE i.status = 'LEGACY_REVIEW';

ALTER TABLE user_info
    ADD COLUMN nickname VARCHAR(50) NULL AFTER avatar,
    ADD COLUMN bio VARCHAR(255) NULL AFTER nickname;


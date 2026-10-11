-- 二阶段权限基础字段：
-- role 区分普通用户和管理员，status 控制账号是否可继续登录。
ALTER TABLE user_info
    ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'USER',
    ADD COLUMN status TINYINT NOT NULL DEFAULT 1;

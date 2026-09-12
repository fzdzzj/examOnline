-- =============================================================
-- examOnline —— RBAC 五表建表脚本（add-project-skeleton）
-- 兼容 MySQL 8.0 与 H2(MySQL 兼容模式, 测试用)；全部幂等（IF NOT EXISTS）
-- 注：不使用 MySQL 专属 COMMENT 子句，保证 H2 测试环境可解析
-- =============================================================

CREATE TABLE IF NOT EXISTS users (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    username             VARCHAR(64)  NOT NULL,
    password             VARCHAR(100) NOT NULL,
    name                 VARCHAR(64)  NOT NULL DEFAULT '',
    email                VARCHAR(128)          DEFAULT NULL,
    status               TINYINT      NOT NULL DEFAULT 0,
    must_change_password TINYINT      NOT NULL DEFAULT 0,
    created_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted           TINYINT      NOT NULL DEFAULT 0,
    CONSTRAINT uk_users_username UNIQUE (username)
);

CREATE TABLE IF NOT EXISTS roles (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    code       VARCHAR(32) NOT NULL,
    name       VARCHAR(64) NOT NULL,
    level      INT         NOT NULL,
    created_time DATETIME  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted TINYINT     NOT NULL DEFAULT 0,
    CONSTRAINT uk_roles_code UNIQUE (code)
);

CREATE TABLE IF NOT EXISTS permissions (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    code       VARCHAR(64) NOT NULL,
    name       VARCHAR(64) NOT NULL,
    created_time DATETIME  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted TINYINT     NOT NULL DEFAULT 0,
    CONSTRAINT uk_permissions_code UNIQUE (code)
);

CREATE TABLE IF NOT EXISTS user_roles (
    id      BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    CONSTRAINT uk_user_roles UNIQUE (user_id, role_id),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_user_roles_role FOREIGN KEY (role_id) REFERENCES roles (id)
);

CREATE TABLE IF NOT EXISTS role_permissions (
    id            BIGINT NOT NULL AUTO_INCREMENT,
    role_id       BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    CONSTRAINT uk_role_permissions UNIQUE (role_id, permission_id),
    CONSTRAINT fk_role_permissions_role FOREIGN KEY (role_id) REFERENCES roles (id),
    CONSTRAINT fk_role_permissions_perm FOREIGN KEY (permission_id) REFERENCES permissions (id)
);

-- 教师邀请码表（add-authentication）：管理员生成，教师凭有效码注册为 TEACHER
CREATE TABLE IF NOT EXISTS invite_codes (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    code         VARCHAR(32) NOT NULL,
    note         VARCHAR(128)         DEFAULT '',
    status       TINYINT     NOT NULL DEFAULT 0,  -- 0=有效 1=已作废
    used_count   INT         NOT NULL DEFAULT 0,  -- 已被用于注册的次数（管理员可见）
    created_by   BIGINT      NOT NULL DEFAULT 0,  -- 创建人用户 ID
    created_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted   TINYINT     NOT NULL DEFAULT 0,
    CONSTRAINT uk_invite_codes_code UNIQUE (code)
);

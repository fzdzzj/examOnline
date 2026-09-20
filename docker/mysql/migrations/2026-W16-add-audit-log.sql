-- =============================================================
-- 存量库迁移：新增 audit_log（harden-security-config）
-- 适用：已建库的 MySQL 环境。新建库由 schema.sql 一次建全，无需本脚本。
-- 幂等性说明：重复执行时 MySQL 8 报表 already exists，可忽略。
-- 注：本项目未接 Flyway，src/main/resources/db/migration 下的同名 V 文件不会被自动执行，
--     存量库必须由 DBA 手工跑本脚本。
-- =============================================================

CREATE TABLE IF NOT EXISTS audit_log (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    trace_id     VARCHAR(64)          DEFAULT NULL,
    user_id      BIGINT               DEFAULT NULL,          -- 系统事件为 NULL
    username     VARCHAR(64) NOT NULL,
    action       VARCHAR(32) NOT NULL,                       -- LOGIN / LOGIN_LOCKED / PERMISSION_CHANGE ...
    ip_address   VARCHAR(45)          DEFAULT NULL,          -- 兼容 IPv6
    status       VARCHAR(16) NOT NULL,                       -- SUCCESS / FAILURE / WARNING
    details      VARCHAR(512)         DEFAULT NULL,
    created_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_audit_user_time (username, created_time),
    KEY idx_audit_action_time (action, created_time),
    KEY idx_audit_trace (trace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='安全审计日志表';

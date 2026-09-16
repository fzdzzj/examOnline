-- =============================================================
-- 存量库迁移：新增 exam_dlq_messages（add-dlq-observability-and-replay, W15）
-- 适用：已建库的 MySQL 环境。新建库由 schema.sql 一次建全，无需本脚本。
-- 幂等性说明：重复执行时 MySQL 8 报表 already exists，可忽略。
-- =============================================================

CREATE TABLE IF NOT EXISTS exam_dlq_messages (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    queue         VARCHAR(128) NOT NULL,
    payload       LONGTEXT     NOT NULL,
    headers_json  TEXT                  DEFAULT NULL,
    retry_count   INT          NOT NULL DEFAULT 0,
    replay_count  INT          NOT NULL DEFAULT 0,
    status        VARCHAR(16)  NOT NULL,
    error_message VARCHAR(512)          DEFAULT NULL,
    created_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_dlq_status_time (status, created_time)
);

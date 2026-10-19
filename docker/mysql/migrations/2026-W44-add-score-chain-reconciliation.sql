-- 2026-W44-add-score-chain-reconciliation.sql
-- 成绩链全链路对账（add-score-chain-reconciliation）存量库迁移脚本。
--
-- 本目录没有自动执行者：脚本入库 ≠ 迁移已生效。须由人按顺序应用到存量库，
-- 并按 docker/mysql/migrations/APPLIED.md 的核验 SQL 逐对象核验后回填台账。
-- 建表唯一事实源是 src/main/resources/schema.sql，本文件与其保持同形（H2/MySQL 双兼容）。
--
-- 幂等预期：两表均为 CREATE TABLE IF NOT EXISTS，重复执行不报错（对齐 W41/W42/W43 先例）。

-- 对账运行记录（一次手动/定时对账一行）。
-- batch_key 为定时兜底的批次键（唯一索引）：多实例同一时间窗并发触发只有一格能插入，
-- 其余实例收到重复键即跳过——与 reliability 域「正确性由幂等保证、不引入调度锁」同口径。
CREATE TABLE IF NOT EXISTS reconciliation_runs (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    exam_id          BIGINT                DEFAULT NULL,
    trigger_source   VARCHAR(16)  NOT NULL,
    batch_key        VARCHAR(64)           DEFAULT NULL,
    status           VARCHAR(16)  NOT NULL,
    scanned_count    INT          NOT NULL DEFAULT 0,
    ungraded_count   INT          NOT NULL DEFAULT 0,
    mismatch_count   INT          NOT NULL DEFAULT 0,
    auto_fixed_count INT          NOT NULL DEFAULT 0,
    error_message    VARCHAR(512)          DEFAULT NULL,
    operator_id      BIGINT                DEFAULT NULL,
    started_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_time    DATETIME              DEFAULT NULL,
    created_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_recon_run_batch UNIQUE (batch_key),
    KEY idx_recon_run_exam (exam_id, id)
);

-- 差异票（OPEN / AUTO_FIXED / MANUAL_CLOSED）。
-- question_id 以 0 表示答卷级，避免唯一索引里 NULL 逃逸；指纹带 status，
-- 使未处置的同一差异重复对账不重复出票，处置完毕后复发仍可重新出 OPEN 票。
-- 涉分矛盾只出票不改分：expected/actual/deviation 仅供人工处置参照。
CREATE TABLE IF NOT EXISTS reconciliation_diffs (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    run_id         BIGINT        NOT NULL,
    exam_id        BIGINT        NOT NULL,
    submission_id  BIGINT        NOT NULL,
    question_id    BIGINT        NOT NULL DEFAULT 0,
    diff_type      VARCHAR(32)   NOT NULL,
    detail         VARCHAR(64)   NOT NULL DEFAULT '',
    expected_value DECIMAL(10,2)          DEFAULT NULL,
    actual_value   DECIMAL(10,2)          DEFAULT NULL,
    deviation      DECIMAL(10,2)          DEFAULT NULL,
    status         VARCHAR(16)   NOT NULL DEFAULT 'OPEN',
    closed_by      BIGINT                 DEFAULT NULL,
    closed_time    DATETIME               DEFAULT NULL,
    created_time   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_recon_diff_fingerprint UNIQUE (diff_type, submission_id, question_id, status),
    KEY idx_recon_diff_run (run_id, id),
    KEY idx_recon_diff_status (status, id)
);

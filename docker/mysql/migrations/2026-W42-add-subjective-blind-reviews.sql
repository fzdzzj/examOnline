-- =============================================================
-- 存量库迁移：简答题双评盲评表（add-subjective-double-blind-grading）
-- 适用：已建库的 MySQL 环境。schema.sql 走 CREATE TABLE IF NOT EXISTS——
-- 不会为"已存在"的库补建 subjective_blind_reviews 表，存量库必须手工执行本脚本
-- （dev 可选，prod SQL_INIT_MODE=never 必须执行）。
-- 新建库由 schema.sql 一次建全，无需本脚本。
--
-- 幂等性说明：
--   建表用 CREATE TABLE IF NOT EXISTS，重复执行时 MySQL 8 不报错、静默跳过
--   （对齐 2026-W41 先例：表形态复跑无变化）。
--
-- 运维警示：
--   本目录没有自动执行者，脚本入库 ≠ 迁移已生效，存量库需人工按顺序应用并验证；
--   dev 库应用时机由用户另行裁决派发，不随本案自动生效。
-- =============================================================

CREATE TABLE IF NOT EXISTS subjective_blind_reviews (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    exam_id       BIGINT        NOT NULL,
    submission_id BIGINT        NOT NULL,
    question_id   BIGINT        NOT NULL,
    round_type    VARCHAR(16)   NOT NULL,
    grader_id     BIGINT        NOT NULL,
    score         DECIMAL(5,1)  NOT NULL,
    comment       VARCHAR(1024)          DEFAULT NULL,
    created_time  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_blind_review UNIQUE (submission_id, question_id, round_type),
    KEY idx_blind_exam_grader (exam_id, grader_id),
    KEY idx_blind_sub_q (submission_id, question_id)
);

-- =============================================================
-- 存量库迁移：判分与成绩（add-grading-score, W7）
-- 适用：在本变更之前已建库的 MySQL 环境。schema.sql 走 CREATE TABLE
-- IF NOT EXISTS——不会为"已存在"的 exam_submissions 表补列，存量库
-- 必须手工执行本脚本（dev 可选，prod SQL_INIT_MODE=never 必须执行）。
-- 新建库由 schema.sql 一次建全，无需本脚本。
-- 幂等性说明：MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，重复执行报
-- "Duplicate column name"可直接忽略；或先查 information_schema 确认。
-- =============================================================

-- 答卷表补判分/成绩字段（与 schema.sql 中 exam_submissions 定义一致）
ALTER TABLE exam_submissions
    ADD COLUMN objective_score  DECIMAL(5,1) DEFAULT NULL AFTER version,
    ADD COLUMN subjective_score DECIMAL(5,1) DEFAULT NULL AFTER objective_score,
    ADD COLUMN total_score      DECIMAL(5,1) DEFAULT NULL AFTER subjective_score,
    ADD COLUMN grading_status   TINYINT NOT NULL DEFAULT 0 AFTER total_score,
    ADD COLUMN grading_error    VARCHAR(512) DEFAULT NULL AFTER grading_status,
    ADD COLUMN partial_graded   TINYINT NOT NULL DEFAULT 0 AFTER grading_error;

-- 判分扫描索引（整场判分/进度按考试+判分状态筛答卷）
ALTER TABLE exam_submissions
    ADD KEY idx_submissions_grading (exam_id, grading_status);

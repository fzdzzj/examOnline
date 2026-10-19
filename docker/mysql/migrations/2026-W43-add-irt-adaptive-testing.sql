-- 2026-W43-add-irt-adaptive-testing.sql
-- 变更：add-irt-adaptive-testing（IRT 三参数标定 + CAT 自适应测验会话）
-- 对象：question_irt_params（题目 IRT 参数，uk_irt_question 唯一 + PRIMARY(id)）
--       cat_quiz_sessions（CAT 会话，idx_cat_session_student 索引 + PRIMARY(id)）
-- 适用：存量库（新库由 src/main/resources/schema.sql 一次建全，无需执行本脚本）
-- 幂等：CREATE TABLE IF NOT EXISTS，重复执行不报错、不改变既有对象
-- 注意：本脚本没有自动执行者（不挂 docker-compose init），入库 ≠ 已应用；
--       应用状态见同目录 APPLIED.md 台账，应用后按 A3/A1/A2/A4 核验回填。
-- 双兼容：列类型只使用 MySQL 8 与 H2 共同支持的形态（BIGINT/VARCHAR/DECIMAL/DATETIME/BOOLEAN），
--         自增块内显式 PRIMARY KEY (id)，与 schema.sql 逐字一致（防新库/存量库形状分歧）。

CREATE TABLE IF NOT EXISTS question_irt_params (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    question_id     BIGINT       NOT NULL,
    discrimination  DECIMAL(8,4) NOT NULL,
    difficulty      DECIMAL(8,4) NOT NULL,
    guessing        DECIMAL(6,4) NOT NULL,
    sample_size     INT          NOT NULL DEFAULT 0,
    pool_available  BOOLEAN      NOT NULL DEFAULT TRUE,
    source_exam_id  BIGINT                DEFAULT NULL,
    calibrated_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_irt_question UNIQUE (question_id)
);

CREATE TABLE IF NOT EXISTS cat_quiz_sessions (
    id                    BIGINT        NOT NULL AUTO_INCREMENT,
    student_id            BIGINT        NOT NULL,
    status                VARCHAR(16)   NOT NULL DEFAULT 'IN_PROGRESS',
    theta                 DECIMAL(10,4) NOT NULL DEFAULT 0,
    se                    DECIMAL(10,4) NOT NULL DEFAULT 1,
    max_items             INT           NOT NULL DEFAULT 10,
    se_threshold          DECIMAL(6,4)  NOT NULL DEFAULT 0.3000,
    answered_count        INT           NOT NULL DEFAULT 0,
    correct_count         INT           NOT NULL DEFAULT 0,
    current_question_id   BIGINT                 DEFAULT NULL,
    answered_question_ids VARCHAR(1024)          DEFAULT '',
    item_trail            VARCHAR(4096)          DEFAULT '',
    termination_reason    VARCHAR(32)            DEFAULT NULL,
    created_time          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_cat_session_student (student_id, id)
);

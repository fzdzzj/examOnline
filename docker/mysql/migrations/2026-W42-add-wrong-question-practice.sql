-- =============================================================
-- 错题重练作答明细（add-wrong-question-practice）
-- 存量 dev 库补表脚本；由人按顺序应用并验证（硬约定 4，提交 ≠ 已生效）
-- 纯练习序列移出口径（同一题目连续 3 次答对自动消灭，任一次答错归零）
-- =============================================================
CREATE TABLE IF NOT EXISTS practice_attempt (
    id           BIGINT   NOT NULL AUTO_INCREMENT,
    student_id   BIGINT   NOT NULL,
    question_id  BIGINT   NOT NULL,
    is_correct   BOOLEAN  NOT NULL,
    created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_practice_attempt_stu_q_id (student_id, question_id, id)
);

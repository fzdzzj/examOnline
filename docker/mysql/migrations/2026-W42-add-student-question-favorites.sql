-- =============================================================
-- 学生题目个人收藏夹（add-student-question-favorites）
-- 存量 dev 库补表脚本；由人按顺序应用并验证（硬约定 4，提交 ≠ 已生效）
-- =============================================================
CREATE TABLE IF NOT EXISTS student_question_favorites (
    id           BIGINT   NOT NULL AUTO_INCREMENT,
    student_id   BIGINT   NOT NULL,
    question_id  BIGINT   NOT NULL,
    created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_fav_student_question UNIQUE (student_id, question_id),
    KEY idx_fav_student_id (student_id, id)
);

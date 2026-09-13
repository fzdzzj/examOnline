-- =============================================================
-- 存量库迁移：成绩复核（add-class-and-post-exam-closure 阶段 4，W10）
-- 适用：已建库的 MySQL 环境。schema.sql 走 CREATE TABLE IF NOT EXISTS——
-- 不会为"已存在"的库补建 score_review 表，存量库必须手工执行本脚本
-- （dev 可选，prod SQL_INIT_MODE=never 必须执行）。
-- 新建库由 schema.sql 一次建全，无需本脚本。
-- 幂等性说明：新表用 CREATE TABLE IF NOT EXISTS，重复执行不报错。
-- =============================================================

-- 成绩复核表（spec score-review §5.4/§10.7）：
--   学生成绩发布后 7 天内可申请复核，复核申请期间学生端隐藏成绩显示"复核中"。
--   status：0=待处理 1=处理中 2=已同意 3=已驳回（0/1 视为"进行中"，隐藏成绩期间命中）。
--   result：教师处理意见/结果说明；agree 调分后同步更新答卷 total_score 显示；
--   apply_time 为 7 天窗口的起点校验依据。
-- 唯一索引 uk_review_exam_student(exam_id,student_id)：一场一学生限申请 1 次（§10.7），
--   是并发的最终幂等护栏——并发重复申请时唯一索引冲突转友好提示"已申请"。
--   索引名规避与 exam_submissions 的 uk_exam_student 在 H2(MySQL 模式) 全局约束名碰撞
--   （同前 absence/candidate 两表约定，见 2026-W10-add-class-onwards）。
-- idx_review_exam_status(exam_id,status)：教师按考试+状态查待处理复核清单。
CREATE TABLE IF NOT EXISTS score_review (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    exam_id     BIGINT       NOT NULL,                                -- 所属考试
    student_id  BIGINT       NOT NULL,                                -- 申请复核的学生（user id）
    status      TINYINT      NOT NULL DEFAULT 0,                      -- 0待处理 1处理中 2已同意 3已驳回
    reason      VARCHAR(512)          DEFAULT NULL,                   -- 学生申请理由
    result      VARCHAR(512)          DEFAULT NULL,                   -- 教师处理意见/结果说明
    apply_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,      -- 申请时间（7 天窗口起点校验）
    handle_time DATETIME              DEFAULT NULL,                   -- 处理时间
    handler_id  BIGINT                DEFAULT NULL,                   -- 处理教师（user id）
    CONSTRAINT uk_review_exam_student UNIQUE (exam_id, student_id),   -- 一场一学生限 1 次（幂等兜底）
    KEY idx_review_exam_status (exam_id, status)                      -- 教师按考试/状态查复核清单
);
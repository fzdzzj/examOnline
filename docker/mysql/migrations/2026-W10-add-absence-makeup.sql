-- =============================================================
-- 存量库迁移：缺考标记 + 补考（add-class-and-post-exam-closure, W10）
-- 适用：已建库的 MySQL 环境。schema.sql 走 CREATE TABLE IF NOT EXISTS——
-- 不会为"已存在"的库补建 exam_absence/exam_candidates、也不会为 exams 加列，
-- 存量库必须手工执行本脚本（dev 可选，prod SQL_INIT_MODE=never 必须执行）。
-- 新建库由 schema.sql 一次建全，无需本脚本。
-- 幂等性说明：新表用 CREATE TABLE IF NOT EXISTS、加列用 ADD COLUMN IF NOT EXISTS（MySQL 8.0），
-- 重复执行不报错。
-- 注意：阶段 9 其余表（classes/user_class 走 2026-W10-add-class.sql，score_review 由复核独立迁移）
-- 本脚本只建缺考/补考两表并给 exams 补两个列。
-- =============================================================

-- 缺考表：考试结束时，把"应考名单（班级当前学生）− 有答卷者"的差集写入，
-- 一人一场至多一条（uk_exam_student 唯一索引兜底幂等，重复扫表 INSERT IGNORE 只写一次）。
-- status 预留缺考状态扩展（0=缺考标记，后续如需"已安排补考"等状态可扩展，不破坏既有行）。
CREATE TABLE IF NOT EXISTS exam_absence (
    id           BIGINT   NOT NULL AUTO_INCREMENT,
    exam_id      BIGINT   NOT NULL,              -- 所属考试
    student_id   BIGINT   NOT NULL,              -- 缺考学生（user id）
    status       TINYINT  NOT NULL DEFAULT 0,    -- 缺考状态：0=已标记缺考（预留扩展）
    marked_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, -- 缺考标记时间（考试结束时服务端写入）
    created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 唯一键作用仍是「一场一学生一条缺考」；取名 uk_absence_exam_student 而非沿用 exam_submissions 的
    -- uk_exam_student——MySQL 索引名按表隔离可同名，但 H2(MySQL 模式) 约束名全局可见，同名会碰撞报错。
    CONSTRAINT uk_absence_exam_student UNIQUE (exam_id, student_id),  -- 一场一学生一条缺考（幂等兜底）
    KEY idx_exam (exam_id)                       -- 教师按考试查缺考名单
);

-- 补考名单表：教师组织补考时写入的"可进入补考的学生"名单，限制进入（名单外拒绝）。
-- 独立于答卷（exam_submissions）：只有进入后才有答卷，名单是进入前的准入闸。
CREATE TABLE IF NOT EXISTS exam_candidates (
    id           BIGINT   NOT NULL AUTO_INCREMENT,
    exam_id      BIGINT   NOT NULL,              -- 补考考试（exams 中 parent_exam_id 指向主考的独立记录）
    student_id   BIGINT   NOT NULL,              -- 被指定补考的学生（user id）
    created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_candidate_exam_student UNIQUE (exam_id, student_id),  -- 一人一场一条名单（幂等）
    KEY idx_candidates_exam (exam_id)            -- 进入时校验名单、教师查名单
);

-- 补考是独立考试记录（复用 exams 表，§12.5：独立时间窗/时长/规则，与主考互不影响）：
--   parent_exam_id = 关联的主考考试 id（非补考为 NULL，向后兼容旧考试）；
--   makeup_score_rule = 补考成绩取最终成绩规则（takeHighest/takeLatest/takeAverage，NULL=非补考不适用）。
-- 两列均 NULL 可，旧考试不受影响。ADD COLUMN IF NOT EXISTS（MySQL 8.0）保证幂等。
ALTER TABLE exams ADD COLUMN IF NOT EXISTS parent_exam_id BIGINT NULL;
ALTER TABLE exams ADD COLUMN IF NOT EXISTS makeup_score_rule VARCHAR(20) NULL;
ALTER TABLE exams ADD KEY IF NOT EXISTS idx_exams_parent (parent_exam_id);
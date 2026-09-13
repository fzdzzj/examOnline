-- =============================================================
-- 存量库迁移：班级体系（add-class-and-post-exam-closure, W10）
-- 适用：在本变更之前已建库的 MySQL 环境。schema.sql 走 CREATE TABLE
-- IF NOT EXISTS——不会为"已存在"的库补建 classes/user_class 两表，存量库
-- 必须手工执行本脚本（dev 可选，prod SQL_INIT_MODE=never 必须执行）。
-- 新建库由 schema.sql 一次建全，无需本脚本。
-- 幂等性说明：两表均用 CREATE TABLE IF NOT EXISTS，重复执行不报错。
-- 注意：阶段 9 其余表（exam_absence/exam_candidates/score_review）由
-- 缺考/补考/复核的独立迁移文件提供，本脚本只建班级地基两表。
-- =============================================================

-- 班级表：归属教师（teacher_id 为 owner 校验依据，可能不同于创建人 created_by，
-- 如管理员代建时两者不同）；course_id 预留课程实体（后续阶段提供，先存 ID）。
-- 软删除（is_deleted），与题目/考试同模式：历史考试引用不受影响。
CREATE TABLE IF NOT EXISTS classes (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    name         VARCHAR(64)  NOT NULL,
    course_id    BIGINT                DEFAULT NULL, -- 课程 ID（课程实体后续阶段提供，先存 ID）
    teacher_id   BIGINT       NOT NULL,              -- 归属教师 ID（owner 校验依据）
    created_by   BIGINT       NOT NULL,              -- 创建人用户 ID
    created_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted   TINYINT      NOT NULL DEFAULT 0,
    KEY idx_classes_teacher (teacher_id)             -- 教师班级列表
);

-- 学生-班级关联表：一人一班一条关联（uk_user_class 唯一索引兜底幂等）；
-- 转班 = 更新本表 class_id（成绩随人 §12.6：答卷已绑 student_id，成绩不依赖班级，无需迁移成绩）；
-- idx_class_id 支撑"查某班当前全部学生"→ 应考名单推导（Agent 2 消费）。
CREATE TABLE IF NOT EXISTS user_class (
    id          BIGINT   NOT NULL AUTO_INCREMENT,
    user_id     BIGINT   NOT NULL,
    class_id    BIGINT   NOT NULL,
    joined_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, -- 入班时间（转班时刷新为转班时间）
    CONSTRAINT uk_user_class UNIQUE (user_id, class_id),
    KEY idx_class_id (class_id)
);

-- =============================================================
-- 存量库迁移：考试预约 + 学生消息中心（add-exam-reservation）
-- 适用：已建库的 MySQL 环境。schema.sql 走 CREATE TABLE IF NOT EXISTS——
-- 不会为"已存在"的库补建 exam_reservations/user_messages 两表，也不会为 exams 补列，
-- 存量库必须手工执行本脚本（dev 可选，prod SQL_INIT_MODE=never 必须执行）。
-- 新建库由 schema.sql 一次建全，无需本脚本。
--
-- 幂等性说明：
--   新表用 CREATE TABLE IF NOT EXISTS，重复执行时 MySQL 8 视为同名跳过（无报错，
--   语义等价"表 already exists 可忽略"，登记别名 1050）；
--   加列用 ALTER TABLE ... ADD COLUMN，重复执行时 MySQL 8 报
--   ERROR 1060 (42000): Duplicate column name 'reservation_required'，可忽略。
--
-- 运维警示：
--   本目录没有自动执行者，脚本入库 ≠ 迁移已生效，存量库需人工按顺序应用并验证。
-- =============================================================

-- 考试预约表：学生预约制考试（reservation_required=1）时写入；开考瞬间名单锁定，
-- 缺席/应考统计按有效预约名单计（「预约即应考」）。reminded_time 为开考提醒投递的行级幂等标记，
-- 扫描投递 UPDATE ... WHERE reminded_time IS NULL 命中 1 行才写消息，重复扫描/重启/多实例竞争不重发。
-- uk_reservation_exam_student 唯一索引避让 H2 全局约束名碰撞（仿 uk_absence_exam_student 先例）。
CREATE TABLE IF NOT EXISTS exam_reservations (
    id            BIGINT   NOT NULL AUTO_INCREMENT,
    exam_id       BIGINT   NOT NULL,
    student_id    BIGINT   NOT NULL,
    reminded_time DATETIME          DEFAULT NULL,  -- 开考提醒投递时间；NULL=尚未投递（扫描候选）
    created_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_reservation_exam_student UNIQUE (exam_id, student_id),
    KEY idx_reservations_exam (exam_id)             -- 按考试取预约名单/提醒扫描
);

-- 学生消息中心（本期唯一投递方为开考预约提醒；表按 user_id 通用建模，为后续功能留口径）。
-- 端点：本人消息列表（倒序）/ 未读数 / 单条标记已读；鉴权仅要求登录，不特判角色。
-- idx_user_messages_user(user_id, id) 支撑"按用户倒序列出本人消息"（id 自增即时间倒序）。
CREATE TABLE IF NOT EXISTS user_messages (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    user_id      BIGINT        NOT NULL,             -- 收件人 user id
    exam_id      BIGINT                 DEFAULT NULL, -- 关联考试（可为空，通用消息预留）
    title        VARCHAR(128)  NOT NULL,
    content      VARCHAR(512)           DEFAULT '',
    read_flag    TINYINT       NOT NULL DEFAULT 0,   -- 0=未读 1=已读
    created_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_user_messages_user (user_id, id)         -- 本人消息列表倒序
);

-- 考试预约开关（add-exam-reservation）：默认 0 = 维持现状（班级即时进入），
-- 既有考试与既有测试零影响；补考（parent_exam_id != null）不适用预约，建考链仅主考暴露此开关。
-- 仅 status=0（未开始）可改开关，开考后名单与开关一并锁定。
ALTER TABLE exams ADD COLUMN reservation_required TINYINT NOT NULL DEFAULT 0;
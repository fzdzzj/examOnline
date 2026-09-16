-- =============================================================
-- 存量库迁移：为全部 25 张表补主键（fix-schema-mysql-pk, W16）
-- 背景：schema.sql 中 25 张表均为 `id BIGINT NOT NULL AUTO_INCREMENT` 但从未声明 PRIMARY KEY，
--       MySQL 8 要求 AUTO_INCREMENT 列必须是 key（错误 1075），空库执行 schema.sql 建不起来；
--       H2(MODE=MySQL) 容忍该写法，故集成测试全绿却掩盖了新环境无法启动。
-- 适用：**已建库**的 MySQL 环境。
--   新库：直接执行最新的 src/main/resources/schema.sql（该文件已含 PRIMARY KEY），无需本脚本。
--   存量库：必须手工执行本脚本；`CREATE TABLE IF NOT EXISTS` 不会给已存在的表补主键。
-- 幂等性说明（预期报错，可忽略）：
--   表已有主键时，对应 ALTER 会报 "Multiple primary key defined" (errno 1068)；
--   表不存在时会报 "Table 'xxx' doesn't exist" (errno 1146)。
--   两类报错均属预期，逐条执行、跳过失败语句即可，不要包在存储过程里掩盖数据库真实状态。
-- 本脚本只加主键，不改列类型、不加业务列、不删既有 UNIQUE/KEY/FOREIGN KEY。
-- 与 2026-W15-add-dlq-messages.sql 对齐：exam_dlq_messages 在那边已带 PRIMARY KEY，若已执行过，
-- 本脚本对该表的 ALTER 会报 Multiple primary key，忽略即可。
-- =============================================================

ALTER TABLE users              ADD PRIMARY KEY (id);
ALTER TABLE roles              ADD PRIMARY KEY (id);
ALTER TABLE permissions        ADD PRIMARY KEY (id);
ALTER TABLE user_roles         ADD PRIMARY KEY (id);
ALTER TABLE role_permissions   ADD PRIMARY KEY (id);
ALTER TABLE invite_codes       ADD PRIMARY KEY (id);
ALTER TABLE questions          ADD PRIMARY KEY (id);
ALTER TABLE tags               ADD PRIMARY KEY (id);
ALTER TABLE question_tags      ADD PRIMARY KEY (id);
ALTER TABLE papers             ADD PRIMARY KEY (id);
ALTER TABLE paper_questions    ADD PRIMARY KEY (id);
ALTER TABLE paper_snapshots    ADD PRIMARY KEY (id);
ALTER TABLE exams              ADD PRIMARY KEY (id);
ALTER TABLE exam_snapshots     ADD PRIMARY KEY (id);
ALTER TABLE exam_submissions   ADD PRIMARY KEY (id);
ALTER TABLE subjective_grades  ADD PRIMARY KEY (id);
ALTER TABLE score_audit_logs   ADD PRIMARY KEY (id);
ALTER TABLE exam_submit_dedups ADD PRIMARY KEY (id);
ALTER TABLE exam_behavior_logs ADD PRIMARY KEY (id);
ALTER TABLE classes            ADD PRIMARY KEY (id);
ALTER TABLE user_class         ADD PRIMARY KEY (id);
ALTER TABLE exam_absence       ADD PRIMARY KEY (id);
ALTER TABLE exam_candidates    ADD PRIMARY KEY (id);
ALTER TABLE score_review       ADD PRIMARY KEY (id);
ALTER TABLE exam_dlq_messages  ADD PRIMARY KEY (id);
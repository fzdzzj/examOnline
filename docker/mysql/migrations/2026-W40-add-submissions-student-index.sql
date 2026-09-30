-- =============================================================
-- 存量库迁移：学生考试列表答卷渠道索引 idx_submissions_student
-- 适用：已建库的 MySQL 环境。新建库由 schema.sql 一次建全，无需本脚本。
--
-- 为什么需要：
--   ExamTakingService.myExams() 答卷渠道按 student_id 查询本人答卷。
--   原表无 student_id 打头索引，结构上只能全表扫描。实测 10 万答卷耗时 260~333ms、
--   40 万答卷耗时 1.1~1.4s；补入 idx_submissions_student (student_id) 索引后
--   耗时降至 0.06~0.15ms，扫描行数减少 560~2400 倍，通过全部 B1–B5 门禁。
--
-- 幂等性说明：
--   重复执行 MySQL 8 报错误码 1061 (42000): Duplicate key name 'idx_submissions_student'，可忽略。
--
-- 运维警示：
--   本目录没有自动执行者，脚本入库 ≠ 迁移已生效，存量库需人工按顺序应用并验证。
-- =============================================================

ALTER TABLE exam_submissions
    ADD KEY idx_submissions_student (student_id);

-- =============================================================
-- 存量库迁移：学生考试列表答卷渠道索引 idx_submissions_student
-- 适用：已建库的 MySQL 环境。新建库由 schema.sql 一次建全，无需本脚本。
--
-- 为什么需要：
--   ExamTakingService.myExams() 答卷渠道按 student_id 查询本人答卷。
--   原表无 student_id 打头索引，真 8.0 容器 EXPLAIN 实测走全表扫描（type=ALL）：
--   n1 基线耗时 229~333ms、n2 基线耗时 1099~1442ms（均 A0∪A0rep 全轮）；补入
--   idx_submissions_student (student_id) 索引后耗时 0.060~0.162ms（采纳臂两形状全轮），
--   逐轮扫描行数比恒定（n1 10051 倍 / n2 40001 倍），通过全部 B1–B5 门禁。
--
-- 幂等性说明：
--   重复执行 MySQL 8 报错误码 1061 (42000): Duplicate key name 'idx_submissions_student'，可忽略。
--
-- 运维警示：
--   本目录没有自动执行者，脚本入库 ≠ 迁移已生效，存量库需人工按顺序应用并验证。
-- =============================================================

ALTER TABLE exam_submissions
    ADD KEY idx_submissions_student (student_id);

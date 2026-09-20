-- =============================================================
-- 存量库迁移：补发对账扫描索引（idx_submissions_republish）
-- 适用：已建库的 MySQL 环境。新建库由 schema.sql 一次建全，无需本脚本。
--
-- 为什么要这条索引：
--   selectSubmittedWithoutAnswers 找"已交卷但 answers 仍为 NULL"的答卷。健康系统里该集合恒空，
--   而 answers IS NULL 不在任何索引里，MySQL 只能沿 idx_submissions_sweep 的 status=2 区间
--   把全部已交卷行读穿才敢返回 0 行。10 万答卷实测 411–519ms，且该扫描每 10 秒一轮、
--   成本随交卷总量线性增长。加索引后 rows=3、0.05ms。
--
-- 为什么用虚拟生成列而不是前缀索引 (status, answers(2))：
--   前缀索引在 MySQL 上同样有效（实测一致），但 H2 不认前缀语法，而本项目两端共用一份
--   schema.sql——索引必须两端都能建。生成列由 answers 算出，不存在与 answers 不一致的可能。
--
-- 幂等性说明：重复执行 MySQL 8 会报 duplicate column / key already exists，可忽略。
-- 代价：ADD COLUMN ... VIRTUAL 是 INSTANT 级（不复制表）；随后建索引需扫一遍表。
-- =============================================================

ALTER TABLE exam_submissions
    ADD COLUMN answers_missing TINYINT AS (CASE WHEN answers IS NULL THEN 1 ELSE 0 END) VIRTUAL;

ALTER TABLE exam_submissions
    ADD KEY idx_submissions_republish (status, answers_missing);

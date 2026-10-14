-- =============================================================
-- 存量库迁移：试卷模板标记列（add-paper-template-clone）
-- 适用：已建库的 MySQL 环境。schema.sql 走 CREATE TABLE IF NOT EXISTS——
-- 不会为"已存在"的 papers 表补建 is_template 列，存量库必须手工执行本脚本
-- （dev 可选，prod SQL_INIT_MODE=never 必须执行）。
-- 新建库由 schema.sql 一次建全，无需本脚本。
--
-- 幂等性说明：
--   加列用 ALTER TABLE ... ADD COLUMN，重复执行时 MySQL 8 报
--   ERROR 1060 (42000): Duplicate column name 'is_template'，可忽略
--   （对齐 W41/W42 先例：复跑仅 1060 重复列名错误为预期）。
--
-- 运维警示：
--   本目录没有自动执行者，脚本入库 ≠ 迁移已生效，存量库需人工按顺序应用并验证；
--   dev 库应用时机由用户另行裁决派发，不随本案自动生效。
-- =============================================================

-- 模板标记：0=普通试卷 1=模板卷。默认 0 使存量全部试卷行为零变化。
ALTER TABLE papers ADD COLUMN is_template TINYINT NOT NULL DEFAULT 0;

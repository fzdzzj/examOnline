-- =============================================================
-- 存量库迁移：score_review 补 created_time 列（add-post-exam-closure-e2e 阶段 5，W15）
-- 适用：已建库的 MySQL 环境。schema.sql 走 CREATE TABLE IF NOT EXISTS——
-- 不会为"已存在"的表补列，这是本仓库的既有坑（classes/user_class
-- 那次就是这么翻车的），存量库必须手工执行本脚本
-- （dev 可选，prod SQL_INIT_MODE=never 必须执行）。
-- 新建库由 schema.sql 一次建全，无需本脚本。
--
-- 为什么补列而不是删实体的 createdTime：
-- MybatisPlusConfig 有全局 MetaObjectHandler.insertFill 自动填充 createdTime，
-- 全库 19 个实体 / 18 张表都依此约定，score_review 不应成为唯一例外。
--
-- 幂等性说明：本脚本是 ALTER TABLE ... ADD COLUMN，重复执行时 MySQL 8
-- 报 Duplicate column name，可忽略。
-- =============================================================

ALTER TABLE score_review ADD COLUMN created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP;

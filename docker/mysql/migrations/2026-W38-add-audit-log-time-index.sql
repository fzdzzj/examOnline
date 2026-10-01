-- =============================================================
-- 存量库迁移：audit_log 的按年龄清理索引 idx_audit_time
-- 适用：已建库的 MySQL 环境（含已跑过 2026-W16-add-audit-log.sql 的库）。
--       新建库由 schema.sql 一次建全，无需本脚本。
--
-- 为什么需要：RetentionService 第二阶段按 created_time 清 audit_log。
--   没有这条索引时，"没有东西可删"这一稳态也要把整张表读穿才敢返回 0——
--   10 万行实测 34ms（冷跑更大），且随行数线性增长；有索引 0.16–0.7ms 且与表大小无关。
--   真正要删的那一批也快 4 倍：12ms vs 47ms（每 1000 行）。
--   本仓库原先对这类表一律"只按 exam_id 删、禁止 WHERE created_time < ?"，
--   因为无索引即全表扫描；audit_log 没有 exam_id，只能反过来把索引补上。
--
-- 幂等性说明：重复执行 MySQL 8 报 Duplicate key name，可忽略。
-- =============================================================

ALTER TABLE audit_log
    ADD KEY idx_audit_time (created_time);

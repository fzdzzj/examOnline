-- =============================================================
-- 5000 并发交卷压测 · 数据清理（add-submit-loadtest）
--
-- 目的：压测结束后把 dev 库恢复为压测前状态（共享 dev 库不残留压测数据）。
-- 命名空间：用户前缀 lt5k_ + 固定标题字符串；本脚本只删这两类，绝不触碰既有业务数据。
-- 幂等：可重复执行。
--
-- 用法：mysql -h127.0.0.1 -P13316 -uroot -p exam_online < 03-cleanup.sql
-- 执行后看本文件末尾的自证查询：leftover_* 三项均须为 0。
-- 需要「与压测前逐表行数对比」时，自己在同一台库上前后各跑一遍等价的 COUNT 查询并留存
-- （本资产不预置基线数值文件——数值会随 dev 库使用漂移，写死就是制造过期常量）。
-- =============================================================

-- 顺序遵循引用方向：先删引用方（答卷/防重/行为日志/角色绑定），再删用户与考试。
DELETE d  FROM exam_submit_dedups  d JOIN users u ON d.student_id  = u.id WHERE u.username LIKE 'lt5k\_%';
DELETE s  FROM exam_submissions    s JOIN users u ON s.student_id  = u.id WHERE u.username LIKE 'lt5k\_%';
DELETE b  FROM exam_behavior_logs  b JOIN users u ON b.student_id  = u.id WHERE u.username LIKE 'lt5k\_%';
DELETE FROM audit_log WHERE username LIKE 'lt5k\_%';   -- 覆盖登录成功/失败两类审计行（user_id 可能为 NULL）
DELETE ur FROM user_roles          ur JOIN users u ON ur.user_id   = u.id WHERE u.username LIKE 'lt5k\_%';
DELETE FROM users WHERE username LIKE 'lt5k\_%';

DELETE es FROM exam_snapshots es JOIN exams e ON es.exam_id = e.id WHERE e.title = 'LOADTEST-5000-并发交卷';
DELETE FROM exams  WHERE title = 'LOADTEST-5000-并发交卷';
DELETE FROM papers WHERE title = 'LOADTEST-5000-并发交卷-试卷';

-- 清理后自证：三项均应为 0
SELECT (SELECT COUNT(*) FROM users            WHERE username LIKE 'lt5k\_%')          AS leftover_users,
       (SELECT COUNT(*) FROM exams            WHERE title = 'LOADTEST-5000-并发交卷') AS leftover_exams,
       (SELECT COUNT(*) FROM exam_submissions s JOIN exams e ON s.exam_id = e.id
          WHERE e.title = 'LOADTEST-5000-并发交卷')                                   AS leftover_submissions;

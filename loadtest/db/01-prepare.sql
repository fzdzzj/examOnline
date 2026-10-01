-- =============================================================
-- 5000 并发交卷压测 · 数据准备（add-submit-loadtest）
--
-- 幂等：可重复执行。先按「压测命名空间」精确清理（用户前缀 lt5k_ + 固定标题），
--       再重建——不触碰 dev 库里任何既有业务数据。
-- 用法：由 loadtest/prepare-data.sh 调用（它负责把 __PWD_HASH__ 替换为真实 BCrypt 哈希）。
--       __PWD_HASH__ = 通过 POST /api/auth/register 注册的模板学生口令哈希，
--       用应用自己的 BCrypt 生成，保证 5000 个学生口径与真实注册完全一致。
--
-- 造出什么：
--   1 场 published=1 且 status=1（进行中）的考试
--   5000 个学生账号（绑定 STUDENT 角色）
--   5000 条 exam_submissions（status=1 进行中）——等价于「5000 人已开考、正待交卷」
--
-- 为什么直接 INSERT 答卷而不走 /enter：交卷峰值窗口里学生**早已进入考试**，
--   enter 属开考期前置而非峰值路径；且 submit 的服务层只读 exam_submissions，
--   不读 exam_snapshots/paper_json（见 ExamSubmitService.doSubmit），
--   因此预置答卷与真实「已进入考试」状态对压测目标路径等价。
-- 关键约束：deadline_time / end_time 必须显著晚于压测时刻，
--   否则 ExamSweepService（10s 一轮的超时兜底）会把答卷提前强制交卷，污染压测。
-- =============================================================

SET SESSION cte_max_recursion_depth = 20000;

-- ---------- 1) 幂等清理（只动压测命名空间） ----------
DELETE d FROM exam_submit_dedups d JOIN users u ON d.student_id = u.id WHERE u.username LIKE 'lt5k\_%';
DELETE s FROM exam_submissions s JOIN users u ON s.student_id = u.id WHERE u.username LIKE 'lt5k\_%';
DELETE b FROM exam_behavior_logs b JOIN users u ON b.student_id = u.id WHERE u.username LIKE 'lt5k\_%';
DELETE ur FROM user_roles ur JOIN users u ON ur.user_id = u.id WHERE u.username LIKE 'lt5k\_%';
DELETE FROM users WHERE username LIKE 'lt5k\_%';
DELETE es FROM exam_snapshots es JOIN exams e ON es.exam_id = e.id WHERE e.title = 'LOADTEST-5000-并发交卷';
DELETE FROM exams WHERE title = 'LOADTEST-5000-并发交卷';
DELETE FROM papers WHERE title = 'LOADTEST-5000-并发交卷-试卷';

-- ---------- 2) 压测专用试卷（无题目，仅满足 exams.paper_id NOT NULL；submit 路径不读试卷） ----------
INSERT INTO papers (title, description, total_score, question_count, status, created_by)
SELECT 'LOADTEST-5000-并发交卷-试卷', '压测专用：无题目，仅满足 exams.paper_id 非空约束',
       0, 0, 0, (SELECT id FROM users WHERE username = 'admin');

-- ---------- 3) 压测专用考试（published=1 + status=1 进行中，时间窗覆盖压测全程） ----------
INSERT INTO exams (title, description, paper_id, start_time, end_time, duration_minutes,
                   allow_late_minutes, status, published, force_end, version, created_by)
SELECT 'LOADTEST-5000-并发交卷', '压测专用考试（add-submit-loadtest）',
       p.id,
       NOW() - INTERVAL 10 MINUTE,   -- 已开始
       NOW() + INTERVAL 4 HOUR,      -- 远晚于压测：避免自然结束
       180,                          -- 个人时长 180 分钟
       0, 1, 1, 0, 0,
       (SELECT id FROM users WHERE username = 'admin')
FROM papers p
WHERE p.title = 'LOADTEST-5000-并发交卷-试卷';

-- ---------- 4) 5000 个学生账号 ----------
INSERT INTO users (username, password, name, email, status, must_change_password, is_deleted)
WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 5000)
SELECT CONCAT('lt5k_', LPAD(n, 4, '0')),
       '__PWD_HASH__',
       CONCAT('压测学生', n),
       NULL,
       0,   -- status 语义：0=正常 1=禁用（AuthService.login 对 status==1 抛 ACCOUNT_DISABLED）
       0,   -- must_change_password=0
       0
FROM seq;

-- ---------- 5) 绑定 STUDENT 角色（复用 RolePermissionInitializer 已预置的角色，不新建权限） ----------
INSERT INTO user_roles (user_id, role_id)
SELECT u.id, (SELECT id FROM roles WHERE code = 'STUDENT')
FROM users u
WHERE u.username LIKE 'lt5k\_%';

-- ---------- 6) 5000 条「进行中」答卷 ----------
INSERT INTO exam_submissions (exam_id, student_id, start_time, deadline_time, status, version)
SELECT e.id, u.id,
       NOW() - INTERVAL 10 MINUTE,   -- 个人开始时间
       NOW() + INTERVAL 4 HOUR,      -- 个人截止：远晚于压测（见文件头「关键约束」）
       1,                            -- STATUS_IN_PROGRESS
       0
FROM users u
JOIN exams e ON e.title = 'LOADTEST-5000-并发交卷'
WHERE u.username LIKE 'lt5k\_%';

-- ---------- 7) 准备结果自证（执行后应由 prepare-data.sh 打印） ----------
SELECT (SELECT id FROM exams WHERE title = 'LOADTEST-5000-并发交卷')                       AS exam_id,
       (SELECT COUNT(*) FROM users WHERE username LIKE 'lt5k\_%')                          AS students,
       (SELECT COUNT(*) FROM exam_submissions s JOIN exams e ON s.exam_id = e.id
          WHERE e.title = 'LOADTEST-5000-并发交卷' AND s.status = 1)                       AS submissions_in_progress;

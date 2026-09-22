-- =============================================================
-- 5000 并发交卷压测 · 复跑重置（add-submit-loadtest）
--
-- 为什么需要它：01-prepare.sql 造一次数据要 ~7s（5000 用户 + 5000 答卷），
--   而"同一场景换参数复跑"只需把答卷退回「待交卷」。本脚本把压测命名空间
--   原地复位，不重建数据、不动任何既有业务数据。
--
-- 复位的三样东西（缺一不可，否则复跑结果不可信）：
--   1) exam_submissions 退回 status=1、清 submit_time/answers/version
--      —— 否则 submit 会撞 uk_exam_student 或走"已交卷"分支，压不到目标路径；
--   2) exam_submit_dedups 清空
--      —— 否则三重幂等的"防重表"这一重会直接把请求判为重复，全部提前返回；
--   3) MQ 队列必须已归零（脚本外核验：管理 API messages_ready=0 且 unacked=0）
--      —— 否则上一轮的积压会算进这一轮的落库时效。本脚本不含该动作，
--         因为清队列会真实丢弃消息，须由 run-loadtest.sh 的前置检查确认归零。
--
-- 用法：mysql -h127.0.0.1 -P13316 -uroot -p exam_online -t < 04-reset.sql
-- 幂等：可重复执行。
-- =============================================================

SET @exam_id = (SELECT id FROM exams WHERE title = 'LOADTEST-5000-并发交卷');

DELETE d FROM exam_submit_dedups d WHERE d.exam_id = @exam_id;

DELETE b FROM exam_behavior_logs b
  JOIN users u ON b.student_id = u.id
 WHERE u.username LIKE 'lt5k\_%';

-- answers_missing 是生成列，随 answers 自动归位，不可（也无需）显式赋值
UPDATE exam_submissions s
   SET s.status      = 1,
       s.submit_time = NULL,
       s.submit_type = NULL,
       s.answers     = NULL,
       s.version     = 0,
       s.updated_time = NOW()
 WHERE s.exam_id = @exam_id;

-- 复位自证：in_progress 须 5000，submitted / dedups / answers_nonnull 须 0
SELECT (SELECT COUNT(*) FROM exam_submissions WHERE exam_id = @exam_id AND status = 1)          AS in_progress_5000,
       (SELECT COUNT(*) FROM exam_submissions WHERE exam_id = @exam_id AND status = 2)          AS submitted_should_0,
       (SELECT COUNT(*) FROM exam_submit_dedups WHERE exam_id = @exam_id)                       AS dedups_should_0,
       (SELECT COUNT(*) FROM exam_submissions
         WHERE exam_id = @exam_id AND answers IS NOT NULL)                                      AS answers_should_0;

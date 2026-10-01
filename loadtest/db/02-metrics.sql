-- =============================================================
-- 5000 并发交卷压测 · 指标采集（add-submit-loadtest）
--
-- 「0 丢单」的证据口径：交卷接口返回 2xx 即代表系统已接受该笔交卷
-- （状态机 CAS 已把 status 置 2，submit_time 已落库），答案由 MQ 消费者异步批量落库。
-- 因此「丢单」= status=2 但 answers IS NULL（消息丢失 / 消费未落库）。
--
-- 「批量落库完成时长」两侧端点都取自 DB 列，不依赖脚本轮询精度：
--   左端 = MAX(submit_time)                     —— 最后一笔被接受（入队）的时刻
--   右端 = MAX(updated_time where answers 非空) —— 最后一条答案落库的时刻
--   （casFillAnswers 与 casSubmit 都显式写 updated_time = CURRENT_TIMESTAMP，
--    故该列的 MAX 就是最后一次答案写入的时刻）
-- 两侧若来自不同时钟（submit_time 取 JVM、updated_time 取 DB）会引入偏差，
--   故附 clock_skew_s 供核对（容器与宿主同钟，预期 ≈0）。
--
-- 用法：mysql -h127.0.0.1 -P13316 -uroot -p exam_online -t < 02-metrics.sql
-- 采集时机：提交阶段结束后，等 MQ 队列深度归零再执行。
-- =============================================================

SET @exam_id = (SELECT id FROM exams WHERE title = 'LOADTEST-5000-并发交卷');

-- ---------- A. 卷面计数（丢单口径） ----------
SELECT '答卷总行数'                       AS metric,
       (SELECT COUNT(*) FROM exam_submissions WHERE exam_id = @exam_id) AS value
UNION ALL SELECT '进行中(status=1)',
       (SELECT COUNT(*) FROM exam_submissions WHERE exam_id = @exam_id AND status = 1)
UNION ALL SELECT '已交卷(status=2)',
       (SELECT COUNT(*) FROM exam_submissions WHERE exam_id = @exam_id AND status = 2)
UNION ALL SELECT '★丢单: status=2 且 answers IS NULL',
       (SELECT COUNT(*) FROM exam_submissions WHERE exam_id = @exam_id AND status = 2 AND answers IS NULL)
UNION ALL SELECT '★丢单(生成列口径): status=2 且 answers_missing=1',
       (SELECT COUNT(*) FROM exam_submissions WHERE exam_id = @exam_id AND status = 2 AND answers_missing = 1)
UNION ALL SELECT '防重表行数(exam_submit_dedups)',
       (SELECT COUNT(*) FROM exam_submit_dedups WHERE exam_id = @exam_id)
UNION ALL SELECT '答案总字节数',
       (SELECT IFNULL(SUM(LENGTH(answers)), 0) FROM exam_submissions WHERE exam_id = @exam_id);

-- ---------- B. 落库时效 ----------
SELECT (SELECT MAX(submit_time) FROM exam_submissions
          WHERE exam_id = @exam_id AND status = 2)                        AS last_submit_accepted_at,
       (SELECT MAX(updated_time) FROM exam_submissions
          WHERE exam_id = @exam_id AND status = 2 AND answers IS NOT NULL) AS last_answers_persisted_at,
       TIMESTAMPDIFF(MICROSECOND,
          (SELECT MIN(submit_time) FROM exam_submissions WHERE exam_id = @exam_id AND status = 2),
          (SELECT MAX(submit_time) FROM exam_submissions WHERE exam_id = @exam_id AND status = 2)
       ) / 1000.0                                                         AS submit_window_ms,
       TIMESTAMPDIFF(MICROSECOND,
          (SELECT MAX(submit_time) FROM exam_submissions
             WHERE exam_id = @exam_id AND status = 2),
          (SELECT MAX(updated_time) FROM exam_submissions
             WHERE exam_id = @exam_id AND status = 2 AND answers IS NOT NULL)
       ) / 1000.0                                                         AS persist_tail_ms;

-- ---------- C. 时钟偏差自证（应 ≈0） ----------
SELECT NOW()                                          AS db_now,
       (SELECT MAX(submit_time) FROM exam_submissions WHERE exam_id = @exam_id) AS max_app_submit_time,
       TIMESTAMPDIFF(MICROSECOND, (SELECT MAX(submit_time) FROM exam_submissions WHERE exam_id = @exam_id), NOW())
         / 1000.0                                     AS clock_skew_s;

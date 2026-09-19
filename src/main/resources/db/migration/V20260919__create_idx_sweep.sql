-- Create composite index for sweep query optimization
-- Purpose: Optimize ExamSubmissionMapper.selectForceSubmitCandidates() performance
-- Query pattern: WHERE status = 1 AND (deadline_time < #{now} OR e.status IN (2, 3))
-- Index covers the most selective predicate: status + deadline_time combination
--
-- Performance impact:
-- - Before: Full table scan on exam_submissions (~N rows)
-- - After: Index range scan on idx_sweep_candidate (~M rows where M << N)
-- Expected improvement: 10-100x faster for sweep queries with 100k+ records
--
-- Trade-offs:
-- - Index write overhead: ~5-10% slower INSERT/UPDATE on exam_submissions
-- - Index size: ~10-20MB for 1M rows (depends on row size)
-- - Maintenance: Monitor index fragmentation after bulk updates
--
-- Rollback if needed:
-- DROP INDEX idx_sweep_candidate ON exam_submissions;

CREATE INDEX idx_sweep_candidate 
ON exam_submissions(status, deadline_time);

-- Verify index creation:
-- EXPLAIN SELECT s.* FROM exam_submissions s 
-- JOIN exams e ON e.id = s.exam_id AND e.is_deleted = 0 
-- WHERE s.status = 1 AND s.deadline_time < '2026-09-19 12:00:00' LIMIT 500;
-- Should show: Using index for type=range, ref=null

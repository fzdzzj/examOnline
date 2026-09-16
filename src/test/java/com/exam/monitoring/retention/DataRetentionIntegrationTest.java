package com.exam.monitoring.retention;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.score.entity.ScoreAuditLog;
import com.exam.score.mapper.ScoreAuditLogMapper;
import com.exam.submission.entity.ExamBehaviorLog;
import com.exam.submission.entity.ExamSubmitDedup;
import com.exam.submission.mapper.ExamBehaviorLogMapper;
import com.exam.submission.mapper.ExamSubmitDedupMapper;
import com.exam.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据保留策略真表集成测试（add-data-retention）：
 * H2 + schema.sql 唯一来源，禁止 @Sql 自建表。
 * 证明：dry-run 不删、只清终态旧考试、进行中不动、批上限生效。
 * 不声称磁盘释放；真删仅在 dry-run=false 的用例中发生。
 */
class DataRetentionIntegrationTest extends IntegrationTestBase {

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RetentionService retentionService;
    @Autowired
    private ExamMapper examMapper;
    @Autowired
    private ExamBehaviorLogMapper behaviorLogMapper;
    @Autowired
    private ExamSubmitDedupMapper submitDedupMapper;
    @Autowired
    private ScoreAuditLogMapper scoreAuditLogMapper;

    @BeforeEach
    void resetRetentionDefaults() {
        // 默认安全闸：enabled 不影响直接调 purgeOnce；dry-run 默认 true；保留 180 天
        ReflectionTestUtils.setField(retentionService, "enabled", false);
        ReflectionTestUtils.setField(retentionService, "dryRun", true);
        ReflectionTestUtils.setField(retentionService, "retentionDays", 180);
        ReflectionTestUtils.setField(retentionService, "batchSize", 1000);
        ReflectionTestUtils.setField(retentionService, "maxBatchesPerRun", 20);
        ReflectionTestUtils.setField(retentionService, "maxExamsPerRun", 100);
        // H2 跨用例保留：清空三张辅助表，避免历史行污染候选量/删除量断言
        behaviorLogMapper.delete(Wrappers.emptyWrapper());
        submitDedupMapper.delete(Wrappers.emptyWrapper());
        scoreAuditLogMapper.delete(Wrappers.emptyWrapper());
    }

    @Test
    @DisplayName("dry-run: hit old terminal candidates, delete nothing, exams unchanged")
    void dryRunDeletesNothing() {
        SeededExams seeded = seedThreeExams(3, 2, 2);

        long examsBefore = examMapper.selectCount(Wrappers.emptyWrapper());
        long behaviorBefore = behaviorLogMapper.selectCount(Wrappers.emptyWrapper());
        long dedupBefore = submitDedupMapper.selectCount(Wrappers.emptyWrapper());
        long auditBefore = scoreAuditLogMapper.selectCount(Wrappers.emptyWrapper());

        ReflectionTestUtils.setField(retentionService, "dryRun", true);
        RetentionReport report = retentionService.purgeOnce();

        assertTrue(report.dryRun(), "should be dry-run");
        assertTrue(report.examsProcessed() >= 1, "should hit old terminal exam a");
        assertEquals(3, report.behaviorCandidates(), "a behavior candidates=3");
        assertEquals(2, report.dedupCandidates(), "a dedup candidates=2");
        assertEquals(2, report.auditCandidates(), "a audit candidates=2");
        assertEquals(0, report.behaviorDeleted());
        assertEquals(0, report.dedupDeleted());
        assertEquals(0, report.auditDeleted());
        assertEquals(0, report.totalDeleted());

        assertEquals(examsBefore, examMapper.selectCount(Wrappers.emptyWrapper()), "exams must not be deleted");
        assertEquals(behaviorBefore, behaviorLogMapper.selectCount(Wrappers.emptyWrapper()));
        assertEquals(dedupBefore, submitDedupMapper.selectCount(Wrappers.emptyWrapper()));
        assertEquals(auditBefore, scoreAuditLogMapper.selectCount(Wrappers.emptyWrapper()));

        assertEquals(3, behaviorLogMapper.countByExamId(seeded.oldTerminalId()));
        assertEquals(3, behaviorLogMapper.countByExamId(seeded.recentTerminalId()));
        assertEquals(3, behaviorLogMapper.countByExamId(seeded.inProgressId()));
    }

    @Test
    @DisplayName("live purge: only old terminal a cleaned; b and c untouched")
    void purgeRemovesOnlyTerminalOldExams() {
        SeededExams seeded = seedThreeExams(4, 3, 2);

        ReflectionTestUtils.setField(retentionService, "dryRun", false);
        RetentionReport report = retentionService.purgeOnce();

        assertEquals(false, report.dryRun());
        assertTrue(report.examsProcessed() >= 1);
        assertEquals(4, report.behaviorDeleted(), "a behavior deleted");
        assertEquals(3, report.dedupDeleted(), "a dedup deleted");
        assertEquals(2, report.auditDeleted(), "a audit deleted");

        assertEquals(0, behaviorLogMapper.countByExamId(seeded.oldTerminalId()));
        assertEquals(0, submitDedupMapper.countByExamId(seeded.oldTerminalId()));
        assertEquals(0, scoreAuditLogMapper.countByExamId(seeded.oldTerminalId()));

        assertEquals(4, behaviorLogMapper.countByExamId(seeded.recentTerminalId()), "b behavior kept");
        assertEquals(3, submitDedupMapper.countByExamId(seeded.recentTerminalId()), "b dedup kept");
        assertEquals(2, scoreAuditLogMapper.countByExamId(seeded.recentTerminalId()), "b audit kept");

        assertEquals(4, behaviorLogMapper.countByExamId(seeded.inProgressId()), "c behavior kept");
        assertEquals(3, submitDedupMapper.countByExamId(seeded.inProgressId()), "c dedup kept");
        assertEquals(2, scoreAuditLogMapper.countByExamId(seeded.inProgressId()), "c audit kept");

        assertTrue(examMapper.selectById(seeded.oldTerminalId()) != null);
        assertTrue(examMapper.selectById(seeded.recentTerminalId()) != null);
        assertTrue(examMapper.selectById(seeded.inProgressId()) != null);
    }

    @Test
    @DisplayName("batch bound: single run stops at batch-size * max-batches")
    void batchBoundIsRespected() {
        long examId = insertExam("ret_bound_old", Exam.STATUS_ENDED,
                LocalDateTime.of(1990, 1, 1, 0, 0), LocalDateTime.of(1990, 1, 2, 0, 0));
        insertBehaviorRows(examId, 25);
        insertDedupRows(examId, 3);
        insertAuditRows(examId, 2);

        ReflectionTestUtils.setField(retentionService, "dryRun", false);
        ReflectionTestUtils.setField(retentionService, "batchSize", 5);
        ReflectionTestUtils.setField(retentionService, "maxBatchesPerRun", 2);

        long behaviorBefore = behaviorLogMapper.countByExamId(examId);
        long dedupBefore = submitDedupMapper.countByExamId(examId);
        long auditBefore = scoreAuditLogMapper.countByExamId(examId);

        RetentionReport report = retentionService.purgeOnce();

        long behaviorAfter = behaviorLogMapper.countByExamId(examId);
        long dedupAfter = submitDedupMapper.countByExamId(examId);
        long auditAfter = scoreAuditLogMapper.countByExamId(examId);

        long behaviorDelta = behaviorBefore - behaviorAfter;
        long dedupDelta = dedupBefore - dedupAfter;
        long auditDelta = auditBefore - auditAfter;

        assertEquals(25, report.behaviorCandidates());
        assertEquals(10, report.behaviorDeleted(), "behavior capped at 5*2=10");
        assertEquals(10, behaviorDelta);
        assertEquals(15, behaviorAfter, "15 rows remain for later runs");

        assertEquals(3, report.dedupCandidates());
        assertEquals(3, report.dedupDeleted());
        assertEquals(3, dedupDelta);
        assertEquals(0, dedupAfter);

        assertEquals(2, report.auditCandidates());
        assertEquals(2, report.auditDeleted());
        assertEquals(2, auditDelta);
        assertEquals(0, auditAfter);
    }

    private SeededExams seedThreeExams(int behaviorPerExam, int dedupPerExam, int auditPerExam) {
        long oldTerminal = insertExam("ret_a_old_terminal", Exam.STATUS_ENDED,
                LocalDateTime.of(1990, 1, 1, 0, 0), LocalDateTime.of(1990, 1, 2, 0, 0));
        long recentTerminal = insertExam("ret_b_recent_terminal", Exam.STATUS_ENDED,
                LocalDateTime.now().minusDays(10), LocalDateTime.now().minusDays(1));
        long inProgress = insertExam("ret_c_in_progress_old_end", Exam.STATUS_IN_PROGRESS,
                LocalDateTime.of(1990, 1, 1, 0, 0), LocalDateTime.of(1990, 1, 2, 0, 0));

        for (long examId : new long[]{oldTerminal, recentTerminal, inProgress}) {
            insertBehaviorRows(examId, behaviorPerExam);
            insertDedupRows(examId, dedupPerExam);
            insertAuditRows(examId, auditPerExam);
        }
        return new SeededExams(oldTerminal, recentTerminal, inProgress);
    }

    private long insertExam(String title, int status, LocalDateTime start, LocalDateTime end) {
        Exam exam = new Exam();
        exam.setTitle(title);
        exam.setDescription("retention-test");
        exam.setPaperId(1L);
        exam.setStartTime(start);
        exam.setEndTime(end);
        exam.setDurationMinutes(90);
        exam.setAllowLateMinutes(0);
        exam.setStatus(status);
        exam.setPublished(1);
        exam.setForceEnd(0);
        exam.setVersion(0);
        exam.setCreatedBy(1L);
        examMapper.insert(exam);
        return exam.getId();
    }

    private void insertBehaviorRows(long examId, int n) {
        for (int i = 0; i < n; i++) {
            ExamBehaviorLog row = new ExamBehaviorLog();
            row.setExamId(examId);
            row.setStudentId(1000L + i);
            row.setEventType("SWITCH_SCREEN");
            row.setEventData("{\"i\":" + i + "}");
            row.setSeverity(1);
            row.setEventTime(LocalDateTime.now().minusDays(10));
            behaviorLogMapper.insert(row);
        }
    }

    private void insertDedupRows(long examId, int n) {
        for (int i = 0; i < n; i++) {
            ExamSubmitDedup row = new ExamSubmitDedup();
            row.setExamId(examId);
            row.setStudentId(2000L + i);
            row.setSubmissionId(3000L + i);
            row.setSubmitType(1);
            submitDedupMapper.insert(row);
        }
    }

    private void insertAuditRows(long examId, int n) {
        for (int i = 0; i < n; i++) {
            ScoreAuditLog row = new ScoreAuditLog();
            row.setExamId(examId);
            row.setAction(i % 2 == 0 ? ScoreAuditLog.ACTION_PUBLISH : ScoreAuditLog.ACTION_REVOKE);
            row.setOperatorId(1L);
            row.setReason(i % 2 == 0 ? null : "test-revoke");
            row.setDetail("retention-" + i);
            scoreAuditLogMapper.insert(row);
        }
    }

    private record SeededExams(long oldTerminalId, long recentTerminalId, long inProgressId) {
    }
}

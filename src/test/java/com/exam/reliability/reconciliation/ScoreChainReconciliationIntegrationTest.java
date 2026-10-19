package com.exam.reliability.reconciliation;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.audit.entity.AuditLog;
import com.exam.audit.mapper.AuditLogMapper;
import com.exam.auth.service.JwtUtil;
import com.exam.exam.entity.Exam;
import com.exam.exam.entity.ExamSnapshot;
import com.exam.exam.mapper.ExamMapper;
import com.exam.exam.mapper.ExamSnapshotMapper;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.entity.SubjectiveGrade;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.grading.mapper.SubjectiveGradeMapper;
import com.exam.grading.service.ObjectiveGradingService;
import com.exam.reliability.reconciliation.entity.ReconciliationDiff;
import com.exam.reliability.reconciliation.entity.ReconciliationRun;
import com.exam.reliability.reconciliation.mapper.ReconciliationDiffMapper;
import com.exam.reliability.reconciliation.mapper.ReconciliationRunMapper;
import com.exam.reliability.reconciliation.service.ReconciliationService;
import com.exam.submission.entity.ExamSubmission;
import com.exam.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 成绩链全链路对账集成测试（spec reliability「成绩链全链路对账」，H2 + MockMvc 全链路）：
 *
 * <ul>
 *   <li>维度一：已判分考试下判分缺失 / 主观题未批完 / 判分完成但总分未回填；</li>
 *   <li>确定性缺失：同源口径幂等补齐 + audit_log + AUTO_FIXED 票，重复对账不二次写；</li>
 *   <li>维度二：判分明细汇总 vs 总分不一致只出票，总分保持不变；</li>
 *   <li>差异票闭环：人工关闭 MANUAL_CLOSED + 审计，非 OPEN 拒绝，列表按状态可查；</li>
 *   <li>权限隔离：STUDENT 调四端点一律 403；</li>
 *   <li>定时兜底：同一时间窗只执行一次（唯一批次键），失败入运行记录且下一次照常执行。</li>
 * </ul>
 *
 * <p>夹具由 Mapper 直插（考试 + 快照 + 答卷 + 主观批改行），全程不建表、不使用 @Sql
 * （AGENTS 硬约定 2）；考试快照的 paper_json 用与 PaperSnapshotService 相同的结构构造。
 */
class ScoreChainReconciliationIntegrationTest extends IntegrationTestBase {

    private static final long OBJECTIVE_QUESTION_ID = 9001L;
    private static final long SHORT_ANSWER_QUESTION_ID = 9002L;

    @Autowired
    private ReconciliationService reconciliationService;
    @Autowired
    private ReconciliationRunMapper runMapper;
    @Autowired
    private ReconciliationDiffMapper diffMapper;
    @Autowired
    private ExamMapper examMapper;
    @Autowired
    private ExamSnapshotMapper examSnapshotMapper;
    @Autowired
    private GradingSubmissionMapper gradingSubmissionMapper;
    @Autowired
    private SubjectiveGradeMapper subjectiveGradeMapper;
    @Autowired
    private AuditLogMapper auditLogMapper;
    @Autowired
    private JwtUtil jwtUtil;

    private String teacher;
    private Long teacherId;
    private String studentToken;
    private Long studentId;

    @Autowired
    private com.exam.submission.mapper.ExamSubmissionMapper examSubmissionMapper;

    @BeforeEach
    void setUpReconcileFixture() throws Exception {
        clearReconcileTables();
        hidePreviousFixtureExams();
        teacher = registerTeacher();
        teacherId = jwtUtil.parseAccessToken(teacher).getId();
        studentToken = registerStudent();
        studentId = jwtUtil.parseAccessToken(studentToken).getId();
    }

    // ==================== 维度一：交卷 vs 判分 ====================

    @Test
    @DisplayName("判分缺失：已判分考试下出 SUBMISSION_UNGRADED 票，不改动答卷判分字段")
    void ungradedSubmissionProducesTicket() throws Exception {
        long examId = createGradedExam(objectiveOnlyPaper());
        long submissionId = seedSubmission(examId, studentId, ExamSubmission.STATUS_SUBMITTED,
                null, null, null, 0, 0);

        JsonNode run = perform(jsonPost("/api/admin/reconciliation/run", teacher,
                "{\"examId\":" + examId + "}"), 200).get("data");

        assertEquals(ReconciliationRun.STATUS_SUCCESS, run.get("status").asText());
        assertEquals(1, run.get("scannedCount").asInt());
        assertEquals(1, run.get("ungradedCount").asInt());
        assertEquals(0, run.get("mismatchCount").asInt());
        assertEquals(0, run.get("autoFixedCount").asInt());
        assertEquals(ReconciliationRun.TRIGGER_MANUAL, run.get("triggerSource").asText());
        assertNotNull(run.get("operatorId"));

        JsonNode diffs = diffsOf(run.get("id").asLong(), ReconciliationDiff.STATUS_OPEN);
        assertEquals(1, diffs.size());
        assertEquals(ReconciliationDiff.TYPE_SUBMISSION_UNGRADED, diffs.get(0).get("diffType").asText());
        assertEquals(ReconciliationDiff.DETAIL_GRADING_MISSING, diffs.get(0).get("detail").asText());
        assertEquals(submissionId, diffs.get(0).get("submissionId").asLong());

        // 只出票，不改分：判分字段保持原样
        GradingSubmission after = gradingSubmissionMapper.selectById(submissionId);
        assertEquals(0, after.getGradingStatus());
        assertNull(after.getTotalScore());
    }

    // ==================== 确定性缺失：幂等补齐 ====================

    @Test
    @DisplayName("确定性缺失：同源口径补齐总分 + 审计，重复对账不二次写（版本与审计计数不变）")
    void deterministicMissingBackfilledIdempotently() throws Exception {
        long examId = createGradedExam(objectiveOnlyPaper());
        long submissionId = seedSubmission(examId, studentId, ExamSubmission.STATUS_SUBMITTED,
                new BigDecimal("5"), null, null, ObjectiveGradingService.GRADING_OK, 0);

        JsonNode first = perform(jsonPost("/api/admin/reconciliation/run", teacher,
                "{\"examId\":" + examId + "}"), 200).get("data");
        assertEquals(1, first.get("autoFixedCount").asInt());

        GradingSubmission fixed = gradingSubmissionMapper.selectById(submissionId);
        assertEquals(0, new BigDecimal("5").compareTo(fixed.getTotalScore()), "总分须按同源口径回填");
        assertEquals(1, fixed.getVersion(), "补齐是一次写：version 只 +1");
        assertEquals(ExamSubmission.STATUS_GRADED, fixed.getStatus());

        JsonNode autoFixed = diffsOf(first.get("id").asLong(), ReconciliationDiff.STATUS_AUTO_FIXED);
        assertEquals(1, autoFixed.size());
        assertEquals(ReconciliationDiff.DETAIL_TOTAL_SCORE_NOT_FILLED, autoFixed.get(0).get("detail").asText());
        assertEquals(1, fixAuditCount(submissionId));
        assertEquals(1, runDiffCount(submissionId, ReconciliationDiff.STATUS_AUTO_FIXED));

        // 第二次对账：总分已回填 → 不重复补齐、不重复审计、不重复出票
        JsonNode second = perform(jsonPost("/api/admin/reconciliation/run", teacher,
                "{\"examId\":" + examId + "}"), 200).get("data");
        assertEquals(0, second.get("autoFixedCount").asInt());
        assertEquals(0, second.get("ungradedCount").asInt());
        assertEquals(0, second.get("mismatchCount").asInt());

        GradingSubmission afterSecond = gradingSubmissionMapper.selectById(submissionId);
        assertEquals(1, afterSecond.getVersion(), "重复对账不得重写总分：version 仍为 1");
        assertEquals(1, fixAuditCount(submissionId));
        assertEquals(1, runDiffCount(submissionId, ReconciliationDiff.STATUS_AUTO_FIXED));

        // 运行历史可查（按考试筛选）
        JsonNode runs = perform(jsonGet("/api/admin/reconciliation/runs?examId=" + examId, teacher),
                200).get("data");
        assertEquals(2, runs.size());
        assertEquals(second.get("id").asLong(), runs.get(0).get("id").asLong(), "运行历史按 id 倒序");
    }

    // ==================== 维度二：判分明细 vs 总分 ====================

    @Test
    @DisplayName("明细与总分不一致：出 DETAIL_TOTAL_MISMATCH 票（期望/实际/偏差），总分不被自动修改")
    void detailTotalMismatchOnlyTickets() throws Exception {
        long examId = createGradedExam(paperWithShortAnswer());
        long mismatchSubmission = seedSubmission(examId, studentId, ExamSubmission.STATUS_SUBMITTED,
                new BigDecimal("5"), new BigDecimal("5"), new BigDecimal("8"),
                ObjectiveGradingService.GRADING_OK, 0);
        seedSubjectiveGrade(examId, mismatchSubmission, studentId, new BigDecimal("5"));
        long partialStudentId = studentId + 1000;
        long partialSubmission = seedSubmission(examId, partialStudentId,
                ExamSubmission.STATUS_SUBMITTED, new BigDecimal("5"), BigDecimal.ZERO,
                new BigDecimal("5"), ObjectiveGradingService.GRADING_OK, 1);
        seedSubjectiveGradeWithoutScore(examId, partialSubmission, partialStudentId);

        JsonNode run = perform(jsonPost("/api/admin/reconciliation/run", teacher,
                "{\"examId\":" + examId + "}"), 200).get("data");
        assertEquals(2, run.get("scannedCount").asInt());
        assertEquals(1, run.get("ungradedCount").asInt(), "主观题未批完计入维度一");
        assertEquals(1, run.get("mismatchCount").asInt(), "明细汇总 10 vs 实际总分 8 计入维度二");
        assertEquals(0, run.get("autoFixedCount").asInt());

        JsonNode mismatch = diffsOf(run.get("id").asLong(), ReconciliationDiff.STATUS_OPEN);
        JsonNode mismatchTicket = findByType(mismatch, ReconciliationDiff.TYPE_DETAIL_TOTAL_MISMATCH);
        assertNotNull(mismatchTicket);
        assertEquals(0, new BigDecimal("10").compareTo(mismatchTicket.get("expectedValue").decimalValue()));
        assertEquals(0, new BigDecimal("8").compareTo(mismatchTicket.get("actualValue").decimalValue()));
        assertEquals(0, new BigDecimal("2").compareTo(mismatchTicket.get("deviation").decimalValue()));

        JsonNode partialTicket = findByType(mismatch, ReconciliationDiff.TYPE_SUBMISSION_UNGRADED);
        assertNotNull(partialTicket);
        assertEquals(ReconciliationDiff.DETAIL_SUBJECTIVE_NOT_FULLY_GRADED, partialTicket.get("detail").asText());

        // 涉分矛盾只出票：库中总分保持 8，绝不被对账"修正"
        GradingSubmission unchanged = gradingSubmissionMapper.selectById(mismatchSubmission);
        assertEquals(0, new BigDecimal("8").compareTo(unchanged.getTotalScore()));
        assertEquals(0, unchanged.getVersion(), "维度二不得写答卷");
    }

    // ==================== 差异票闭环与权限隔离 ====================

    @Test
    @DisplayName("差异票闭环：人工关闭转 MANUAL_CLOSED + 审计，按状态可查，重复关闭 400、不存在 404")
    void closeDiffMarksManualClosedAndAudits() throws Exception {
        long examId = createGradedExam(paperWithShortAnswer());
        long submissionId = seedSubmission(examId, studentId, ExamSubmission.STATUS_SUBMITTED,
                new BigDecimal("5"), new BigDecimal("5"), new BigDecimal("8"),
                ObjectiveGradingService.GRADING_OK, 0);
        seedSubjectiveGrade(examId, submissionId, studentId, new BigDecimal("5"));

        JsonNode run = perform(jsonPost("/api/admin/reconciliation/run", teacher,
                "{\"examId\":" + examId + "}"), 200).get("data");
        long diffId = diffsOf(run.get("id").asLong(), ReconciliationDiff.STATUS_OPEN).get(0).get("id").asLong();

        JsonNode closed = perform(jsonPost("/api/admin/reconciliation/diffs/" + diffId + "/close",
                teacher, "{\"reason\":\"已人工核对纸质卷\"}"), 200).get("data");
        assertEquals(ReconciliationDiff.STATUS_MANUAL_CLOSED, closed.get("status").asText());
        assertNotNull(closed.get("closedBy"));
        assertNotNull(closed.get("closedTime"));

        JsonNode closedList = perform(jsonGet("/api/admin/reconciliation/diffs?status="
                + ReconciliationDiff.STATUS_MANUAL_CLOSED, teacher), 200).get("data");
        assertEquals(1, closedList.size());
        assertEquals(diffId, closedList.get(0).get("id").asLong());
        assertEquals(1, closeAuditCount(diffId));

        // 非 OPEN 不可重复关闭；不存在的票 404
        perform(jsonPost("/api/admin/reconciliation/diffs/" + diffId + "/close", teacher, "{}"), 400);
        perform(jsonPost("/api/admin/reconciliation/diffs/99999999/close", teacher, "{}"), 404);
    }

    @Test
    @DisplayName("权限隔离：STUDENT 调对账四端点一律 403")
    void studentForbiddenOnAllEndpoints() throws Exception {
        long examId = createGradedExam(objectiveOnlyPaper());

        perform(jsonPost("/api/admin/reconciliation/run", studentToken,
                "{\"examId\":" + examId + "}"), 403);
        perform(jsonGet("/api/admin/reconciliation/runs", studentToken), 403);
        perform(jsonGet("/api/admin/reconciliation/diffs", studentToken), 403);
        perform(jsonPost("/api/admin/reconciliation/diffs/1/close", studentToken, "{}"), 403);
    }

    @Test
    @DisplayName("手动对账前置：考试未达已判分状态按 400 拒绝")
    void manualRunRejectsExamNotGraded() throws Exception {
        long examId = createExam(Exam.STATUS_ENDED, objectiveOnlyPaper());
        perform(jsonPost("/api/admin/reconciliation/run", teacher,
                "{\"examId\":" + examId + "}"), 400);
    }

    // ==================== 定时兜底与多实例安全 ====================

    @Test
    @DisplayName("定时兜底：同一时间窗只执行一次（唯一批次键），不同窗口照常执行且不重复补齐")
    void scheduledBatchRunsOncePerWindow() throws Exception {
        long examId = createGradedExam(objectiveOnlyPaper());
        long submissionId = seedSubmission(examId, studentId, ExamSubmission.STATUS_SUBMITTED,
                new BigDecimal("5"), null, null, ObjectiveGradingService.GRADING_OK, 0);

        LocalDateTime window = LocalDateTime.of(2026, 10, 20, 10, 0, 0);
        Optional<ReconciliationRun> first = reconciliationService.runScheduled(window);
        Optional<ReconciliationRun> duplicate = reconciliationService.runScheduled(window.plusSeconds(1));
        assertTrue(first.isPresent());
        assertFalse(duplicate.isPresent(), "同一窗口的第二个实例必须被唯一批次键挡下");
        assertEquals(ReconciliationRun.TRIGGER_SCHEDULED, first.get().getTriggerSource());

        assertEquals(1, scheduledRunCount());
        GradingSubmission fixed = gradingSubmissionMapper.selectById(submissionId);
        assertEquals(ExamSubmission.STATUS_GRADED, fixed.getStatus());
        assertEquals(0, new BigDecimal("5").compareTo(fixed.getTotalScore()));
        assertEquals(1, fixAuditCount(submissionId), "定时补齐只发生一次");

        Optional<ReconciliationRun> nextWindow = reconciliationService.runScheduled(window.plusMinutes(20));
        assertTrue(nextWindow.isPresent(), "不同时间窗是新批次，照常执行");
        assertEquals(2, scheduledRunCount());
        assertEquals(1, fixAuditCount(submissionId));
    }

    @Test
    @DisplayName("定时兜底失败：失败原因入运行记录且不阻断下一次运行")
    void scheduledFailureRecordedAndNextRunProceeds() {
        long examId = createExamWithoutSnapshot(Exam.STATUS_GRADED);
        seedSubmission(examId, studentId, ExamSubmission.STATUS_SUBMITTED,
                new BigDecimal("5"), null, null, ObjectiveGradingService.GRADING_OK, 0);

        LocalDateTime window = LocalDateTime.of(2026, 10, 20, 11, 0, 0);
        Optional<ReconciliationRun> failed = reconciliationService.runScheduled(window);
        assertTrue(failed.isPresent());
        assertEquals(ReconciliationRun.STATUS_FAILED, failed.get().getStatus());
        assertNotNull(failed.get().getErrorMessage());
        assertTrue(failed.get().getErrorMessage().contains("快照"),
                "失败原因须写入运行记录：" + failed.get().getErrorMessage());

        Optional<ReconciliationRun> next = reconciliationService.runScheduled(window.plusMinutes(20));
        assertTrue(next.isPresent(), "失败不影响下一次运行");
        assertEquals(2, scheduledRunCount());
    }

    // ==================== 夹具 ====================

    private long createGradedExam(String paperJson) {
        return createExam(Exam.STATUS_GRADED, paperJson);
    }

    /** 已判分考试 + 快照（快照是判分与对账的题目事实源）。 */
    private long createExam(int status, String paperJson) {
        long examId = createExamWithoutSnapshot(status);
        ExamSnapshot snapshot = new ExamSnapshot();
        snapshot.setExamId(examId);
        snapshot.setExamJson("{}");
        snapshot.setPaperJson(paperJson);
        snapshot.setVersion(1);
        snapshot.setCreatedBy(teacherId);
        examSnapshotMapper.insert(snapshot);
        return examId;
    }

    /** 只有考试行、没有快照——用于复现「对账读到坏考试」的失败路径。 */
    private long createExamWithoutSnapshot(int status) {
        Exam exam = new Exam();
        exam.setTitle(unique("recon"));
        exam.setPaperId(9000L);
        exam.setStartTime(LocalDateTime.now().minusHours(2));
        exam.setEndTime(LocalDateTime.now().minusHours(1));
        exam.setDurationMinutes(60);
        exam.setAllowLateMinutes(0);
        exam.setStatus(status);
        exam.setPublished(1);
        exam.setForceEnd(0);
        exam.setReservationRequired(0);
        exam.setVersion(0);
        exam.setCreatedBy(teacherId);
        exam.setIsDeleted(0);
        examMapper.insert(exam);
        return exam.getId();
    }

    private long seedSubmission(long examId, long studentId, int status, BigDecimal objective,
                                BigDecimal subjective, BigDecimal total, int gradingStatus,
                                int partialGraded) {
        // exam_submissions 的 start_time/deadline_time 是 NOT NULL 且不在判分实体里：
        // 先用交卷域实体插入（一人一场唯一约束要求 studentId 场次内唯一），再回写判分字段。
        ExamSubmission submission = new ExamSubmission();
        submission.setExamId(examId);
        submission.setStudentId(studentId);
        submission.setStartTime(LocalDateTime.now().minusHours(1));
        submission.setDeadlineTime(LocalDateTime.now().minusMinutes(30));
        submission.setSubmitTime(LocalDateTime.now().minusMinutes(30));
        submission.setAnswers("{}");
        submission.setStatus(status);
        submission.setVersion(0);
        examSubmissionMapper.insert(submission);

        gradingSubmissionMapper.update(null, Wrappers.<GradingSubmission>lambdaUpdate()
                .eq(GradingSubmission::getId, submission.getId())
                .set(GradingSubmission::getObjectiveScore, objective)
                .set(GradingSubmission::getSubjectiveScore, subjective)
                .set(GradingSubmission::getTotalScore, total)
                .set(GradingSubmission::getGradingStatus, gradingStatus)
                .set(GradingSubmission::getPartialGraded, partialGraded)
                .set(GradingSubmission::getUpdatedTime, LocalDateTime.now()));
        return submission.getId();
    }

    private void seedSubjectiveGrade(long examId, long submissionId, long studentId, BigDecimal score) {
        SubjectiveGrade grade = new SubjectiveGrade();
        grade.setExamId(examId);
        grade.setSubmissionId(submissionId);
        grade.setStudentId(studentId);
        grade.setQuestionId(SHORT_ANSWER_QUESTION_ID);
        grade.setQuestionNumber(2);
        grade.setScore(score);
        grade.setGraderId(teacherId);
        grade.setVersion(0);
        subjectiveGradeMapper.insert(grade);
    }

    private void seedSubjectiveGradeWithoutScore(long examId, long submissionId, long studentId) {
        seedSubjectiveGrade(examId, submissionId, studentId, null);
    }

    private String objectiveOnlyPaper() throws Exception {
        return paperJson(false);
    }

    private String paperWithShortAnswer() throws Exception {
        return paperJson(true);
    }

    /** 与 PaperSnapshotService.serialize 同结构的 paper_json（判分只读该快照）。 */
    private String paperJson(boolean withShortAnswer) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("paperId", 9000L);
        root.put("title", "对账测试卷");
        root.put("totalScore", withShortAnswer ? 15 : 5);
        root.put("questionCount", withShortAnswer ? 2 : 1);
        ArrayNode questions = root.putArray("questions");

        ObjectNode objective = questions.addObject();
        objective.put("number", 1);
        objective.put("questionId", OBJECTIVE_QUESTION_ID);
        objective.put("type", 1);
        objective.put("content", "客观题");
        objective.putArray("choices").add("A").add("B");
        objective.put("correctAnswer", "A");
        objective.put("score", 5);

        if (withShortAnswer) {
            ObjectNode shortAnswer = questions.addObject();
            shortAnswer.put("number", 2);
            shortAnswer.put("questionId", SHORT_ANSWER_QUESTION_ID);
            shortAnswer.put("type", 4);
            shortAnswer.put("content", "简答题");
            shortAnswer.put("correctAnswer", "参考答案");
            shortAnswer.put("score", 10);
        }
        return objectMapper.writeValueAsString(root);
    }

    // ==================== 查询与清理 ====================

    private JsonNode diffsOf(long runId, String status) throws Exception {
        return perform(jsonGet("/api/admin/reconciliation/diffs?runId=" + runId + "&status=" + status,
                teacher), 200).get("data");
    }

    private JsonNode findByType(JsonNode diffs, String diffType) {
        for (JsonNode diff : diffs) {
            if (diffType.equals(diff.get("diffType").asText())) {
                return diff;
            }
        }
        return null;
    }

    private long fixAuditCount(long submissionId) {
        return auditLogMapper.selectCount(Wrappers.<AuditLog>lambdaQuery()
                .eq(AuditLog::getAction, AuditLog.ACTION_SCORE_RECONCILE_FIX)
                .like(AuditLog::getDetails, "submissionId=" + submissionId + ","));
    }

    private long closeAuditCount(long diffId) {
        return auditLogMapper.selectCount(Wrappers.<AuditLog>lambdaQuery()
                .eq(AuditLog::getAction, AuditLog.ACTION_SCORE_RECONCILE_CLOSE)
                .like(AuditLog::getDetails, "diffId=" + diffId + ","));
    }

    private long runDiffCount(long submissionId, String status) {
        return diffMapper.selectCount(Wrappers.<ReconciliationDiff>lambdaQuery()
                .eq(ReconciliationDiff::getSubmissionId, submissionId)
                .eq(ReconciliationDiff::getStatus, status));
    }

    private long scheduledRunCount() {
        return runMapper.selectCount(Wrappers.<ReconciliationRun>lambdaQuery()
                .eq(ReconciliationRun::getTriggerSource, ReconciliationRun.TRIGGER_SCHEDULED));
    }

    /** 清空本案两张表（其他用例不写这两张表，跨类无竞争）。 */
    private void clearReconcileTables() {
        List<ReconciliationDiff> diffs = diffMapper.selectList(null);
        if (diffs != null && !diffs.isEmpty()) {
            diffMapper.deleteBatchIds(diffs.stream().map(ReconciliationDiff::getId).toList());
        }
        List<ReconciliationRun> runs = runMapper.selectList(null);
        if (runs != null && !runs.isEmpty()) {
            runMapper.deleteBatchIds(runs.stream().map(ReconciliationRun::getId).toList());
        }
    }

    /** 隐藏上一用例的夹具考试（逻辑删）：定时兜底的候选扫描不得捞到旧夹具。 */
    private void hidePreviousFixtureExams() {
        List<Exam> previous = examMapper.selectList(
                Wrappers.<Exam>lambdaQuery().likeRight(Exam::getTitle, "recon-"));
        for (Exam exam : previous) {
            exam.setIsDeleted(1);
            examMapper.updateById(exam);
        }
    }
}

package com.exam.score.service;

import com.exam.auth.security.LoginUser;
import com.exam.auth.security.RoleHierarchy;
import com.exam.auth.security.SecurityUtil;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.config.ReadYourWriteMark;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.entity.SubjectiveGrade;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.grading.mapper.SubjectiveGradeMapper;
import com.exam.grading.model.GradingPaper;
import com.exam.grading.model.GradingQuestion;
import com.exam.grading.support.GradingPaperReader;
import com.exam.question.entity.QuestionType;
import com.exam.score.dto.ScoreActionItem;
import com.exam.score.dto.ScorePreviewResponse;
import com.exam.score.entity.ScoreAuditLog;
import com.exam.score.mapper.ScoreAuditLogMapper;
import com.exam.submission.entity.ExamSubmission;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 成绩汇总 / 批量发布 / 批量撤回单测（spec score-management，决策记录 §5.3/§7.5/§11.6）。
 *
 * <p>覆盖的核心是<b>批量语义</b>：一次发布多场考试时，单场状态不符（未批改、不存在、
 * 越权、CAS 抢输）只让该场失败并回填原因，其余照常发布——整体不回滚、循环不中断。
 * 这是 publish() 拆分事务边界（commit 023f46e）后唯一能守住该语义的回归网。
 *
 * <p>指标依赖说明：{@code meterRegistry} 由 {@code @Autowired initMetrics(...)} 后置注入，
 * {@code @InjectMocks} 只走构造器，故必须在 @BeforeEach 手动注入，否则 publish() 必然 NPE。
 */
@ExtendWith(MockitoExtension.class)
class ScoreServiceTest {

    private static final Long TEACHER_ID = 7L;
    private static final Long ADMIN_ID = 99L;
    private static final Long EXAM_ID = 10L;

    @Mock
    private ExamMapper examMapper;
    @Mock
    private GradingSubmissionMapper gradingSubmissionMapper;
    @Mock
    private SubjectiveGradeMapper subjectiveGradeMapper;
    @Mock
    private GradingPaperReader paperReader;
    @Mock
    private ScoreAuditLogMapper auditLogMapper;
    @Mock
    private ReadYourWriteMark readYourWriteMark;

    @InjectMocks
    private ScoreService scoreService;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @BeforeEach
    void injectMeterRegistry() {
        scoreService.initMetrics(registry);
    }

    @AfterEach
    void clearSecurity() {
        SecurityUtil.clear();
    }

    // ==================== 造数工具 ====================

    private Exam exam(long id, int status) {
        Exam exam = new Exam();
        exam.setId(id);
        exam.setTitle("期末考-" + id);
        exam.setStatus(status);
        exam.setVersion(0);
        exam.setCreatedBy(TEACHER_ID);
        return exam;
    }

    private GradingSubmission submission(long id, long studentId, String objective, String total,
                                         Integer partialGraded) {
        GradingSubmission submission = new GradingSubmission();
        submission.setId(id);
        submission.setExamId(EXAM_ID);
        submission.setStudentId(studentId);
        submission.setObjectiveScore(objective == null ? null : new BigDecimal(objective));
        submission.setTotalScore(total == null ? null : new BigDecimal(total));
        submission.setPartialGraded(partialGraded);
        submission.setGradingStatus(1);
        return submission;
    }

    private SubjectiveGrade gradeRow(long submissionId, long questionId, String score) {
        SubjectiveGrade grade = new SubjectiveGrade();
        grade.setSubmissionId(submissionId);
        grade.setQuestionId(questionId);
        grade.setScore(score == null ? null : new BigDecimal(score));
        return grade;
    }

    private GradingPaper paperOf(GradingQuestion... questions) {
        return new GradingPaper(1L, "试卷", new BigDecimal("100"), List.of(questions));
    }

    private GradingQuestion shortAnswer(long questionId, int number) {
        return new GradingQuestion(number, questionId, QuestionType.SHORT_ANSWER,
                "简答题" + number, null, "参考答案", new BigDecimal("10"));
    }

    private GradingQuestion singleChoice(long questionId, int number) {
        return new GradingQuestion(number, questionId, QuestionType.SINGLE,
                "单选题" + number, List.of("A", "B"), "A", new BigDecimal("5"));
    }

    private void loginAs(Long userId, int roleLevel) {
        LoginUser loginUser = new LoginUser();
        loginUser.setId(userId);
        loginUser.setRoleLevel(roleLevel);
        SecurityUtil.set(loginUser);
    }

    private void loginAsTeacher() {
        loginAs(TEACHER_ID, RoleHierarchy.levelOf(RoleHierarchy.TEACHER));
    }

    private void loginAsAdmin() {
        loginAs(ADMIN_ID, RoleHierarchy.levelOf(RoleHierarchy.ADMIN));
    }

    private static String messageOf(ResponseCode rc) {
        return new BusinessException(rc).getMessage();
    }

    // ==================== 批量发布 ====================

    @Test
    void publishReturnsPerExamOutcomeAndKeepsGoingWhenSomeExamCannotPublish() {
        loginAsTeacher();
        when(examMapper.selectById(1L)).thenReturn(exam(1L, Exam.STATUS_GRADED));
        when(examMapper.selectById(2L)).thenReturn(exam(2L, Exam.STATUS_ENDED));
        when(examMapper.selectById(3L)).thenReturn(null);
        when(examMapper.casUpdateStatus(1L, Exam.STATUS_GRADED, Exam.STATUS_PUBLISHED, 0)).thenReturn(1);
        when(gradingSubmissionMapper.selectCount(any())).thenReturn(3L);

        List<ScoreActionItem> results = scoreService.publish(List.of(1L, 2L, 3L));

        assertEquals(List.of(
                new ScoreActionItem(1L, true, "发布成功"),
                new ScoreActionItem(2L, false, "考试未完成批改，不能发布"),
                new ScoreActionItem(3L, false, "考试不存在")), results);
    }

    @Test
    void publishWritesAuditWithOperatorAndGradedHeadcount() {
        loginAsTeacher();
        when(examMapper.selectById(1L)).thenReturn(exam(1L, Exam.STATUS_GRADED));
        when(examMapper.casUpdateStatus(1L, Exam.STATUS_GRADED, Exam.STATUS_PUBLISHED, 0)).thenReturn(1);
        when(gradingSubmissionMapper.selectCount(any())).thenReturn(42L);

        scoreService.publish(List.of(1L));

        ArgumentCaptor<ScoreAuditLog> captor = ArgumentCaptor.forClass(ScoreAuditLog.class);
        verify(auditLogMapper).insert(captor.capture());
        ScoreAuditLog audit = captor.getValue();
        assertEquals(1L, audit.getExamId());
        assertEquals(ScoreAuditLog.ACTION_PUBLISH, audit.getAction());
        assertEquals(TEACHER_ID, audit.getOperatorId());
        assertEquals("发布成绩 42 人", audit.getDetail());
        assertNull(audit.getReason(), "发布无需原因，仅撤回强制填写");
        verify(readYourWriteMark).mark();
        var published = registry.get("exam_publish_total")
                .tag("status", "success").counter();
        assertEquals(1.0, published.count());
        // 守指标基数：多一个 tag 维度就多一批时间序列，按 exam_id 打 tag 会让序列数随考试量线性膨胀
        assertEquals(1, published.getId().getTags().size(),
                "publish 指标只该有 status 一个 tag，实际=" + published.getId().getTags());
    }

    @Test
    void publishIsIdempotentWhenExamAlreadyPublished() {
        loginAsTeacher();
        when(examMapper.selectById(1L)).thenReturn(exam(1L, Exam.STATUS_PUBLISHED));

        List<ScoreActionItem> results = scoreService.publish(List.of(1L));

        assertEquals(new ScoreActionItem(1L, true, "已发布（幂等跳过）"), results.get(0));
        verify(examMapper, never()).casUpdateStatus(anyLong(), anyInt(), anyInt(), anyInt());
        verifyNoInteractions(auditLogMapper, readYourWriteMark);
    }

    @Test
    void publishCasConflictFailsOnlyThatExamAndLetsTheRestThrough() {
        loginAsTeacher();
        when(examMapper.selectById(1L)).thenReturn(exam(1L, Exam.STATUS_GRADED));
        when(examMapper.selectById(2L)).thenReturn(exam(2L, Exam.STATUS_GRADED));
        when(examMapper.casUpdateStatus(1L, Exam.STATUS_GRADED, Exam.STATUS_PUBLISHED, 0)).thenReturn(0);
        when(examMapper.casUpdateStatus(2L, Exam.STATUS_GRADED, Exam.STATUS_PUBLISHED, 0)).thenReturn(1);
        when(gradingSubmissionMapper.selectCount(any())).thenReturn(5L);

        List<ScoreActionItem> results = scoreService.publish(List.of(1L, 2L));

        assertEquals(new ScoreActionItem(1L, false, "考试状态已变化，请刷新后重试"), results.get(0));
        assertEquals(new ScoreActionItem(2L, true, "发布成功"), results.get(1));
        verify(auditLogMapper, times(1)).insert(any(ScoreAuditLog.class));
        assertEquals(1.0, registry.get("exam_publish_total")
                .tag("status", "fail").counter().count());
        assertEquals(1.0, registry.get("exam_publish_total")
                .tag("status", "success").counter().count());
    }

    @Test
    void publishRejectsExamOwnedByAnotherTeacherWithoutTouchingStatus() {
        loginAsTeacher();
        Exam foreign = exam(1L, Exam.STATUS_GRADED);
        foreign.setCreatedBy(TEACHER_ID + 1);
        when(examMapper.selectById(1L)).thenReturn(foreign);

        List<ScoreActionItem> results = scoreService.publish(List.of(1L));

        assertEquals(new ScoreActionItem(1L, false, "无权操作该考试（资源不属于当前用户）"), results.get(0));
        verify(examMapper, never()).casUpdateStatus(anyLong(), anyInt(), anyInt(), anyInt());
        verifyNoInteractions(auditLogMapper, readYourWriteMark);
    }

    @Test
    void publishReportsUnauthenticatedAsPerExamFailure() {
        when(examMapper.selectById(1L)).thenReturn(exam(1L, Exam.STATUS_GRADED));

        List<ScoreActionItem> results = scoreService.publish(List.of(1L));

        assertEquals(new ScoreActionItem(1L, false, messageOf(ResponseCode.TOKEN_INVALID)), results.get(0));
        verify(examMapper, never()).casUpdateStatus(anyLong(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void publishPassesExamVersionIntoCasSoConcurrentEditsLose() {
        loginAsTeacher();
        Exam stale = exam(1L, Exam.STATUS_GRADED);
        stale.setVersion(13);
        when(examMapper.selectById(1L)).thenReturn(stale);
        when(examMapper.casUpdateStatus(1L, Exam.STATUS_GRADED, Exam.STATUS_PUBLISHED, 13)).thenReturn(0);

        List<ScoreActionItem> results = scoreService.publish(List.of(1L));

        assertFalse(results.get(0).success());
        verify(examMapper).casUpdateStatus(1L, Exam.STATUS_GRADED, Exam.STATUS_PUBLISHED, 13);
    }

    @Test
    void publishEmptyBatchTouchesNothing() {
        assertTrue(scoreService.publish(List.of()).isEmpty());
        verifyNoInteractions(examMapper, auditLogMapper, readYourWriteMark);
    }

    @Test
    void publishAbortsWholeBatchWhenFailureIsNotABusinessException() {
        loginAsTeacher();
        when(examMapper.selectById(1L)).thenThrow(new IllegalStateException("db down"));

        // 只捕获 BusinessException：基础设施故障必须整体失败，不能让部分考试静默发布
        assertThrows(IllegalStateException.class, () -> scoreService.publish(List.of(1L, 2L)));
        verify(examMapper, never()).selectById(2L);
        // 基础设施故障记为 error，与业务性 fail 分开，避免看板把宕机读成"用户操作不当"
        assertEquals(1.0, registry.get("exam_publish_total")
                .tag("status", "error").counter().count());
        assertNull(registry.find("exam_publish_total").tag("status", "fail").counter(),
                "基础设施故障不该计入业务失败");
    }

    // ==================== 批量撤回 ====================

    @Test
    void revokeRejectsTeacherOperatorBeforeReadingAnyExam() {
        loginAsTeacher();

        BusinessException e = assertThrows(BusinessException.class,
                () -> scoreService.revoke(List.of(1L), "成绩录入有误"));

        assertEquals("成绩撤回仅管理员可执行", e.getMessage());
        verifyNoInteractions(examMapper, auditLogMapper);
    }

    @Test
    void revokeRejectsUnauthenticatedOperator() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> scoreService.revoke(List.of(1L), "成绩录入有误"));

        assertEquals(messageOf(ResponseCode.TOKEN_INVALID), e.getMessage());
    }

    @Test
    void revokeRequiresNonBlankReason() {
        loginAsAdmin();

        assertEquals("撤回必须填写原因（审计要求）",
                assertThrows(BusinessException.class,
                        () -> scoreService.revoke(List.of(1L), "   ")).getMessage());
        assertEquals("撤回必须填写原因（审计要求）",
                assertThrows(BusinessException.class,
                        () -> scoreService.revoke(List.of(1L), null)).getMessage());
        verifyNoInteractions(examMapper, auditLogMapper);
    }

    @Test
    void revokeReportsPerExamOutcomeAndKeepsReasonForAudit() {
        loginAsAdmin();
        when(examMapper.selectById(1L)).thenReturn(exam(1L, Exam.STATUS_PUBLISHED));
        when(examMapper.selectById(2L)).thenReturn(exam(2L, Exam.STATUS_GRADED));
        when(examMapper.selectById(3L)).thenReturn(null);
        when(examMapper.casUpdateStatus(1L, Exam.STATUS_PUBLISHED, Exam.STATUS_GRADED, 0)).thenReturn(1);

        List<ScoreActionItem> results = scoreService.revoke(List.of(1L, 2L, 3L), "成绩录入有误");

        assertEquals(List.of(
                new ScoreActionItem(1L, true, "撤回成功"),
                new ScoreActionItem(2L, false, "考试成绩未处于已发布状态"),
                new ScoreActionItem(3L, false, "考试不存在")), results);

        ArgumentCaptor<ScoreAuditLog> captor = ArgumentCaptor.forClass(ScoreAuditLog.class);
        verify(auditLogMapper).insert(captor.capture());
        assertEquals(ScoreAuditLog.ACTION_REVOKE, captor.getValue().getAction());
        assertEquals("成绩录入有误", captor.getValue().getReason());
        assertEquals("撤回成绩，学生端隐藏", captor.getValue().getDetail());
        assertEquals(ADMIN_ID, captor.getValue().getOperatorId());
        verify(readYourWriteMark).mark();
    }

    @Test
    void revokeCasConflictBecomesPerExamFailureWithoutAudit() {
        loginAsAdmin();
        Exam published = exam(1L, Exam.STATUS_PUBLISHED);
        published.setVersion(6);
        when(examMapper.selectById(1L)).thenReturn(published);
        when(examMapper.casUpdateStatus(1L, Exam.STATUS_PUBLISHED, Exam.STATUS_GRADED, 6)).thenReturn(0);

        List<ScoreActionItem> results = scoreService.revoke(List.of(1L), "误发布");

        assertEquals(new ScoreActionItem(1L, false, "考试状态已变化，请刷新后重试"), results.get(0));
        verifyNoInteractions(auditLogMapper, readYourWriteMark);
    }

    @Test
    void revokeIsAllowedOnForeignExamBecauseItIsAGlobalAdminAction() {
        loginAsAdmin();
        Exam foreign = exam(1L, Exam.STATUS_PUBLISHED);
        foreign.setCreatedBy(TEACHER_ID + 50);
        when(examMapper.selectById(1L)).thenReturn(foreign);
        when(examMapper.casUpdateStatus(1L, Exam.STATUS_PUBLISHED, Exam.STATUS_GRADED, 0)).thenReturn(1);

        assertTrue(scoreService.revoke(List.of(1L), "批量纠错").get(0).success());
    }

    // ==================== 成绩汇总 ====================

    @Test
    void summarizeRejectsPublishedExamUntilRevoked() {
        loginAsTeacher();
        when(examMapper.selectById(EXAM_ID)).thenReturn(exam(EXAM_ID, Exam.STATUS_PUBLISHED));

        assertEquals("成绩已发布，须先撤回再重新汇总",
                assertThrows(BusinessException.class, () -> scoreService.summarize(EXAM_ID)).getMessage());
    }

    @Test
    void summarizeRejectsExamThatHasNotEnded() {
        loginAsTeacher();
        when(examMapper.selectById(EXAM_ID)).thenReturn(exam(EXAM_ID, Exam.STATUS_IN_PROGRESS));

        assertEquals("考试尚未结束，不能汇总成绩",
                assertThrows(BusinessException.class, () -> scoreService.summarize(EXAM_ID)).getMessage());
    }

    @Test
    void summarizeCountsCasWinsAndSkipsSeparately() {
        loginAsTeacher();
        when(examMapper.selectById(EXAM_ID)).thenReturn(exam(EXAM_ID, Exam.STATUS_ENDED));
        when(paperReader.readByExamId(EXAM_ID)).thenReturn(paperOf(singleChoice(101L, 1)));
        when(gradingSubmissionMapper.selectList(any())).thenReturn(List.of(
                submission(1L, 201L, "14.0", null, null),
                submission(2L, 202L, "12.0", null, null),
                submission(3L, 203L, "10.0", null, null)));
        when(gradingSubmissionMapper.casSummarize(eq(1L), any(), any(), anyInt())).thenReturn(1);
        when(gradingSubmissionMapper.casSummarize(eq(2L), any(), any(), anyInt())).thenReturn(1);
        when(gradingSubmissionMapper.casSummarize(eq(3L), any(), any(), anyInt())).thenReturn(0);
        when(examMapper.casUpdateStatus(EXAM_ID, Exam.STATUS_ENDED, Exam.STATUS_GRADED, 0)).thenReturn(1);

        ScoreService.SummarizeStats stats = scoreService.summarize(EXAM_ID);

        assertEquals(2, stats.summarized());
        assertEquals(1, stats.skipped());
        assertTrue(stats.examGraded());
        verify(readYourWriteMark).mark();
        verifyNoInteractions(subjectiveGradeMapper);
    }

    @Test
    void summarizeSumsGradedShortAnswersAndTreatsMissingRowAsZeroAndPartial() {
        loginAsTeacher();
        when(examMapper.selectById(EXAM_ID)).thenReturn(exam(EXAM_ID, Exam.STATUS_ENDED));
        when(paperReader.readByExamId(EXAM_ID)).thenReturn(paperOf(
                singleChoice(101L, 1), shortAnswer(201L, 2), shortAnswer(202L, 3)));
        when(gradingSubmissionMapper.selectList(any()))
                .thenReturn(List.of(submission(1L, 201L, "30.0", null, null)));
        // 201 已批 7 分；202 缺行 → 未批按 0 分计入 + 部分批改标记（§7.5）
        when(subjectiveGradeMapper.selectList(any())).thenReturn(List.of(gradeRow(1L, 201L, "7.0")));
        when(gradingSubmissionMapper.casSummarize(eq(1L), any(), any(), anyInt())).thenReturn(1);
        when(examMapper.casUpdateStatus(EXAM_ID, Exam.STATUS_ENDED, Exam.STATUS_GRADED, 0)).thenReturn(1);

        scoreService.summarize(EXAM_ID);

        verify(gradingSubmissionMapper).casSummarize(1L, new BigDecimal("7.0"), new BigDecimal("37.0"), 1);
    }

    @Test
    void summarizeRejectsSubmissionWithMissingObjectiveScoreBeforeAnyWrite() {
        loginAsTeacher();
        when(examMapper.selectById(EXAM_ID)).thenReturn(exam(EXAM_ID, Exam.STATUS_ENDED));
        when(paperReader.readByExamId(EXAM_ID)).thenReturn(paperOf(shortAnswer(201L, 1)));
        when(gradingSubmissionMapper.selectList(any()))
                .thenReturn(List.of(submission(1L, 201L, null, null, null)));

        BusinessException error = assertThrows(BusinessException.class, () -> scoreService.summarize(EXAM_ID));

        assertEquals("存在未完成判分的答卷，不能汇总成绩", error.getMessage());
        verify(gradingSubmissionMapper, never()).casSummarize(anyLong(), any(), any(), anyInt());
        verify(examMapper, never()).casUpdateStatus(anyLong(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void summarizeRejectsWholeBatchWhenUnfinishedSubmissionFollowsValidSubmissionWithoutAnyWrite() {
        loginAsTeacher();
        when(examMapper.selectById(EXAM_ID)).thenReturn(exam(EXAM_ID, Exam.STATUS_ENDED));
        when(paperReader.readByExamId(EXAM_ID)).thenReturn(paperOf(singleChoice(101L, 1)));
        GradingSubmission valid = submission(1L, 201L, "12.0", null, null);
        GradingSubmission unfinished = submission(2L, 202L, null, null, null);
        when(gradingSubmissionMapper.selectList(any())).thenReturn(List.of(valid, unfinished));

        assertEquals("存在未完成判分的答卷，不能汇总成绩",
                assertThrows(BusinessException.class, () -> scoreService.summarize(EXAM_ID)).getMessage());
        verify(gradingSubmissionMapper, never()).casSummarize(anyLong(), any(), any(), anyInt());
        verify(examMapper, never()).casUpdateStatus(anyLong(), anyInt(), anyInt(), anyInt());
        verifyNoInteractions(subjectiveGradeMapper);
    }

    @Test
    void summarizeRejectsFailedSubmissionBeforeAnyWrite() {
        loginAsTeacher();
        when(examMapper.selectById(EXAM_ID)).thenReturn(exam(EXAM_ID, Exam.STATUS_ENDED));
        when(paperReader.readByExamId(EXAM_ID)).thenReturn(paperOf(singleChoice(101L, 1)));
        GradingSubmission failed = submission(1L, 201L, "0.0", null, null);
        failed.setGradingStatus(2);
        when(gradingSubmissionMapper.selectList(any())).thenReturn(List.of(failed));

        assertEquals("存在未完成判分的答卷，不能汇总成绩",
                assertThrows(BusinessException.class, () -> scoreService.summarize(EXAM_ID)).getMessage());
        verify(gradingSubmissionMapper, never()).casSummarize(anyLong(), any(), any(), anyInt());
        verify(examMapper, never()).casUpdateStatus(anyLong(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void summarizeAllowsSuccessfulObjectiveZeroAndPartialSubjectiveGrading() {
        loginAsTeacher();
        when(examMapper.selectById(EXAM_ID)).thenReturn(exam(EXAM_ID, Exam.STATUS_ENDED));
        when(paperReader.readByExamId(EXAM_ID)).thenReturn(paperOf(shortAnswer(201L, 1), shortAnswer(202L, 2)));
        when(gradingSubmissionMapper.selectList(any()))
                .thenReturn(List.of(submission(1L, 201L, "0.0", null, null)));
        when(subjectiveGradeMapper.selectList(any())).thenReturn(List.of(
                gradeRow(1L, 201L, "0.0"), gradeRow(1L, 202L, null)));
        when(gradingSubmissionMapper.casSummarize(eq(1L), any(), any(), anyInt())).thenReturn(1);
        when(examMapper.casUpdateStatus(EXAM_ID, Exam.STATUS_ENDED, Exam.STATUS_GRADED, 0)).thenReturn(1);

        scoreService.summarize(EXAM_ID);

        verify(gradingSubmissionMapper).casSummarize(1L, new BigDecimal("0.0"), new BigDecimal("0.0"), 1);
    }

    @Test
    void summarizeClearsPartialFlagWhenEveryShortAnswerIsGraded() {
        loginAsTeacher();
        when(examMapper.selectById(EXAM_ID)).thenReturn(exam(EXAM_ID, Exam.STATUS_ENDED));
        when(paperReader.readByExamId(EXAM_ID)).thenReturn(paperOf(shortAnswer(201L, 1), shortAnswer(202L, 2)));
        when(gradingSubmissionMapper.selectList(any()))
                .thenReturn(List.of(submission(1L, 201L, "20.0", null, null)));
        when(subjectiveGradeMapper.selectList(any())).thenReturn(List.of(
                gradeRow(1L, 201L, "8.0"), gradeRow(1L, 202L, "6.0")));
        when(gradingSubmissionMapper.casSummarize(eq(1L), any(), any(), anyInt())).thenReturn(1);
        when(examMapper.casUpdateStatus(EXAM_ID, Exam.STATUS_ENDED, Exam.STATUS_GRADED, 0)).thenReturn(1);

        scoreService.summarize(EXAM_ID);

        verify(gradingSubmissionMapper).casSummarize(1L, new BigDecimal("14.0"), new BigDecimal("34.0"), 0);
    }

    @Test
    void summarizeReportsNotGradedWhenExamCasLosesRaceButKeepsSubmissionResults() {
        loginAsTeacher();
        when(examMapper.selectById(EXAM_ID)).thenReturn(exam(EXAM_ID, Exam.STATUS_ENDED));
        when(paperReader.readByExamId(EXAM_ID)).thenReturn(paperOf(singleChoice(101L, 1)));
        when(gradingSubmissionMapper.selectList(any()))
                .thenReturn(List.of(submission(1L, 201L, "14.0", null, null)));
        when(gradingSubmissionMapper.casSummarize(eq(1L), any(), any(), anyInt())).thenReturn(1);
        when(examMapper.casUpdateStatus(EXAM_ID, Exam.STATUS_ENDED, Exam.STATUS_GRADED, 0)).thenReturn(0);

        ScoreService.SummarizeStats stats = scoreService.summarize(EXAM_ID);

        assertEquals(1, stats.summarized());
        assertFalse(stats.examGraded(), "并发汇总只有一个能推进考试状态，另一个静默通过");
    }

    @Test
    void summarizeReportsAlreadyGradedExamWithoutAnotherCas() {
        loginAsTeacher();
        when(examMapper.selectById(EXAM_ID)).thenReturn(exam(EXAM_ID, Exam.STATUS_GRADED));
        when(paperReader.readByExamId(EXAM_ID)).thenReturn(paperOf(singleChoice(101L, 1)));
        when(gradingSubmissionMapper.selectList(any())).thenReturn(List.of());

        ScoreService.SummarizeStats stats = scoreService.summarize(EXAM_ID);

        assertEquals(0, stats.summarized());
        assertTrue(stats.examGraded());
        verify(examMapper, never()).casUpdateStatus(anyLong(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void summarizeReportsNotGradedWhenExamIsPublishedMidRun() {
        loginAsTeacher();
        // 两次读之间被别人发布掉了：既不是 ENDED 也不是 GRADED，不能谎报"已批改"
        when(examMapper.selectById(EXAM_ID))
                .thenReturn(exam(EXAM_ID, Exam.STATUS_ENDED), exam(EXAM_ID, Exam.STATUS_PUBLISHED));
        when(paperReader.readByExamId(EXAM_ID)).thenReturn(paperOf(singleChoice(101L, 1)));
        when(gradingSubmissionMapper.selectList(any())).thenReturn(List.of());

        ScoreService.SummarizeStats stats = scoreService.summarize(EXAM_ID);

        assertFalse(stats.examGraded());
        verify(examMapper, never()).casUpdateStatus(anyLong(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void summarizeKeepsFirstGradeRowWhenAShortAnswerHasDuplicates() {
        loginAsTeacher();
        when(examMapper.selectById(EXAM_ID)).thenReturn(exam(EXAM_ID, Exam.STATUS_ENDED));
        when(paperReader.readByExamId(EXAM_ID)).thenReturn(paperOf(shortAnswer(201L, 1)));
        when(gradingSubmissionMapper.selectList(any()))
                .thenReturn(List.of(submission(1L, 201L, "20.0", null, null)));
        // 脏数据：同一题两行判分（唯一索引之外的历史遗留）→ 取首行，不抛异常中断整场汇总
        when(subjectiveGradeMapper.selectList(any())).thenReturn(List.of(
                gradeRow(1L, 201L, "7.0"), gradeRow(1L, 201L, "9.0")));
        when(gradingSubmissionMapper.casSummarize(eq(1L), any(), any(), anyInt())).thenReturn(1);
        when(examMapper.casUpdateStatus(EXAM_ID, Exam.STATUS_ENDED, Exam.STATUS_GRADED, 0)).thenReturn(1);

        scoreService.summarize(EXAM_ID);

        verify(gradingSubmissionMapper).casSummarize(1L, new BigDecimal("7.0"), new BigDecimal("27.0"), 0);
    }

    @Test
    void summarizeQueriesSubjectiveGradesOnceForTwoSubmissionsAndKeepsPerSubmissionTotals() {
        loginAsTeacher();
        when(examMapper.selectById(EXAM_ID)).thenReturn(exam(EXAM_ID, Exam.STATUS_ENDED));
        when(paperReader.readByExamId(EXAM_ID)).thenReturn(paperOf(
                singleChoice(101L, 1), shortAnswer(201L, 2), shortAnswer(202L, 3)));
        when(gradingSubmissionMapper.selectList(any())).thenReturn(List.of(
                submission(1L, 201L, "30.0", null, null),
                submission(2L, 202L, "30.0", null, null)));
        // 一次查询返回两份答卷的主观分（行序按两份卷交错，证明按答卷分组而非取首份）：
        // 1 号卷已批 7+3=10 分，2 号卷已批 5+4=9 分
        when(subjectiveGradeMapper.selectList(any())).thenReturn(List.of(
                gradeRow(2L, 201L, "5.0"), gradeRow(1L, 201L, "7.0"),
                gradeRow(2L, 202L, "4.0"), gradeRow(1L, 202L, "3.0")));
        when(gradingSubmissionMapper.casSummarize(eq(1L), any(), any(), anyInt())).thenReturn(1);
        when(gradingSubmissionMapper.casSummarize(eq(2L), any(), any(), anyInt())).thenReturn(1);
        when(examMapper.casUpdateStatus(EXAM_ID, Exam.STATUS_ENDED, Exam.STATUS_GRADED, 0)).thenReturn(1);

        ScoreService.SummarizeStats stats = scoreService.summarize(EXAM_ID);

        assertEquals(2, stats.summarized());
        // 两份答卷只允许一次主观分查询（原先每份各查一次）
        verify(subjectiveGradeMapper, times(1)).selectList(any());
        // 逐份 CAS 的主观分/总分/部分批改标记与逐份查询时完全一致
        verify(gradingSubmissionMapper).casSummarize(1L, new BigDecimal("10.0"), new BigDecimal("40.0"), 0);
        verify(gradingSubmissionMapper).casSummarize(2L, new BigDecimal("9.0"), new BigDecimal("39.0"), 0);
    }

    @Test
    void summarizeTreatsMissingSubjectiveRowsAsUngradedWhenBatchLoadedWithAnotherSubmission() {
        loginAsTeacher();
        when(examMapper.selectById(EXAM_ID)).thenReturn(exam(EXAM_ID, Exam.STATUS_ENDED));
        when(paperReader.readByExamId(EXAM_ID)).thenReturn(paperOf(
                singleChoice(101L, 1), shortAnswer(201L, 2), shortAnswer(202L, 3)));
        when(gradingSubmissionMapper.selectList(any())).thenReturn(List.of(
                submission(1L, 201L, "30.0", null, null),
                submission(2L, 202L, "30.0", null, null)));
        // 一次取回后只有 1 号卷有主观分行：2 号卷缺行 → 整卷未批口径（主观 0 分 + 部分批改），不得当成已批完
        when(subjectiveGradeMapper.selectList(any())).thenReturn(List.of(
                gradeRow(1L, 201L, "7.0"), gradeRow(1L, 202L, "3.0")));
        when(gradingSubmissionMapper.casSummarize(eq(1L), any(), any(), anyInt())).thenReturn(1);
        when(gradingSubmissionMapper.casSummarize(eq(2L), any(), any(), anyInt())).thenReturn(1);
        when(examMapper.casUpdateStatus(EXAM_ID, Exam.STATUS_ENDED, Exam.STATUS_GRADED, 0)).thenReturn(1);

        scoreService.summarize(EXAM_ID);

        verify(subjectiveGradeMapper, times(1)).selectList(any());
        verify(gradingSubmissionMapper).casSummarize(1L, new BigDecimal("10.0"), new BigDecimal("40.0"), 0);
        verify(gradingSubmissionMapper).casSummarize(2L, new BigDecimal("0"), new BigDecimal("30.0"), 1);
    }
}

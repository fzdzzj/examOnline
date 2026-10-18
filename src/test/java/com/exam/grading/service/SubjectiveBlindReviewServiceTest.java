package com.exam.grading.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.exam.auth.security.LoginUser;
import com.exam.auth.security.RoleHierarchy;
import com.exam.auth.security.SecurityUtil;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.config.ReadYourWriteMark;
import com.exam.config.SlaveReadFallbackExecutor;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.grading.dto.BlindArbitrationItem;
import com.exam.grading.dto.BlindArbitrateResponse;
import com.exam.grading.dto.BlindReviewSubmitRequest;
import com.exam.grading.dto.BlindReviewSubmitResponse;
import com.exam.grading.dto.SubjectiveGradePageResponse;
import com.exam.grading.dto.SubjectiveGradeRow;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.entity.SubjectiveBlindReview;
import com.exam.grading.entity.SubjectiveGrade;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.grading.mapper.SubjectiveBlindReviewMapper;
import com.exam.grading.mapper.SubjectiveGradeMapper;
import com.exam.grading.model.GradingPaper;
import com.exam.grading.model.GradingQuestion;
import com.exam.grading.support.GradingPaperReader;
import com.exam.question.entity.QuestionType;
import com.exam.user.entity.User;
import com.exam.user.mapper.UserMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 双评盲评与分差仲裁服务单测（add-subjective-double-blind-grading，Mockito 隔离无 Spring）：
 *
 * <ul>
 *   <li>一评/二评自动分流：无 FIRST 即一评（终分保持 NULL），有即二评；</li>
 *   <li>同教师互斥（m1 护栏）：一评二评背靠背，同一人 400；</li>
 *   <li>分差裁决：≤ 容差取均分回填终分，超标流转 ARBITRATION_PENDING（m2 护栏）；</li>
 *   <li>仲裁终审：仲裁分回填 subjective_grades.score（m3 护栏）、超满分 400、非待仲裁 400；</li>
 *   <li>盲评脱敏视图：考生打码、教师评分评语置空、blindDone 标记本人盲评状态。</li>
 * </ul>
 *
 * <p>变异验证靶：m1=抽除同教师互斥校验、m2=抽除分差超标流转仲裁、m3=抽除仲裁分回填终分。
 */
@ExtendWith(MockitoExtension.class)
class SubjectiveBlindReviewServiceTest {

    private static final Long TEACHER_A = 7L;
    private static final Long TEACHER_B = 8L;
    private static final Long EXAM_ID = 10L;
    private static final Long QUESTION_ID = 21L;
    private static final Long SUBMISSION_ID = 101L;
    private static final Long GRADE_ROW_ID = 301L;

    @Mock
    private ExamMapper examMapper;
    @Mock
    private GradingSubmissionMapper gradingSubmissionMapper;
    @Mock
    private SubjectiveGradeMapper subjectiveGradeMapper;
    @Mock
    private SubjectiveBlindReviewMapper blindReviewMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private GradingPaperReader paperReader;
    @Mock
    private ReadYourWriteMark readYourWriteMark;

    private SubjectiveGradingService service;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, GradingSubmission.class);
        TableInfoHelper.initTableInfo(assistant, SubjectiveGrade.class);
        TableInfoHelper.initTableInfo(assistant, SubjectiveBlindReview.class);
        GradingQueryService gradingQueryService =
                new GradingQueryService(examMapper, gradingSubmissionMapper, subjectiveGradeMapper,
                        passThroughExecutor());
        service = new SubjectiveGradingService(gradingQueryService, paperReader,
                subjectiveGradeMapper, gradingSubmissionMapper, blindReviewMapper, userMapper,
                readYourWriteMark);
    }

    @AfterEach
    void clearSecurity() {
        SecurityUtil.clear();
    }

    // ==================== blindSubmit：分流与互斥 ====================

    @Test
    @DisplayName("一评自动分流：无 FIRST 记录时落 FIRST，终分保持 NULL 不回填")
    void firstReviewRecordsAndKeepsScoreNull() {
        loginAs(TEACHER_A);
        stubHappyPath();
        when(blindReviewMapper.selectList(any())).thenReturn(List.of());
        when(blindReviewMapper.insert(any(SubjectiveBlindReview.class))).thenReturn(1);

        BlindReviewSubmitResponse response =
                service.blindSubmit(EXAM_ID, submitRequest("5.0", "要点完整"));
        assertEquals(SubjectiveBlindReview.ROUND_FIRST, response.getRoundType());
        assertEquals(SubjectiveGradingService.STATUS_PENDING_SECOND, response.getStatus());
        assertNull(response.getFinalScore(), "一评绝不产生终分");
        ArgumentCaptor<SubjectiveBlindReview> captor =
                ArgumentCaptor.forClass(SubjectiveBlindReview.class);
        verify(blindReviewMapper).insert(captor.capture());
        assertEquals(SubjectiveBlindReview.ROUND_FIRST, captor.getValue().getRoundType());
        verify(subjectiveGradeMapper, never()).backfillFinalScore(anyLong(), any());
        verify(gradingSubmissionMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("同教师互斥（m1 护栏）：一评教师再次提交 400，背靠背红线")
    void sameTeacherSecondReviewRejected() {
        loginAs(TEACHER_A);
        stubHappyPath();
        when(blindReviewMapper.selectList(any()))
                .thenReturn(List.of(firstReview(TEACHER_A, "5.0")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.blindSubmit(EXAM_ID, submitRequest("6.0", null)));

        assertEquals(ResponseCode.BAD_REQUEST.getCode(), ex.getCode(),
                "同教师互斥必须 400 阻断（背靠背盲评红线）");
        verify(blindReviewMapper, never()).insert(any(SubjectiveBlindReview.class));
    }

    @Test
    @DisplayName("二评完成后重复提交 400：一轮一条由服务端拦截")
    void secondReviewRejectedWhenPairComplete() {
        loginAs(TEACHER_B);
        stubHappyPath();
        when(blindReviewMapper.selectList(any()))
                .thenReturn(List.of(firstReview(TEACHER_A, "5.0"), secondReview("6.0")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.blindSubmit(EXAM_ID, submitRequest("5.5", null)));

        assertEquals(ResponseCode.BAD_REQUEST.getCode(), ex.getCode());
    }

    // ==================== blindSubmit：分差裁决 ====================

    @Test
    @DisplayName("分差容差内（1.0 ≤ 2.0）：二评后自动取均分 5.5 回填终分并刷新答卷")
    void secondReviewWithinToleranceResolvesAverage() {
        loginAs(TEACHER_B);
        stubHappyPath();
        when(blindReviewMapper.selectList(any()))
                .thenReturn(List.of(firstReview(TEACHER_A, "5.0")));
        when(blindReviewMapper.insert(any(SubjectiveBlindReview.class))).thenReturn(1);
        when(subjectiveGradeMapper.backfillFinalScore(eq(GRADE_ROW_ID),
                eq(new BigDecimal("5.5")))).thenReturn(1);
        when(subjectiveGradeMapper.selectList(any())).thenReturn(List.of(gradeRow("5.5")));
        when(gradingSubmissionMapper.update(any(), any())).thenReturn(1);

        BlindReviewSubmitResponse response =
                service.blindSubmit(EXAM_ID, submitRequest("6.0", "基本正确"));

        assertEquals(SubjectiveBlindReview.ROUND_SECOND, response.getRoundType());
        assertEquals(SubjectiveGradingService.STATUS_RESOLVED, response.getStatus());
        assertEquals(0, new BigDecimal("5.5").compareTo(response.getFinalScore()));
        verify(subjectiveGradeMapper).backfillFinalScore(eq(GRADE_ROW_ID), eq(new BigDecimal("5.5")));
        verify(readYourWriteMark).mark();
    }

    @Test
    @DisplayName("分差超标（4.0 > 2.0）：不回填终分，流转 ARBITRATION_PENDING（m2 护栏）")
    void secondReviewExceedingToleranceFlowsToArbitration() {
        loginAs(TEACHER_B);
        stubHappyPath();
        when(blindReviewMapper.selectList(any()))
                .thenReturn(List.of(firstReview(TEACHER_A, "2.0")));
        when(blindReviewMapper.insert(any(SubjectiveBlindReview.class))).thenReturn(1);

        BlindReviewSubmitResponse response =
                service.blindSubmit(EXAM_ID, submitRequest("6.0", "回答完整"));

        assertEquals(SubjectiveGradingService.STATUS_ARBITRATION_PENDING, response.getStatus(),
                "分差超标必须流转待仲裁，不得自动定分");
        assertNull(response.getFinalScore(), "未达成共识绝不能出终分");
        verify(subjectiveGradeMapper, never()).backfillFinalScore(anyLong(), any());
        verify(gradingSubmissionMapper, never()).update(any(), any());
    }

    // ==================== arbitrationList ====================

    @Test
    @DisplayName("待仲裁清单只收「双评齐 + 分差超标 + 终分未定」条目，透出双方分数评语")
    void arbitrationListOnlyContainsExceededPending() {
        loginAs(TEACHER_A);
        stubOwnedExam();
        when(paperReader.readByExamId(EXAM_ID)).thenReturn(paperWithShortAnswer());
        List<SubjectiveBlindReview> pairs = new java.util.ArrayList<>();
        pairs.addAll(pair(201L, "2.0", TEACHER_A, "未达要点", "6.0", "回答完整"));
        pairs.addAll(pair(202L, "5.0", TEACHER_A, null, "5.5", null));
        pairs.addAll(pair(203L, "1.0", TEACHER_A, null, "6.0", null));
        when(blindReviewMapper.selectList(any())).thenReturn(pairs);
        SubjectiveGrade pendingGrade = gradeRow(null);
        pendingGrade.setSubmissionId(201L);
        pendingGrade.setStudentId(3L);
        SubjectiveGrade smallDiffGrade = gradeRow(null);
        smallDiffGrade.setSubmissionId(202L);
        smallDiffGrade.setStudentId(4L);
        SubjectiveGrade resolvedGrade = gradeRow("4.0");
        resolvedGrade.setSubmissionId(203L);
        when(subjectiveGradeMapper.selectList(any()))
                .thenReturn(List.of(pendingGrade, smallDiffGrade, resolvedGrade));
        User student = new User();
        student.setId(3L);
        student.setName("学生C");
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(student));

        List<BlindArbitrationItem> items = service.arbitrationList(EXAM_ID);

        assertEquals(1, items.size(), "小分差与已定分条目绝不能进入待仲裁清单");
        BlindArbitrationItem item = items.get(0);
        assertEquals(201L, item.getSubmissionId());
        assertEquals("学生C", item.getStudentName());
        assertEquals(0, new BigDecimal("2.0").compareTo(item.getFirstScore()));
        assertEquals("未达要点", item.getFirstComment());
        assertEquals(0, new BigDecimal("6.0").compareTo(item.getSecondScore()));
        assertEquals("回答完整", item.getSecondComment());
        assertEquals(0, new BigDecimal("4.0").compareTo(item.getScoreDiff()));
        assertEquals(SubjectiveGradingService.STATUS_ARBITRATION_PENDING, item.getStatus());
    }

    // ==================== arbitrate ====================

    @Test
    @DisplayName("仲裁终审（m3 护栏）：仲裁分 4.5 回填终分、记录仲裁轮次、结案刷新")
    void arbitrateBackfillsArbitrationScore() {
        loginAs(TEACHER_A);
        stubOwnedExam();
        stubHappyPath();
        when(blindReviewMapper.selectList(any()))
                .thenReturn(List.of(firstReview(TEACHER_A, "2.0"), secondReview("6.0")));
        when(blindReviewMapper.insert(any(SubjectiveBlindReview.class))).thenReturn(1);
        when(subjectiveGradeMapper.backfillFinalScore(eq(GRADE_ROW_ID),
                eq(new BigDecimal("4.5")))).thenReturn(1);
        when(subjectiveGradeMapper.selectList(any()))
                .thenReturn(List.of(gradeRow("4.5")));
        when(gradingSubmissionMapper.update(any(), any())).thenReturn(1);

        BlindArbitrateResponse response = service.arbitrate(EXAM_ID,
                arbitrateRequest(new BigDecimal("4.5"), "取中仲裁"));

        assertEquals(SubjectiveGradingService.STATUS_ARBITRATED, response.getStatus());
        assertEquals(0, new BigDecimal("4.5").compareTo(response.getFinalScore()));
        ArgumentCaptor<SubjectiveBlindReview> captor =
                ArgumentCaptor.forClass(SubjectiveBlindReview.class);
        verify(blindReviewMapper).insert(captor.capture());
        assertEquals(SubjectiveBlindReview.ROUND_ARBITRATION, captor.getValue().getRoundType());
        verify(subjectiveGradeMapper).backfillFinalScore(eq(GRADE_ROW_ID), eq(new BigDecimal("4.5")));
        verify(readYourWriteMark).mark();
    }

    @Test
    @DisplayName("仲裁分超过本题满分 6.0 → 400 拦截")
    void arbitrateRejectsScoreOverMax() {
        loginAs(TEACHER_A);
        stubOwnedExam();
        // 满分校验在快照校验层即抛，后续查答卷/批改行不会执行（仅桩试卷读取）
        when(paperReader.readByExamId(EXAM_ID)).thenReturn(paperWithShortAnswer());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.arbitrate(EXAM_ID, arbitrateRequest(new BigDecimal("9.0"), null)));

        assertEquals(ResponseCode.BAD_REQUEST.getCode(), ex.getCode(),
                "仲裁分不得超过本题满分");
        verify(blindReviewMapper, never()).insert(any(SubjectiveBlindReview.class));
    }

    @Test
    @DisplayName("非待仲裁状态（仅一评）→ 400 拦截，绝不凭空定分")
    void arbitrateRejectsNonPendingPair() {
        loginAs(TEACHER_A);
        stubOwnedExam();
        stubHappyPath();
        when(blindReviewMapper.selectList(any()))
                .thenReturn(List.of(firstReview(TEACHER_A, "2.0")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.arbitrate(EXAM_ID, arbitrateRequest(new BigDecimal("4.0"), null)));

        assertEquals(ResponseCode.BAD_REQUEST.getCode(), ex.getCode());
        verify(subjectiveGradeMapper, never()).backfillFinalScore(anyLong(), any());
    }

    // ==================== listBlindRows：脱敏视图 ====================

    @Test
    @DisplayName("盲评脱敏视图：考生打码、教师评分评语批改人版本全置空，blindDone 标记本人盲评")
    void blindRowsMasksIdentityAndHidesReviews() {
        loginAs(TEACHER_B);
        when(paperReader.readByExamId(EXAM_ID)).thenReturn(paperWithShortAnswer());
        SubjectiveGradeRow mine = blindRow(201L, "张三", "5.0");
        SubjectiveGradeRow other = blindRow(202L, "李四", null);
        when(subjectiveGradeMapper.selectWorkbenchRowsPaged(
                eq(EXAM_ID), eq(QUESTION_ID), any(), any(), any(), any(), any()))
                .thenReturn(new java.util.ArrayList<>(List.of(mine, other)));
        SubjectiveBlindReview myReview = new SubjectiveBlindReview();
        myReview.setSubmissionId(201L);
        when(blindReviewMapper.selectList(any())).thenReturn(List.of(myReview));

        SubjectiveGradePageResponse view = service.listBlindRows(EXAM_ID, QUESTION_ID);

        assertEquals(2, view.getRows().size());
        SubjectiveGradeRow maskedMine = view.getRows().get(0);
        assertEquals(SubjectiveGradingService.BLIND_MASKED_NAME, maskedMine.getStudentName());
        assertNull(maskedMine.getStudentId(), "考生 ID 不得透出");
        assertNull(maskedMine.getScore(), "教师评分不得透出");
        assertNull(maskedMine.getComment(), "教师评语不得透出");
        assertNull(maskedMine.getGraderId(), "批改人不得透出");
        assertNull(maskedMine.getVersion());
        assertEquals(Boolean.TRUE, maskedMine.getBlindDone(), "本人已盲评行必须标记");
        assertEquals(Boolean.FALSE, view.getRows().get(1).getBlindDone());
    }

    // ==================== 造数工具 ====================

    private void loginAs(Long userId) {
        LoginUser user = new LoginUser();
        user.setId(userId);
        user.setRoleLevel(RoleHierarchy.levelOf(RoleHierarchy.TEACHER));
        SecurityUtil.set(user);
    }

    private void stubOwnedExam() {
        Exam exam = new Exam();
        exam.setId(EXAM_ID);
        exam.setCreatedBy(TEACHER_A);
        when(examMapper.selectById(EXAM_ID)).thenReturn(exam);
    }

    /** blindSubmit/arbitrate 共用前置：快照试卷 + 提交存在 + 批改行存在。 */
    private void stubHappyPath() {
        when(paperReader.readByExamId(EXAM_ID)).thenReturn(paperWithShortAnswer());
        GradingSubmission submission = new GradingSubmission();
        submission.setId(SUBMISSION_ID);
        submission.setExamId(EXAM_ID);
        when(gradingSubmissionMapper.selectById(SUBMISSION_ID)).thenReturn(submission);
        when(subjectiveGradeMapper.selectOne(any())).thenReturn(gradeRow(null));
    }

    private SubjectiveGrade gradeRow(String score) {
        SubjectiveGrade row = new SubjectiveGrade();
        row.setId(GRADE_ROW_ID);
        row.setSubmissionId(SUBMISSION_ID);
        row.setExamId(EXAM_ID);
        row.setQuestionId(QUESTION_ID);
        row.setScore(score == null ? null : new BigDecimal(score));
        return row;
    }

    private SubjectiveBlindReview firstReview(Long graderId, String score) {
        SubjectiveBlindReview row = new SubjectiveBlindReview();
        row.setSubmissionId(SUBMISSION_ID);
        row.setQuestionId(QUESTION_ID);
        row.setRoundType(SubjectiveBlindReview.ROUND_FIRST);
        row.setGraderId(graderId);
        row.setScore(new BigDecimal(score));
        row.setComment("一评评语");
        return row;
    }

    private SubjectiveBlindReview secondReview(String score) {
        SubjectiveBlindReview row = new SubjectiveBlindReview();
        row.setSubmissionId(SUBMISSION_ID);
        row.setQuestionId(QUESTION_ID);
        row.setRoundType(SubjectiveBlindReview.ROUND_SECOND);
        row.setGraderId(TEACHER_B);
        row.setScore(new BigDecimal(score));
        return row;
    }

    /** 仲裁清单造数：同一 submission 的一评+二评记录对（两条都要入 selectList）。 */
    private List<SubjectiveBlindReview> pair(long submissionId, String firstScore, Long firstGrader,
                                             String firstComment, String secondScore, String secondComment) {
        SubjectiveBlindReview first = new SubjectiveBlindReview();
        first.setSubmissionId(submissionId);
        first.setQuestionId(QUESTION_ID);
        first.setRoundType(SubjectiveBlindReview.ROUND_FIRST);
        first.setGraderId(firstGrader);
        first.setScore(new BigDecimal(firstScore));
        first.setComment(firstComment);
        SubjectiveBlindReview second = new SubjectiveBlindReview();
        second.setSubmissionId(submissionId);
        second.setQuestionId(QUESTION_ID);
        second.setRoundType(SubjectiveBlindReview.ROUND_SECOND);
        second.setGraderId(TEACHER_B);
        second.setScore(new BigDecimal(secondScore));
        second.setComment(secondComment);
        return List.of(first, second);
    }

    private SubjectiveGradeRow blindRow(long submissionId, String name, String score) {
        SubjectiveGradeRow row = new SubjectiveGradeRow();
        row.setSubmissionId(submissionId);
        row.setQuestionId(QUESTION_ID);
        row.setStudentName(name);
        row.setStudentId(submissionId);
        row.setStudentAnswer("HTTP 是无状态的协议");
        row.setScore(score == null ? null : new BigDecimal(score));
        row.setComment("评语");
        row.setGraderId(TEACHER_A);
        row.setVersion(0);
        return row;
    }

    private BlindReviewSubmitRequest submitRequest(String score, String comment) {
        BlindReviewSubmitRequest request = new BlindReviewSubmitRequest();
        request.setSubmissionId(SUBMISSION_ID);
        request.setQuestionId(QUESTION_ID);
        request.setScore(new BigDecimal(score));
        request.setComment(comment);
        return request;
    }

    private com.exam.grading.dto.BlindArbitrateRequest arbitrateRequest(BigDecimal score, String comment) {
        com.exam.grading.dto.BlindArbitrateRequest request =
                new com.exam.grading.dto.BlindArbitrateRequest();
        request.setSubmissionId(SUBMISSION_ID);
        request.setQuestionId(QUESTION_ID);
        request.setScore(score);
        request.setComment(comment);
        return request;
    }

    private GradingPaper paperWithShortAnswer() {
        GradingQuestion question = new GradingQuestion(1, QUESTION_ID, QuestionType.SHORT_ANSWER,
                "简答", null, null, "HTTP,协议", new BigDecimal("6"));
        return new GradingPaper(EXAM_ID, "试卷", new BigDecimal("100"), List.of(question));
    }

    /** SlaveReadFallbackExecutor 透传桩（单测不触降级路径）。 */
    private SlaveReadFallbackExecutor passThroughExecutor() {
        SlaveReadFallbackExecutor executor = mock(SlaveReadFallbackExecutor.class);
        lenient().when(executor.execute(any(), any()))
                .thenAnswer((Answer<Object>) inv -> ((java.util.function.Supplier<?>) inv.getArgument(1)).get());
        return executor;
    }
}

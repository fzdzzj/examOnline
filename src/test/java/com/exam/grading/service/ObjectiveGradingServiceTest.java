package com.exam.grading.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.Update;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.exam.auth.security.LoginUser;
import com.exam.auth.security.RoleHierarchy;
import com.exam.auth.security.SecurityUtil;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.entity.SubjectiveGrade;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.grading.mapper.SubjectiveGradeMapper;
import com.exam.grading.model.GradingConfig;
import com.exam.grading.model.GradingPaper;
import com.exam.grading.model.GradingQuestion;
import com.exam.grading.strategy.GradingStrategyRegistry;
import com.exam.grading.strategy.JudgeGradingStrategy;
import com.exam.grading.strategy.ShortAnswerGradingStrategy;
import com.exam.grading.strategy.SingleChoiceGradingStrategy;
import com.exam.grading.support.GradingPaperReader;
import com.exam.question.entity.QuestionType;
import com.exam.submission.entity.ExamSubmission;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 整场判分主观行批取单测（spec batch-grading-subjective-upserts，Mockito 隔离、无 Spring 上下文）：
 *
 * <ul>
 *   <li>整场判分：已有 subjective_grades 行按本场答卷一次取出（selectList ≤1 次、selectOne 零次），
 *       逐份写入的初判建行 / 客观分落库语义不变；</li>
 *   <li>重判不覆盖教师批改：已有终分/评语/批改人/批改时间/version 的行只刷新答案快照与初判提示分；</li>
 *   <li>失败隔离：一份答案损坏只标记该份，另一份照常写入；</li>
 *   <li>无简答题或无答卷时不发 subjective_grades 查询。</li>
 * </ul>
 *
 * <p>驱动方式：mock 三个 Mapper 与快照读取器，判分策略用真实实现（单选/判断/简答关键词
 * 均为纯函数），ObjectiveGradingService 与 ExamGradingService 按生产装配手工构造——
 * 这样断言的是"编排层预取 + 引擎消费预取行"的真实链路，而非 mock 自说自话。
 */
@ExtendWith(MockitoExtension.class)
class ObjectiveGradingServiceTest {

    private static final Long TEACHER_ID = 7L;
    private static final Long EXAM_ID = 10L;
    private static final Long SUBMISSION_1 = 101L;
    private static final Long SUBMISSION_2 = 102L;
    private static final Long STUDENT_1 = 201L;
    private static final Long STUDENT_2 = 202L;

    @Mock
    private ExamMapper examMapper;
    @Mock
    private GradingSubmissionMapper gradingSubmissionMapper;
    @Mock
    private SubjectiveGradeMapper subjectiveGradeMapper;
    @Mock
    private GradingPaperReader paperReader;

    private ExamGradingService examGradingService;

    @BeforeEach
    void initEntityMetadata() {
        // LambdaUpdateWrapper.set() 在加片段时就解析列名（selectList 的 lambda 条件是惰性的，
        // 只有 update 需要实体元数据）——纯 Mockito 环境无 MyBatis 启动流程，须手动初始化
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, GradingSubmission.class);
        TableInfoHelper.initTableInfo(assistant, SubjectiveGrade.class);
    }

    @BeforeEach
    void wireServices() {
        GradingStrategyRegistry registry = new GradingStrategyRegistry(List.of(
                new SingleChoiceGradingStrategy(),
                new JudgeGradingStrategy(),
                new ShortAnswerGradingStrategy()));
        ObjectiveGradingService objectiveGradingService = new ObjectiveGradingService(
                paperReader, registry, new GradingConfig(new BigDecimal("1.0")),
                gradingSubmissionMapper, subjectiveGradeMapper);
        examGradingService = new ExamGradingService(
                examMapper, gradingSubmissionMapper, paperReader, objectiveGradingService);
    }

    @AfterEach
    void clearSecurity() {
        SecurityUtil.clear();
    }

    // ==================== 整场判分：一次取出 + 逐份写入 ====================

    @Test
    void runExamGradingLoadsSubjectiveRowsOnceAndUpsertsBothSubmissions() {
        loginAsTeacher();
        when(examMapper.selectById(EXAM_ID)).thenReturn(endedExam());
        GradingPaper paper = paperOf(
                singleChoice(11L, 1),
                shortAnswer(21L, 2, "HTTP,协议,无状态", "6"),
                shortAnswer(22L, 3, "TCP,可靠,传输", "4"));
        when(paperReader.readByExamId(EXAM_ID)).thenReturn(paper);
        String answers1 = "{\"11\":\"A\",\"21\":\"HTTP 是无状态的协议\",\"22\":\"TCP 可靠传输\"}";
        String answers2 = "{\"11\":\"B\",\"21\":\"HTTP\",\"22\":\"可靠\"}";
        when(gradingSubmissionMapper.selectList(any())).thenReturn(List.of(
                submission(SUBMISSION_1, STUDENT_1, answers1),
                submission(SUBMISSION_2, STUDENT_2, answers2)));
        when(paperReader.parseAnswers(answers1)).thenReturn(Map.of(
                11L, "A", 21L, "HTTP 是无状态的协议", 22L, "TCP 可靠传输"));
        when(paperReader.parseAnswers(answers2)).thenReturn(Map.of(
                11L, "B", 21L, "HTTP", 22L, "可靠"));
        when(gradingSubmissionMapper.update(any(), any())).thenReturn(1);

        ExamGradingService.RunStats stats = examGradingService.runExamGrading(EXAM_ID);

        assertEquals(2, stats.total());
        assertEquals(2, stats.success());
        assertEquals(0, stats.failed());

        // 整场对 subjective_grades 只查一次（selectList），逐份循环内零 selectOne
        verify(subjectiveGradeMapper, times(1)).selectList(any());
        verify(subjectiveGradeMapper, never()).selectOne(any());

        // 两份答卷 × 两道简答 = 4 行初判，行归属、答案快照与提示分逐行正确
        ArgumentCaptor<SubjectiveGrade> inserts = ArgumentCaptor.forClass(SubjectiveGrade.class);
        verify(subjectiveGradeMapper, times(4)).insert(inserts.capture());
        List<SubjectiveGrade> rows = inserts.getAllValues();
        assertEquals(SUBMISSION_1, rows.get(0).getSubmissionId());
        assertEquals(21L, rows.get(0).getQuestionId());
        assertEquals("HTTP 是无状态的协议", rows.get(0).getStudentAnswer());
        assertEquals(0, rows.get(0).getSuggestedScore().compareTo(new BigDecimal("6.0")));
        assertEquals(22L, rows.get(1).getQuestionId());
        assertEquals(0, rows.get(1).getSuggestedScore().compareTo(new BigDecimal("4.0")));
        assertEquals(SUBMISSION_2, rows.get(2).getSubmissionId());
        assertEquals(21L, rows.get(2).getQuestionId());
        assertEquals(0, rows.get(2).getSuggestedScore().compareTo(new BigDecimal("2.0")));
        assertEquals(22L, rows.get(3).getQuestionId());
        assertEquals("可靠", rows.get(3).getStudentAnswer());
        assertEquals(0, rows.get(3).getSuggestedScore().compareTo(new BigDecimal("1.3")));

        // 客观分照常按题型策略落库：s1 单选对 5.0，s2 错选 0.0
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Wrapper> objectiveWrites = ArgumentCaptor.forClass(Wrapper.class);
        verify(gradingSubmissionMapper, times(2)).update(any(), objectiveWrites.capture());
        assertTrue(paramValuePresent(asAbstractWrapper(objectiveWrites.getAllValues().get(0)),
                new BigDecimal("5.0")));
        assertTrue(paramValuePresent(asAbstractWrapper(objectiveWrites.getAllValues().get(0)),
                ObjectiveGradingService.GRADING_OK));
        assertTrue(paramValuePresent(asAbstractWrapper(objectiveWrites.getAllValues().get(1)),
                new BigDecimal("0.0")));
    }

    // ==================== 重判不覆盖教师批改 ====================

    @Test
    void runExamGradingRefreshesOnlySuggestionColumnsOnExistingTeacherRows() {
        loginAsTeacher();
        when(examMapper.selectById(EXAM_ID)).thenReturn(endedExam());
        GradingPaper paper = paperOf(
                shortAnswer(21L, 2, "HTTP,协议,无状态", "6"),
                shortAnswer(22L, 3, "TCP,可靠,传输", "4"));
        when(paperReader.readByExamId(EXAM_ID)).thenReturn(paper);
        String answers = "{\"21\":\"HTTP 是无状态的协议\",\"22\":\"TCP 可靠传输\"}";
        when(gradingSubmissionMapper.selectList(any())).thenReturn(List.of(
                submission(SUBMISSION_1, STUDENT_1, answers)));
        when(paperReader.parseAnswers(answers)).thenReturn(Map.of(
                21L, "HTTP 是无状态的协议", 22L, "TCP 可靠传输"));
        when(gradingSubmissionMapper.update(any(), any())).thenReturn(1);
        // 两道简答都已有教师批改过的行：终分/评语/批改人/批改时间/version 全在
        SubjectiveGrade row21 = teacherGradedRow(501L, SUBMISSION_1, 21L);
        SubjectiveGrade row22 = teacherGradedRow(502L, SUBMISSION_1, 22L);
        when(subjectiveGradeMapper.selectList(any())).thenReturn(List.of(row21, row22));

        ExamGradingService.RunStats stats = examGradingService.runExamGrading(EXAM_ID);

        assertEquals(1, stats.success());
        verify(subjectiveGradeMapper, times(1)).selectList(any());
        verify(subjectiveGradeMapper, never()).selectOne(any());
        verify(subjectiveGradeMapper, never()).insert(any(SubjectiveGrade.class));

        // 每条刷新 SET 列恰好是：答案快照 + 初判提示分/依据 + 题号 + 更新时间，
        // 终分 score / 评语 comment / 批改人 grader_id / 批改时间 graded_time / version 不在 SET 内
        Map<Long, BigDecimal> expectedSuggestion = Map.of(
                501L, new BigDecimal("6.0"), 502L, new BigDecimal("4.0"));
        Set<Long> refreshedIds = new HashSet<>();
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Wrapper> refreshes = ArgumentCaptor.forClass(Wrapper.class);
        verify(subjectiveGradeMapper, times(2)).update(any(), refreshes.capture());
        for (Object wrapper : refreshes.getAllValues()) {
            assertEquals(Set.of("student_answer", "suggested_score", "suggested_detail",
                    "question_number", "updated_time"), setColumns(wrapper));
            AbstractWrapper<?, ?, ?> refresh = asAbstractWrapper(wrapper);
            // eq 的参数惰性存放（SQL 段物化时才入 params），mock 环境不会真正生成 SQL，先物化一次
            refresh.getSqlSegment();
            Long targetId = null;
            for (Long rowId : expectedSuggestion.keySet()) {
                if (paramValuePresent(refresh, rowId)) {
                    targetId = rowId;
                    break;
                }
            }
            assertNotNull(targetId, "刷新必须按已有行 id 定位");
            assertTrue(paramValuePresent(refresh, expectedSuggestion.get(targetId)),
                    "该行应刷新为新初判提示分");
            refreshedIds.add(targetId);
        }
        assertEquals(Set.of(501L, 502L), refreshedIds);
    }

    // ==================== 失败隔离 ====================

    @Test
    void runExamGradingIsolatesBrokenSubmissionAndStillGradesTheOther() {
        loginAsTeacher();
        when(examMapper.selectById(EXAM_ID)).thenReturn(endedExam());
        GradingPaper paper = paperOf(
                singleChoice(11L, 1),
                shortAnswer(21L, 2, "HTTP,协议,无状态", "6"));
        when(paperReader.readByExamId(EXAM_ID)).thenReturn(paper);
        String broken = "{broken-json";
        String good = "{\"11\":\"A\",\"21\":\"HTTP 协议\"}";
        when(gradingSubmissionMapper.selectList(any())).thenReturn(List.of(
                submission(SUBMISSION_1, STUDENT_1, broken),
                submission(SUBMISSION_2, STUDENT_2, good)));
        when(paperReader.parseAnswers(broken))
                .thenThrow(new BusinessException(ResponseCode.INTERNAL_ERROR, "答卷答案解析失败"));
        when(paperReader.parseAnswers(good)).thenReturn(Map.of(11L, "A", 21L, "HTTP 协议"));
        when(gradingSubmissionMapper.update(any(), any())).thenReturn(1);

        ExamGradingService.RunStats stats = examGradingService.runExamGrading(EXAM_ID);

        assertEquals(2, stats.total());
        assertEquals(1, stats.success());
        assertEquals(1, stats.failed());
        assertEquals(SUBMISSION_1, stats.failures().get(0).submissionId());

        // 第一条 update 是坏卷的失败标记（grading_status=2 + 原因），第二条是好卷照常落客观分
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Wrapper> writes = ArgumentCaptor.forClass(Wrapper.class);
        verify(gradingSubmissionMapper, times(2)).update(any(), writes.capture());
        AbstractWrapper<?, ?, ?> failedMark = asAbstractWrapper(writes.getAllValues().get(0));
        AbstractWrapper<?, ?, ?> healthyWrite = asAbstractWrapper(writes.getAllValues().get(1));
        assertTrue(paramValuePresent(failedMark, ObjectiveGradingService.GRADING_FAILED));
        assertTrue(paramValuePresent(failedMark, "答卷答案解析失败"));
        assertTrue(paramValuePresent(healthyWrite, ObjectiveGradingService.GRADING_OK));

        // 好卷的简答初判行照常建立，且整场仍只批量取出一次
        ArgumentCaptor<SubjectiveGrade> inserts = ArgumentCaptor.forClass(SubjectiveGrade.class);
        verify(subjectiveGradeMapper, times(1)).insert(inserts.capture());
        assertEquals(SUBMISSION_2, inserts.getValue().getSubmissionId());
        assertEquals(21L, inserts.getValue().getQuestionId());
        verify(subjectiveGradeMapper, times(1)).selectList(any());
        verify(subjectiveGradeMapper, never()).selectOne(any());
    }

    // ==================== 无简答题 / 无答卷不查询 ====================

    @Test
    void runExamGradingSkipsSubjectiveQueryWhenPaperHasNoShortAnswers() {
        loginAsTeacher();
        when(examMapper.selectById(EXAM_ID)).thenReturn(endedExam());
        when(paperReader.readByExamId(EXAM_ID))
                .thenReturn(paperOf(singleChoice(11L, 1), judge(12L, 2)));
        String answers = "{\"11\":\"A\",\"12\":\"T\"}";
        when(gradingSubmissionMapper.selectList(any())).thenReturn(List.of(
                submission(SUBMISSION_1, STUDENT_1, answers)));
        when(paperReader.parseAnswers(answers)).thenReturn(Map.of(11L, "A", 12L, "T"));
        when(gradingSubmissionMapper.update(any(), any())).thenReturn(1);

        ExamGradingService.RunStats stats = examGradingService.runExamGrading(EXAM_ID);

        assertEquals(1, stats.success());
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Wrapper> writes = ArgumentCaptor.forClass(Wrapper.class);
        verify(gradingSubmissionMapper, times(1)).update(any(), writes.capture());
        assertTrue(paramValuePresent(asAbstractWrapper(writes.getValue()), new BigDecimal("9.0")),
                "单选 5 + 判断 4 = 9.0 应照常落库");
        verifyNoInteractions(subjectiveGradeMapper);
    }

    @Test
    void runExamGradingSkipsSubjectiveQueryWhenNoSubmissions() {
        loginAsTeacher();
        when(examMapper.selectById(EXAM_ID)).thenReturn(endedExam());
        when(paperReader.readByExamId(EXAM_ID))
                .thenReturn(paperOf(shortAnswer(21L, 1, "HTTP,协议", "6")));
        when(gradingSubmissionMapper.selectList(any())).thenReturn(List.of());

        ExamGradingService.RunStats stats = examGradingService.runExamGrading(EXAM_ID);

        assertEquals(0, stats.total());
        verifyNoInteractions(subjectiveGradeMapper);
    }

    // ==================== 单份重判 ====================

    @Test
    void rejudgeLoadsExistingRowsOnceForSingleSubmission() {
        loginAsTeacher();
        when(examMapper.selectById(EXAM_ID)).thenReturn(endedExam());
        when(paperReader.readByExamId(EXAM_ID)).thenReturn(paperOf(
                shortAnswer(21L, 1, "HTTP,协议,无状态", "6"),
                shortAnswer(22L, 2, "TCP,可靠,传输", "4")));
        GradingSubmission submission = submission(SUBMISSION_1, STUDENT_1,
                "{\"21\":\"HTTP 是无状态的协议\",\"22\":\"TCP 可靠传输\"}");
        when(gradingSubmissionMapper.selectById(SUBMISSION_1)).thenReturn(submission);
        when(paperReader.parseAnswers(submission.getAnswers())).thenReturn(Map.of(
                21L, "HTTP 是无状态的协议", 22L, "TCP 可靠传输"));
        when(gradingSubmissionMapper.update(any(), any())).thenReturn(1);

        ObjectiveGradingService.GradeOutcome outcome = examGradingService.rejudge(EXAM_ID, SUBMISSION_1);

        assertTrue(outcome.success());
        // 该份答卷的已有行一次取出，不再按题各查一次
        verify(subjectiveGradeMapper, times(1)).selectList(any());
        verify(subjectiveGradeMapper, never()).selectOne(any());
        ArgumentCaptor<SubjectiveGrade> inserts = ArgumentCaptor.forClass(SubjectiveGrade.class);
        verify(subjectiveGradeMapper, times(2)).insert(inserts.capture());
        for (SubjectiveGrade row : inserts.getAllValues()) {
            assertEquals(SUBMISSION_1, row.getSubmissionId());
        }
    }

    // ==================== 造数与断言工具 ====================

    private void loginAsTeacher() {
        LoginUser loginUser = new LoginUser();
        loginUser.setId(TEACHER_ID);
        loginUser.setRoleLevel(RoleHierarchy.levelOf(RoleHierarchy.TEACHER));
        SecurityUtil.set(loginUser);
    }

    private Exam endedExam() {
        Exam exam = new Exam();
        exam.setId(EXAM_ID);
        exam.setStatus(Exam.STATUS_ENDED);
        exam.setVersion(0);
        exam.setCreatedBy(TEACHER_ID);
        return exam;
    }

    private GradingSubmission submission(long id, long studentId, String answersJson) {
        GradingSubmission submission = new GradingSubmission();
        submission.setId(id);
        submission.setExamId(EXAM_ID);
        submission.setStudentId(studentId);
        submission.setStatus(ExamSubmission.STATUS_SUBMITTED);
        submission.setAnswers(answersJson);
        return submission;
    }

    /** 带教师批改留痕的已有主观行：终分/评语/批改人/批改时间/version 齐全。 */
    private SubjectiveGrade teacherGradedRow(long id, long submissionId, long questionId) {
        SubjectiveGrade row = new SubjectiveGrade();
        row.setId(id);
        row.setSubmissionId(submissionId);
        row.setExamId(EXAM_ID);
        row.setStudentId(STUDENT_1);
        row.setQuestionId(questionId);
        row.setQuestionNumber(2);
        row.setStudentAnswer("旧答案");
        row.setSuggestedScore(new BigDecimal("1.0"));
        row.setSuggestedDetail("旧初判");
        row.setScore(new BigDecimal("5.5"));
        row.setComment("表述完整");
        row.setGraderId(88L);
        row.setGradedTime(LocalDateTime.of(2026, 9, 1, 12, 0));
        row.setVersion(3);
        return row;
    }

    private GradingPaper paperOf(GradingQuestion... questions) {
        return new GradingPaper(1L, "试卷", new BigDecimal("100"), List.of(questions));
    }

    private GradingQuestion singleChoice(long questionId, int number) {
        return new GradingQuestion(number, questionId, QuestionType.SINGLE,
                "单选题", List.of("A", "B"), "A", new BigDecimal("5"));
    }

    private GradingQuestion judge(long questionId, int number) {
        return new GradingQuestion(number, questionId, QuestionType.JUDGE,
                "判断题", null, "T", new BigDecimal("4"));
    }

    private GradingQuestion shortAnswer(long questionId, int number, String keywords, String score) {
        return new GradingQuestion(number, questionId, QuestionType.SHORT_ANSWER,
                "简答题", null, keywords, new BigDecimal(score));
    }

    private static AbstractWrapper<?, ?, ?> asAbstractWrapper(Object wrapper) {
        return (AbstractWrapper<?, ?, ?>) wrapper;
    }

    /** Wrapper 携带的参数里是否含期望值（BigDecimal 按数值比较，规避 scale 差异）。 */
    private static boolean paramValuePresent(AbstractWrapper<?, ?, ?> wrapper, Object expected) {
        for (Object value : wrapper.getParamNameValuePairs().values()) {
            if (expected instanceof BigDecimal expectedDecimal && value instanceof BigDecimal decimal) {
                if (decimal.compareTo(expectedDecimal) == 0) {
                    return true;
                }
            } else if (Objects.equals(value, expected)) {
                return true;
            }
        }
        return false;
    }

    /** 解析 LambdaUpdateWrapper 的 SET 子句为列名集合（"col=#{...},col2=#{...}"）。 */
    private static Set<String> setColumns(Object wrapper) {
        String sqlSet = ((Update<?, ?>) wrapper).getSqlSet();
        assertNotNull(sqlSet);
        return Arrays.stream(sqlSet.split(","))
                .map(fragment -> fragment.substring(0, fragment.indexOf('=')).trim())
                .collect(java.util.stream.Collectors.toSet());
    }
}

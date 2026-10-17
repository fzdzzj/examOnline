package com.exam.grading.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.exam.auth.security.LoginUser;
import com.exam.auth.security.RoleHierarchy;
import com.exam.auth.security.SecurityUtil;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.config.ReadYourWriteMark;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.grading.dto.SubjectiveGradeRow;
import com.exam.grading.dto.SubjectiveScoreRequest;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.entity.SubjectiveGrade;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.grading.mapper.SubjectiveGradeMapper;
import com.exam.grading.model.GradingPaper;
import com.exam.grading.model.GradingQuestion;
import com.exam.grading.support.GradingPaperReader;
import com.exam.question.entity.QuestionType;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批改保存单行回读单测（spec changes/optimize-practice-pool-hot-path，Mockito 隔离、无 Spring 上下文）：
 *
 * <ul>
 *   <li>保存成功后的回读走单行查询（examId + questionId + submissionId 维度），
 *       不再「selectWorkbenchRows 全量学生工作台行 + 内存 filter」；</li>
 *   <li>回读结果字段与既有工作台行等价（响应形状不变），回读失败仍抛
 *       INTERNAL_ERROR「批改结果回读失败」；</li>
 *   <li>乐观锁 CAS、读己之写标记、防重与越权校验语义零改动。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SubjectiveGradingServiceSaveScoreReadBackTest {

    private static final Long TEACHER_ID = 7L;
    private static final Long EXAM_ID = 10L;
    private static final Long QUESTION_ID = 21L;
    private static final Long SUBMISSION_ID = 101L;

    @Mock
    private ExamMapper examMapper;
    @Mock
    private GradingSubmissionMapper gradingSubmissionMapper;
    @Mock
    private SubjectiveGradeMapper subjectiveGradeMapper;
    @Mock
    private GradingPaperReader paperReader;
    @Mock
    private ReadYourWriteMark readYourWriteMark;

    private SubjectiveGradingService service;

    @BeforeEach
    void setUp() {
        // 纯 Mockito 环境无 MyBatis 启动流程：为 lambda 条件初始化实体元数据
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, GradingSubmission.class);
        TableInfoHelper.initTableInfo(assistant, SubjectiveGrade.class);
        // GradingQueryService 用真实装配（requireOwnedExam 走真实 owner 校验），Mapper 全 mock
        GradingQueryService gradingQueryService =
                new GradingQueryService(examMapper, gradingSubmissionMapper, subjectiveGradeMapper,
                        passThroughExecutor());
        service = new SubjectiveGradingService(gradingQueryService, paperReader,
                subjectiveGradeMapper, gradingSubmissionMapper, readYourWriteMark);
        LoginUser teacher = new LoginUser();
        teacher.setId(TEACHER_ID);
        teacher.setRoleLevel(RoleHierarchy.levelOf(RoleHierarchy.TEACHER));
        SecurityUtil.set(teacher);
    }

    @AfterEach
    void clearSecurity() {
        SecurityUtil.clear();
    }

    @Test
    @DisplayName("保存成功路径：回读走单行查询，不再全量工作台行内存过滤")
    void saveScoreReadsBackSingleRowInsteadOfFullWorkbenchRows() {
        stubOwnedExam();
        when(paperReader.readByExamId(EXAM_ID)).thenReturn(paperWithShortAnswer());
        SubjectiveGrade existing = existingRow(new BigDecimal("4.5"));
        when(subjectiveGradeMapper.selectOne(any())).thenReturn(existing);
        when(subjectiveGradeMapper.casSaveScore(any(), any(), any(), any(), any())).thenReturn(1);
        when(subjectiveGradeMapper.selectList(any())).thenReturn(List.of(existing));
        when(gradingSubmissionMapper.update(any(), any())).thenReturn(1);
        SubjectiveGradeRow savedRow = workbenchRow(new BigDecimal("4.5"));
        when(subjectiveGradeMapper.selectWorkbenchRow(EXAM_ID, QUESTION_ID, SUBMISSION_ID))
                .thenReturn(savedRow);
        // 变异对照桩（m3 注入"退回全量 filter"时被消费；正确实现下为 lenient 不报未用桩）
        lenient().when(subjectiveGradeMapper.selectWorkbenchRows(any(), any()))
                .thenReturn(List.of(savedRow));

        SubjectiveGradeRow result = service.saveScore(EXAM_ID, request(new BigDecimal("4.5"), 0));

        assertSame(savedRow, result, "回读结果须为单行查询返回的行（响应形状不变）");
        verify(subjectiveGradeMapper).selectWorkbenchRow(EXAM_ID, QUESTION_ID, SUBMISSION_ID);
        verify(subjectiveGradeMapper, never()).selectWorkbenchRows(any(), any());
        // 乐观锁 CAS 与读己之写零改动
        verify(subjectiveGradeMapper).casSaveScore(existing.getId(), new BigDecimal("4.5"),
                null, TEACHER_ID, 0);
        verify(readYourWriteMark).mark();
    }

    @Test
    @DisplayName("判分未运行先批路径：建行后回读同样走单行查询")
    void saveScoreInsertPathAlsoReadsBackSingleRow() {
        stubOwnedExam();
        when(paperReader.readByExamId(EXAM_ID)).thenReturn(paperWithShortAnswer());
        when(subjectiveGradeMapper.selectOne(any())).thenReturn(null);
        GradingSubmission submission = new GradingSubmission();
        submission.setId(SUBMISSION_ID);
        submission.setExamId(EXAM_ID);
        when(gradingSubmissionMapper.selectById(SUBMISSION_ID)).thenReturn(submission);
        when(subjectiveGradeMapper.insert(any(SubjectiveGrade.class))).thenReturn(1);
        when(subjectiveGradeMapper.selectList(any())).thenReturn(List.of());
        when(gradingSubmissionMapper.update(any(), any())).thenReturn(1);
        SubjectiveGradeRow savedRow = workbenchRow(new BigDecimal("5.0"));
        when(subjectiveGradeMapper.selectWorkbenchRow(EXAM_ID, QUESTION_ID, SUBMISSION_ID))
                .thenReturn(savedRow);
        // 变异对照桩：退回全量 filter 形态时被消费；正确实现下 lenient 不报未用桩
        lenient().when(subjectiveGradeMapper.selectWorkbenchRows(any(), any()))
                .thenReturn(List.of(savedRow));

        SubjectiveGradeRow result = service.saveScore(EXAM_ID, request(new BigDecimal("5.0"), 0));

        assertSame(savedRow, result);
        verify(subjectiveGradeMapper).selectWorkbenchRow(EXAM_ID, QUESTION_ID, SUBMISSION_ID);
        verify(subjectiveGradeMapper, never()).selectWorkbenchRows(any(), any());
    }

    @Test
    @DisplayName("回读失败语义不变：单行回读取不到行仍抛 INTERNAL_ERROR「批改结果回读失败」")
    void saveScoreReadBackFailureStillThrowsInternalError() {
        stubOwnedExam();
        when(paperReader.readByExamId(EXAM_ID)).thenReturn(paperWithShortAnswer());
        SubjectiveGrade existing = existingRow(new BigDecimal("4.5"));
        when(subjectiveGradeMapper.selectOne(any())).thenReturn(existing);
        when(subjectiveGradeMapper.casSaveScore(any(), any(), any(), any(), any())).thenReturn(1);
        when(subjectiveGradeMapper.selectList(any())).thenReturn(List.of(existing));
        when(gradingSubmissionMapper.update(any(), any())).thenReturn(1);
        lenient().when(subjectiveGradeMapper.selectWorkbenchRow(EXAM_ID, QUESTION_ID, SUBMISSION_ID))
                .thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.saveScore(EXAM_ID, request(new BigDecimal("4.5"), 0)));

        assertEquals(ResponseCode.INTERNAL_ERROR.getCode(), ex.getCode());
        assertEquals("批改结果回读失败", ex.getMessage());
    }

    @Test
    @DisplayName("乐观锁 CAS 冲突语义不变：版本不一致仍抛 1012 冲突")
    void casConflictStillThrowsStateConflict() {
        stubOwnedExam();
        when(paperReader.readByExamId(EXAM_ID)).thenReturn(paperWithShortAnswer());
        SubjectiveGrade existing = existingRow(new BigDecimal("4.5"));
        when(subjectiveGradeMapper.selectOne(any())).thenReturn(existing);
        when(subjectiveGradeMapper.casSaveScore(any(), any(), any(), any(), any())).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.saveScore(EXAM_ID, request(new BigDecimal("4.5"), 0)));

        assertEquals(ResponseCode.STATE_CONFLICT.getCode(), ex.getCode());
        verify(subjectiveGradeMapper, never()).selectWorkbenchRows(any(), any());
        verify(subjectiveGradeMapper, never()).selectWorkbenchRow(any(), any(), any());
    }

    // ==================== 造数工具 ====================

    private void stubOwnedExam() {
        Exam exam = new Exam();
        exam.setId(EXAM_ID);
        exam.setCreatedBy(TEACHER_ID);
        when(examMapper.selectById(EXAM_ID)).thenReturn(exam);
    }

    private GradingPaper paperWithShortAnswer() {
        GradingQuestion question = new GradingQuestion(1, QUESTION_ID, QuestionType.SHORT_ANSWER,
                "简答", null, null, "HTTP,协议", new BigDecimal("6"));
        return new GradingPaper(1L, "试卷", new BigDecimal("100"), List.of(question));
    }

    private SubjectiveGrade existingRow(BigDecimal score) {
        SubjectiveGrade row = new SubjectiveGrade();
        row.setId(501L);
        row.setSubmissionId(SUBMISSION_ID);
        row.setExamId(EXAM_ID);
        row.setQuestionId(QUESTION_ID);
        row.setScore(score);
        row.setVersion(0);
        return row;
    }

    private SubjectiveGradeRow workbenchRow(BigDecimal score) {
        SubjectiveGradeRow row = new SubjectiveGradeRow();
        row.setId(501L);
        row.setSubmissionId(SUBMISSION_ID);
        row.setExamId(EXAM_ID);
        row.setStudentId(999L);
        row.setQuestionId(QUESTION_ID);
        row.setScore(score);
        row.setVersion(1);
        return row;
    }

    private SubjectiveScoreRequest request(BigDecimal score, int expectedVersion) {
        SubjectiveScoreRequest request = new SubjectiveScoreRequest();
        request.setSubmissionId(SUBMISSION_ID);
        request.setQuestionId(QUESTION_ID);
        request.setScore(score);
        request.setExpectedVersion(expectedVersion);
        return request;
    }

    /** SlaveReadFallbackExecutor 透传桩（harden-slave-read-failover 构造适配：单测不触降级路径） */
    private com.exam.config.SlaveReadFallbackExecutor passThroughExecutor() {
        com.exam.config.SlaveReadFallbackExecutor executor =
                org.mockito.Mockito.mock(com.exam.config.SlaveReadFallbackExecutor.class);
        org.mockito.Mockito.lenient().when(executor.execute(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(1)).get());
        return executor;
    }
}

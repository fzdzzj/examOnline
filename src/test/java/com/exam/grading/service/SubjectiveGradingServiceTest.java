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
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 初次批改并发唯一键冲突防御单测（change harden-draft-and-grading-boundaries，
 * Mockito 隔离、无 Spring 上下文）：
 *
 * <ul>
 *   <li>两教师同批一份卷同题且尚无批改行：后到的 insert 撞 uk_subjective_grades
 *       抛 DuplicateKeyException，必须被捕获转抛 STATE_CONFLICT(409)，
 *       不得把原始 DAO 异常透出成 500；</li>
 *   <li>冲突即终止：不刷新主观分合计、不打读己之写标记；</li>
 *   <li>插入成功的正常路径零改动：继续刷新合计并单行回读。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SubjectiveGradingServiceTest {

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
    private com.exam.grading.mapper.SubjectiveBlindReviewMapper blindReviewMapper;
    @Mock
    private com.exam.user.mapper.UserMapper userMapper;
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
                subjectiveGradeMapper, gradingSubmissionMapper, blindReviewMapper, userMapper,
                readYourWriteMark);
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
    @DisplayName("初次批改并发冲突：insert 撞唯一键转抛 409 STATE_CONFLICT，不透出 DAO 异常")
    void insertDuplicateKeyConvertedToStateConflict() {
        stubOwnedExam();
        when(paperReader.readByExamId(EXAM_ID)).thenReturn(paperWithShortAnswer());
        when(subjectiveGradeMapper.selectOne(any())).thenReturn(null);
        GradingSubmission submission = new GradingSubmission();
        submission.setId(SUBMISSION_ID);
        submission.setExamId(EXAM_ID);
        when(gradingSubmissionMapper.selectById(SUBMISSION_ID)).thenReturn(submission);
        when(subjectiveGradeMapper.insert(any(SubjectiveGrade.class)))
                .thenThrow(new DuplicateKeyException("Duplicate entry for key 'uk_subjective_grades'"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.saveScore(EXAM_ID, request()));

        assertEquals(ResponseCode.STATE_CONFLICT.getCode(), ex.getCode(),
                "唯一键冲突必须转成 409 语义（STATE_CONFLICT），不得透出原始异常");
        assertEquals("批改已被他人更新，请刷新后重试", ex.getMessage());
        // 冲突即终止：主观分合计刷新与读己之写标记都不得执行
        verify(gradingSubmissionMapper, never()).update(any(), any());
        verify(readYourWriteMark, never()).mark();
    }

    @Test
    @DisplayName("插入成功路径零改动：继续刷新合计并单行回读")
    void insertSuccessPathUnaffected() {
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
        SubjectiveGradeRow savedRow = new SubjectiveGradeRow();
        savedRow.setSubmissionId(SUBMISSION_ID);
        when(subjectiveGradeMapper.selectWorkbenchRow(EXAM_ID, QUESTION_ID, SUBMISSION_ID))
                .thenReturn(savedRow);

        SubjectiveGradeRow result = service.saveScore(EXAM_ID, request());

        assertSame(savedRow, result);
        verify(gradingSubmissionMapper).update(any(), any());
        verify(readYourWriteMark).mark();
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

    private SubjectiveScoreRequest request() {
        SubjectiveScoreRequest request = new SubjectiveScoreRequest();
        request.setSubmissionId(SUBMISSION_ID);
        request.setQuestionId(QUESTION_ID);
        request.setScore(new BigDecimal("5.0"));
        request.setExpectedVersion(0);
        return request;
    }

    /** SlaveReadFallbackExecutor 透传桩（harden-slave-read-failover 构造适配：单测不触降级路径） */
    private SlaveReadFallbackExecutor passThroughExecutor() {
        SlaveReadFallbackExecutor executor = mock(SlaveReadFallbackExecutor.class);
        lenient().when(executor.execute(any(), any()))
                .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(1)).get());
        return executor;
    }
}

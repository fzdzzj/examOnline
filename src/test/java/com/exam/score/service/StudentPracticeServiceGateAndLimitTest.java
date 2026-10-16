package com.exam.score.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.grading.model.GradeResult;
import com.exam.grading.model.GradingConfig;
import com.exam.grading.model.GradingQuestion;
import com.exam.grading.strategy.GradingStrategy;
import com.exam.grading.strategy.GradingStrategyRegistry;
import com.exam.question.entity.Question;
import com.exam.question.entity.QuestionType;
import com.exam.question.mapper.QuestionMapper;
import com.exam.score.dto.PracticeSubmitRequest;
import com.exam.score.dto.PracticeSubmitResponse;
import com.exam.score.entity.PracticeAttempt;
import com.exam.score.mapper.PracticeAttemptMapper;
import com.exam.score.support.QuestionScoreResolver;
import com.exam.submission.entity.ExamSubmission;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 错题重练提交热路径单测（spec changes/optimize-practice-pool-hot-path，Mockito 隔离、无 Spring 上下文）：
 *
 * <ul>
 *   <li>门控短路定位：目标题在靠前考试即命中，不再全量重建错题集合（逐场装配至命中即止）；</li>
 *   <li>门控 miss 路径：全部考试扫描完不命中 → 400 拒绝（与全量重建判定口径一致）；</li>
 *   <li>门控循环异常：记录 warn 后跳过该场（对齐同文件 L93/L265 口径），不得静默吞；</li>
 *   <li>attempt 回读 LIMIT 3：连对窗口即 3，回读查询必须带 LIMIT 3（消灭判定连对 ≥3 不变）。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class StudentPracticeServiceGateAndLimitTest {

    private static final Long STUDENT_ID = 42L;
    private static final Long EXAM_1 = 1001L;
    private static final Long EXAM_2 = 1002L;
    private static final Long EXAM_3 = 1003L;
    private static final Long QUESTION_1 = 201L;
    private static final Long QUESTION_2 = 202L;
    private static final BigDecimal FULL = new BigDecimal("5.0");

    @Mock
    private ExamMapper examMapper;
    @Mock
    private GradingSubmissionMapper gradingSubmissionMapper;
    @Mock
    private QuestionMapper questionMapper;
    @Mock
    private PracticeAttemptMapper practiceAttemptMapper;
    @Mock
    private ScoreExportService scoreExportService;
    @Mock
    private GradingStrategyRegistry strategyRegistry;
    @Mock
    private GradingStrategy strategy;

    private StudentPracticeService service;

    @BeforeEach
    void setUp() {
        // 纯 Mockito 环境无 MyBatis 启动流程：为 lambda 条件/投影初始化实体元数据（与
        // ObjectiveGradingServiceTest 同法，保证 wrapper 物化 SQL 时不缺 TableInfo）
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, GradingSubmission.class);
        TableInfoHelper.initTableInfo(assistant, Exam.class);
        TableInfoHelper.initTableInfo(assistant, Question.class);
        TableInfoHelper.initTableInfo(assistant, PracticeAttempt.class);
        service = new StudentPracticeService(examMapper, gradingSubmissionMapper, questionMapper,
                practiceAttemptMapper, scoreExportService, strategyRegistry,
                new GradingConfig(BigDecimal.ONE), new ObjectMapper());
    }

    // ==================== 门控短路定位 ====================

    @Test
    @DisplayName("门控命中即短路：目标题在首场考试即通过，不再装配后续考试")
    void gateShortCircuitsAtHitExam() {
        stubPublishedExams(EXAM_1, EXAM_2, EXAM_3);
        GradingQuestion q1Wrong = question(QUESTION_1, "A");
        GradingQuestion q1Full = question(QUESTION_1, "A");
        GradingQuestion q2Full = question(QUESTION_2, "B");
        when(scoreExportService.getPersonalReportWithoutRank(any(), any())).thenAnswer(inv -> {
            Exam exam = inv.getArgument(0);
            if (exam.getId().equals(EXAM_1)) {
                return report(q1Wrong, new BigDecimal("3.0"));   // 命中：q1 判分严格小于满分
            }
            if (exam.getId().equals(EXAM_2)) {
                return report(q1Full, FULL);                    // q1 满分不算错
            }
            return report(q2Full, FULL);                        // 不含 q1
        });
        stubSubmitPath();

        PracticeSubmitResponse response = service.submitPractice(STUDENT_ID, request(QUESTION_1, "A"));

        assertTrue(response.getCorrect(), "命中场次判分应通过门控并正常判分");
        // 关键：命中即短路——成绩单装配只发生 1 次（旧实现全量重建集合须装配全部 3 场）
        verify(scoreExportService, times(1)).getPersonalReportWithoutRank(any(), any());
    }

    @Test
    @DisplayName("门控 miss 路径：全部考试扫描完仍不命中 → 400 拒绝（与全量重建判定口径一致）")
    void gateScansAllExamsAndRejectsWhenQuestionNotInPool() {
        stubPublishedExams(EXAM_1, EXAM_2);
        GradingQuestion q1Full = question(QUESTION_1, "A");
        GradingQuestion q2Full = question(QUESTION_2, "B");
        when(scoreExportService.getPersonalReportWithoutRank(any(), any())).thenAnswer(inv -> {
            Exam exam = inv.getArgument(0);
            return exam.getId().equals(EXAM_1) ? report(q1Full, FULL) : report(q2Full, FULL);
        });

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.submitPractice(STUDENT_ID, request(QUESTION_1, "A")));

        assertEquals(ResponseCode.BAD_REQUEST.getCode(), ex.getCode());
        assertEquals("该题目不属于您的错题重练池", ex.getMessage());
        verify(scoreExportService, times(2)).getPersonalReportWithoutRank(any(), any());
    }

    @Test
    @DisplayName("门控定位循环异常记录 warn 并跳过该场，后续场次命中照常通过")
    void gateLogsWarnAndSkipsBrokenExamThenFindsQuestionInLaterExam() {
        stubPublishedExams(EXAM_1, EXAM_2);
        GradingQuestion q1Wrong = question(QUESTION_1, "A");
        when(scoreExportService.getPersonalReportWithoutRank(any(), any())).thenAnswer(inv -> {
            Exam exam = inv.getArgument(0);
            if (exam.getId().equals(EXAM_1)) {
                throw new IllegalStateException("成绩单快照损坏");
            }
            return report(q1Wrong, new BigDecimal("3.0"));
        });
        stubSubmitPath();

        MemoryAppender appender = new MemoryAppender();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(StudentPracticeService.class);
        logger.addAppender(appender);
        try {
            PracticeSubmitResponse response = service.submitPractice(STUDENT_ID, request(QUESTION_1, "A"));
            assertTrue(response.getCorrect(), "异常场跳过、命中场照常判分");
            assertTrue(appender.events.stream().anyMatch(e -> e.getLevel() == Level.WARN
                            && e.getMessage().contains("成绩单")),
                    "门控定位循环异常必须记录 warn（对齐同文件 L93/L265 口径），不得静默吞");
        } finally {
            logger.detachAppender(appender);
        }
    }

    // ==================== attempt 回读 LIMIT 3 ====================

    @Test
    @DisplayName("attempt 连对回读查询必须 LIMIT 3（连对窗口即 3）")
    void attemptHistoryReadCappedAtThree() {
        stubPublishedExams(EXAM_1);
        when(scoreExportService.getPersonalReportWithoutRank(any(), any()))
                .thenReturn(report(question(QUESTION_1, "A"), new BigDecimal("3.0")));
        stubSubmitPath();
        // 模拟：本次答对 + 历史已有多条连对记录（第四条及以前必须被窗口截断）
        when(practiceAttemptMapper.selectList(any())).thenReturn(List.of(
                attempt(true), attempt(true), attempt(true), attempt(true)));

        service.submitPractice(STUDENT_ID, request(QUESTION_1, "A"));

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Wrapper<PracticeAttempt>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(practiceAttemptMapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("LIMIT 3"), "连对窗口回读必须 LIMIT 3，实际 SQL: " + sql);
    }

    @Test
    @DisplayName("最近 3 条连对即消灭：消灭判定（连对 ≥3）语义不变")
    void masteryDecisionUnchangedWhenThreeRecentCorrect() {
        stubPublishedExams(EXAM_1);
        when(scoreExportService.getPersonalReportWithoutRank(any(), any()))
                .thenReturn(report(question(QUESTION_1, "A"), new BigDecimal("3.0")));
        stubSubmitPath();
        when(practiceAttemptMapper.selectList(any())).thenReturn(List.of(
                attempt(true), attempt(true), attempt(true)));

        PracticeSubmitResponse response = service.submitPractice(STUDENT_ID, request(QUESTION_1, "A"));

        assertEquals(3, response.getConsecutiveCorrect());
        assertTrue(response.getMastered(), "连对 3 次即消灭");
    }

    // ==================== 造数与断言工具 ====================

    private void stubPublishedExams(Long... examIds) {
        when(gradingSubmissionMapper.selectList(any())).thenReturn(
                Arrays.stream(examIds).map(this::gradedSubmission).toList());
        // loadPublishedExams 按 start_time 倒序 + id 倒序排序：给各场显式 startTime，
        // 保证声明顺序即装配顺序（先声明者更新 → 排在最前）
        List<Exam> exams = new ArrayList<>();
        LocalDateTime base = LocalDateTime.of(2026, 1, 1, 0, 0);
        for (int i = 0; i < examIds.length; i++) {
            Exam exam = publishedExam(examIds[i]);
            exam.setStartTime(base.plusHours(examIds.length - i));
            exams.add(exam);
        }
        when(examMapper.selectBatchIds(any())).thenReturn(exams);
    }

    /** 提交主路径后续桩：题库取题（单选）、判分策略（答对）、历史 attempt 由各用例自行桩。 */
    private void stubSubmitPath() {
        Question question = new Question();
        question.setId(QUESTION_1);
        question.setType(QuestionType.SINGLE.getCode());
        question.setContent("单选");
        question.setCorrectAnswer("A");
        question.setScore(FULL);
        question.setAnalysis("解析A");
        when(questionMapper.selectById(QUESTION_1)).thenReturn(question);
        when(strategyRegistry.dispatch(any())).thenReturn(strategy);
        when(strategy.grade(any(), any(), any())).thenReturn(new GradeResult(FULL, true, "答对"));
    }

    private Exam publishedExam(Long id) {
        Exam exam = new Exam();
        exam.setId(id);
        exam.setStatus(Exam.STATUS_PUBLISHED);
        return exam;
    }

    private GradingSubmission gradedSubmission(Long examId) {
        GradingSubmission submission = new GradingSubmission();
        submission.setId(9000L + examId);
        submission.setExamId(examId);
        submission.setStudentId(STUDENT_ID);
        submission.setStatus(ExamSubmission.STATUS_GRADED);
        submission.setTotalScore(new BigDecimal("60"));
        return submission;
    }

    private GradingQuestion question(Long questionId, String answer) {
        return new GradingQuestion(1, questionId, QuestionType.SINGLE,
                "单选", null, List.of("A", "B"), answer, FULL);
    }

    private ScoreExportService.PersonalReport report(GradingQuestion q, BigDecimal score) {
        QuestionScoreResolver.ResolvedQuestionScore resolved =
                new QuestionScoreResolver.ResolvedQuestionScore(score, q.score(),
                        score.compareTo(q.score()) == 0, true, null, null);
        return new ScoreExportService.PersonalReport(null, null, null, null, null, 0, false,
                List.of(q), java.util.Map.of(q.questionId(), resolved), java.util.Map.of());
    }

    private PracticeAttempt attempt(boolean correct) {
        PracticeAttempt attempt = new PracticeAttempt();
        attempt.setId(1L);
        attempt.setStudentId(STUDENT_ID);
        attempt.setQuestionId(QUESTION_1);
        attempt.setIsCorrect(correct);
        return attempt;
    }

    private PracticeSubmitRequest request(Long questionId, String answer) {
        PracticeSubmitRequest request = new PracticeSubmitRequest();
        request.setQuestionId(questionId);
        request.setAnswer(answer);
        return request;
    }

    /** 内存日志捕获器（不依赖 logback 具体版本的 ListAppender 包路径）。 */
    private static final class MemoryAppender extends AppenderBase<ILoggingEvent> {
        private final List<ILoggingEvent> events = new ArrayList<>();

        @Override
        protected void append(ILoggingEvent event) {
            events.add(event);
        }
    }
}

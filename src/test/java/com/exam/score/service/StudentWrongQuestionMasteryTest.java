package com.exam.score.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.grading.model.GradingQuestion;
import com.exam.question.entity.Question;
import com.exam.question.entity.QuestionType;
import com.exam.question.mapper.QuestionMapper;
import com.exam.score.dto.WrongQuestionItem;
import com.exam.score.dto.WrongQuestionPageResponse;
import com.exam.score.service.StudentPracticeService.PracticeProgress;
import com.exam.score.support.QuestionScoreResolver;
import com.exam.submission.entity.ExamSubmission;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 错题本条目掌握度三态单测（spec add-student-wrong-question-mastery，Mockito 隔离）：
 * GET /api/scores/my/wrong-questions 返回的每个条目必须显式携带
 * masteryStatus（0/1/2）与 masteryStatusName（UNTOUCHED/PRACTICING/MASTERED），
 * 且既有 consecutiveCorrect 与 mastered 字段保持向下兼容不变。
 */
@ExtendWith(MockitoExtension.class)
class StudentWrongQuestionMasteryTest {

    private static final Long STUDENT_ID = 42L;
    private static final Long EXAM_1 = 1001L;
    private static final Long QUESTION_1 = 201L;
    private static final BigDecimal FULL = new BigDecimal("5.0");
    private static final BigDecimal WRONG_SCORE = new BigDecimal("2.0");

    @Mock
    private ExamMapper examMapper;
    @Mock
    private GradingSubmissionMapper gradingSubmissionMapper;
    @Mock
    private QuestionMapper questionMapper;
    @Mock
    private ScoreExportService scoreExportService;
    @Mock
    private StudentPracticeService studentPracticeService;

    private StudentWrongQuestionService service;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, GradingSubmission.class);
        TableInfoHelper.initTableInfo(assistant, Exam.class);
        service = new StudentWrongQuestionService(examMapper, gradingSubmissionMapper,
                questionMapper, scoreExportService, studentPracticeService);
    }

    @Test
    @DisplayName("连对 0 → masteryStatus=0/UNTOUCHED；既有 consecutiveCorrect/mastered 字段向下兼容")
    void wrongQuestionItemUntouchedState() throws Exception {
        stubExamWithOneWrongQuestion();
        when(studentPracticeService.calculateProgressMap(eq(STUDENT_ID), any()))
                .thenReturn(Map.of(QUESTION_1, new PracticeProgress(0, false)));

        WrongQuestionPageResponse resp = service.listWrongQuestions(STUDENT_ID, 1, 10, false);

        WrongQuestionItem item = soleWrongItem(resp);
        assertEquals(0, item.getMasteryStatus(), "连对 0 三态应为 UNTOUCHED");
        assertEquals("UNTOUCHED", item.getMasteryStatusName());
        assertEquals(0, item.getConsecutiveCorrect(), "既有连对字段保持不变");
        assertFalse(item.getMastered(), "既有消灭标记保持不变");
    }

    @Test
    @DisplayName("连对 1~2 → masteryStatus=1/PRACTICING（攻克中）")
    void wrongQuestionItemPracticingState() throws Exception {
        stubExamWithOneWrongQuestion();
        when(studentPracticeService.calculateProgressMap(eq(STUDENT_ID), any()))
                .thenReturn(Map.of(QUESTION_1, new PracticeProgress(2, false)));

        WrongQuestionPageResponse resp = service.listWrongQuestions(STUDENT_ID, 1, 10, false);

        WrongQuestionItem item = soleWrongItem(resp);
        assertEquals(1, item.getMasteryStatus(), "连对 2 三态应为 PRACTICING");
        assertEquals("PRACTICING", item.getMasteryStatusName());
        assertEquals(2, item.getConsecutiveCorrect());
        assertFalse(item.getMastered());
    }

    @Test
    @DisplayName("连对 3（includeMastered 翻回）→ masteryStatus=2/MASTERED")
    void wrongQuestionItemMasteredState() throws Exception {
        stubExamWithOneWrongQuestion();
        when(studentPracticeService.calculateProgressMap(eq(STUDENT_ID), any()))
                .thenReturn(Map.of(QUESTION_1, new PracticeProgress(3, true)));

        WrongQuestionPageResponse resp = service.listWrongQuestions(STUDENT_ID, 1, 10, true);

        WrongQuestionItem item = soleWrongItem(resp);
        assertEquals(2, item.getMasteryStatus(), "连对 3 三态应为 MASTERED");
        assertEquals("MASTERED", item.getMasteryStatusName());
        assertEquals(3, item.getConsecutiveCorrect());
        assertTrue(item.getMastered());
    }

    // ==================== 造数工具 ====================

    private void stubExamWithOneWrongQuestion() {
        when(gradingSubmissionMapper.selectList(any())).thenReturn(List.of(submission()));
        when(examMapper.selectBatchIds(any())).thenReturn(List.of(publishedExam()));
        GradingQuestion wrongQuestion = new GradingQuestion(1, QUESTION_1, QuestionType.SINGLE,
                "单选", null, List.of("A", "B"), "A", FULL);
        when(scoreExportService.getPersonalReportWithoutRank(any(), any())).thenReturn(
                new ScoreExportService.PersonalReport("stu_42", "学生甲",
                        WRONG_SCORE, BigDecimal.ZERO, WRONG_SCORE,
                        1, false,
                        List.of(wrongQuestion),
                        Map.of(QUESTION_1, new QuestionScoreResolver.ResolvedQuestionScore(
                                WRONG_SCORE, FULL, false, true, null, "判定明细")),
                        Map.of(QUESTION_1, "B")));
        Question entity = new Question();
        entity.setId(QUESTION_1);
        entity.setAnalysis("单选解析");
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(entity));
    }

    private WrongQuestionItem soleWrongItem(WrongQuestionPageResponse resp) {
        assertNotNull(resp);
        assertEquals(1, resp.getGroups().size());
        assertEquals(1, resp.getGroups().get(0).getWrongQuestions().size());
        return resp.getGroups().get(0).getWrongQuestions().get(0);
    }

    private GradingSubmission submission() {
        GradingSubmission submission = new GradingSubmission();
        submission.setId(9001L);
        submission.setExamId(EXAM_1);
        submission.setStudentId(STUDENT_ID);
        submission.setStatus(ExamSubmission.STATUS_GRADED);
        submission.setTotalScore(new BigDecimal("60"));
        return submission;
    }

    private Exam publishedExam() {
        Exam exam = new Exam();
        exam.setId(EXAM_1);
        exam.setTitle("期末模考");
        exam.setStatus(Exam.STATUS_PUBLISHED);
        exam.setStartTime(LocalDateTime.of(2026, 1, 1, 9, 0));
        return exam;
    }
}

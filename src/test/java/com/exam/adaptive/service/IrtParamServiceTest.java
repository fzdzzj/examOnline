package com.exam.adaptive.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.exam.adaptive.entity.QuestionIrtParam;
import com.exam.adaptive.mapper.QuestionIrtParamMapper;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.grading.model.GradingQuestion;
import com.exam.question.entity.Question;
import com.exam.question.entity.QuestionType;
import com.exam.question.mapper.QuestionMapper;
import com.exam.score.entity.PracticeAttempt;
import com.exam.score.mapper.PracticeAttemptMapper;
import com.exam.score.service.ExamAnalysisReportService;
import com.exam.score.service.ScoreExportService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IRT 标定服务单测（spec add-irt-adaptive-testing，Mockito 隔离 Spring）：
 *
 * <ul>
 *   <li>CTT→3PL 经验转换：a=0.5+2D 钳制 [0.5,2.5]、b=logit(P) 中值修正、c 按题型固定；</li>
 *   <li>标定聚合：考试作答 + 练习作答合并样本量与正确率；</li>
 *   <li>样本不足（&lt;4）或缺区分度 D 的题目 poolAvailable=false（不进题池）；</li>
 *   <li>同场次 TTL 内幂等跳过；逐场标定单场异常跳过不中断；</li>
 *   <li>可用题池过滤：软删/简答题排除。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class IrtParamServiceTest {

    private static final Long EXAM_ID = 11L;
    private static final long Q_SINGLE = 101L;
    private static final long Q_JUDGE = 102L;

    @Mock
    private ExamAnalysisReportService examAnalysisReportService;
    @Mock
    private GradingSubmissionMapper gradingSubmissionMapper;
    @Mock
    private QuestionMapper questionMapper;
    @Mock
    private PracticeAttemptMapper practiceAttemptMapper;
    @Mock
    private QuestionIrtParamMapper questionIrtParamMapper;

    private IrtParamService service;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, QuestionIrtParam.class);
        TableInfoHelper.initTableInfo(assistant, PracticeAttempt.class);
        TableInfoHelper.initTableInfo(assistant, GradingSubmission.class);
        service = new IrtParamService(examAnalysisReportService, gradingSubmissionMapper,
                questionMapper, practiceAttemptMapper, questionIrtParamMapper);
    }

    // ==================== 转换公式 ====================

    @Test
    @DisplayName("a 由区分度 D 线性映射并钳制：D=0→0.5、D=0.5→1.5、D=1→2.5、负 D 落到下界")
    void discriminationMapsToA() {
        assertEquals(0.5, IrtParamService.discriminationToA(0.0).doubleValue(), 1e-9);
        assertEquals(1.5, IrtParamService.discriminationToA(0.5).doubleValue(), 1e-9);
        assertEquals(2.5, IrtParamService.discriminationToA(1.0).doubleValue(), 1e-9);
        assertEquals(0.5, IrtParamService.discriminationToA(-1.0).doubleValue(), 1e-9);
        assertEquals(1.5, IrtParamService.discriminationToA(null).doubleValue(), 1e-9);
    }

    @Test
    @DisplayName("b 由难度 P 做 logit 变换并中值修正：P=0.5→0、P=0.8→1.3863、P=0/1 钳制到 ±2.9444")
    void difficultyMapsToLogitB() {
        assertEquals(0.0, IrtParamService.difficultyToB(0.5).doubleValue(), 1e-9);
        assertEquals(1.3863, IrtParamService.difficultyToB(0.8).doubleValue(), 1e-4);
        assertEquals(-2.9444, IrtParamService.difficultyToB(0.0).doubleValue(), 1e-4);
        assertEquals(2.9444, IrtParamService.difficultyToB(1.0).doubleValue(), 1e-4);
        assertEquals(0.0, IrtParamService.difficultyToB(null).doubleValue(), 1e-9);
    }

    @Test
    @DisplayName("c 按题型固定：单选 0.25、判断 0.50、多选/填空 0")
    void guessingDependsOnType() {
        assertEquals(0.25, IrtParamService.guessingOf(QuestionType.SINGLE).doubleValue(), 1e-9);
        assertEquals(0.50, IrtParamService.guessingOf(QuestionType.JUDGE).doubleValue(), 1e-9);
        assertEquals(0.0, IrtParamService.guessingOf(QuestionType.MULTIPLE).doubleValue(), 1e-9);
        assertEquals(0.0, IrtParamService.guessingOf(QuestionType.FILL_BLANK).doubleValue(), 1e-9);
    }

    // ==================== 标定聚合 ====================

    @Test
    @DisplayName("标定：考试作答与练习作答合并样本，样本不足题标记不可用，参数按公式落库")
    void calibrateExamMergesPracticeAndExamSamples() {
        stubMetricSource();
        when(questionIrtParamMapper.selectCount(any())).thenReturn(0L);
        when(questionIrtParamMapper.selectOne(any())).thenReturn(null);
        // 练习作答：Q_SINGLE 2 次全对
        PracticeAttempt p1 = attempt(Q_SINGLE, true);
        PracticeAttempt p2 = attempt(Q_SINGLE, true);
        when(practiceAttemptMapper.selectList(any())).thenReturn(List.of(p1, p2));

        int calibrated = service.calibrateExam(EXAM_ID);

        assertEquals(2, calibrated);
        ArgumentCaptor<QuestionIrtParam> captor = ArgumentCaptor.forClass(QuestionIrtParam.class);
        verify(questionIrtParamMapper, times(2)).insert(captor.capture());
        QuestionIrtParam single = captor.getAllValues().stream()
                .filter(row -> Q_SINGLE == row.getQuestionId()).findFirst().orElseThrow();
        // P = (10*0.8 + 2) / (10 + 2) = 0.8333 → b = ln(5) = 1.6094
        assertEquals(1.7, single.getDiscrimination().doubleValue(), 1e-9);
        assertEquals(1.6094, single.getDifficulty().doubleValue(), 1e-4);
        assertEquals(0.25, single.getGuessing().doubleValue(), 1e-9);
        assertEquals(12, single.getSampleSize());
        assertTrue(single.getPoolAvailable());
        assertEquals(EXAM_ID, single.getSourceExamId());

        QuestionIrtParam judge = captor.getAllValues().stream()
                .filter(row -> Q_JUDGE == row.getQuestionId()).findFirst().orElseThrow();
        // 样本量 2 < 4 且区分度缺失 → 不入池
        assertFalse(judge.getPoolAvailable());
        assertEquals(2, judge.getSampleSize());
        assertEquals(0.5, judge.getGuessing().doubleValue(), 1e-9);
    }

    @Test
    @DisplayName("幂等：同场次 TTL 内已标定则跳过（不再聚合 CTT，也不写参数）")
    void calibrateSkipsFreshExam() {
        when(questionIrtParamMapper.selectCount(any())).thenReturn(1L);

        assertEquals(0, service.calibrateExam(EXAM_ID));
        verify(examAnalysisReportService, never()).questionMetrics(anyLong());
        verify(questionIrtParamMapper, never()).insert(any(QuestionIrtParam.class));
    }

    @Test
    @DisplayName("逐场标定：单场异常只记 warn 跳过，不中断其余场次")
    void calibrateStudentPoolSkipsFailingExam() {
        when(gradingSubmissionMapper.selectList(any()))
                .thenReturn(List.of(submission(EXAM_ID), submission(EXAM_ID + 1)));
        when(questionIrtParamMapper.selectCount(any())).thenReturn(0L);
        when(examAnalysisReportService.questionMetrics(EXAM_ID)).thenReturn(List.of());
        when(examAnalysisReportService.questionMetrics(EXAM_ID + 1))
                .thenThrow(new IllegalStateException("boom"));

        // EXAM_ID 正常标定；EXAM_ID+1 抛错被 warn 跳过——返回值是"成功标定场次数"而非"覆盖场次数"
        assertEquals(1, service.calibrateStudentPool(7L));
    }

    @Test
    @DisplayName("可用题池：排除简答题，保留客观题参数")
    void usablePoolExcludesShortAnswer() {
        QuestionIrtParam single = param(Q_SINGLE);
        QuestionIrtParam shortAnswer = param(103L);
        when(questionIrtParamMapper.selectList(any())).thenReturn(List.of(single, shortAnswer));
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(
                question(Q_SINGLE, QuestionType.SINGLE),
                question(103L, QuestionType.SHORT_ANSWER)));

        List<QuestionIrtParam> usable = service.usablePool();

        assertEquals(1, usable.size());
        assertEquals(Q_SINGLE, usable.get(0).getQuestionId());
    }

    @Test
    @DisplayName("按题取参数：空 ID 与未标定题返回 null")
    void findParamReturnsNullWhenMissing() {
        when(questionIrtParamMapper.selectOne(any())).thenReturn(null);
        assertNull(service.findParam(null));
        assertNull(service.findParam(999L));
    }

    // ==================== 夹具 ====================

    private void stubMetricSource() {
        when(examAnalysisReportService.questionMetrics(EXAM_ID)).thenReturn(List.of(
                metric(question(Q_SINGLE, QuestionType.SINGLE), 10, 0.8, 0.6),
                metric(question(Q_JUDGE, QuestionType.JUDGE), 2, 1.0, null)));
    }

    private ScoreExportService.QuestionStatMetric metric(Question question, int answered,
                                                         double correctRate, Double discrimination) {
        GradingQuestion gradingQuestion = new GradingQuestion(1, question.getId(),
                QuestionType.of(question.getType()), question.getContent(), null,
                List.of("A", "B"), "A", BigDecimal.valueOf(5));
        return new ScoreExportService.QuestionStatMetric(gradingQuestion, answered, 0.0, 0.0,
                correctRate, discrimination, List.of());
    }

    private Question question(long id, QuestionType type) {
        Question question = new Question();
        question.setId(id);
        question.setType(type.getCode());
        question.setContent("题干-" + id);
        question.setChoices("[\"A\",\"B\"]");
        question.setCorrectAnswer("A");
        question.setScore(BigDecimal.valueOf(5));
        return question;
    }

    private QuestionIrtParam param(long questionId) {
        QuestionIrtParam param = new QuestionIrtParam();
        param.setQuestionId(questionId);
        param.setDiscrimination(BigDecimal.valueOf(1.2));
        param.setDifficulty(BigDecimal.ZERO);
        param.setGuessing(BigDecimal.valueOf(0.25));
        param.setSampleSize(20);
        param.setPoolAvailable(true);
        return param;
    }

    private PracticeAttempt attempt(long questionId, boolean correct) {
        PracticeAttempt attempt = new PracticeAttempt();
        attempt.setQuestionId(questionId);
        attempt.setIsCorrect(correct);
        return attempt;
    }

    private GradingSubmission submission(long examId) {
        GradingSubmission submission = new GradingSubmission();
        submission.setExamId(examId);
        return submission;
    }
}

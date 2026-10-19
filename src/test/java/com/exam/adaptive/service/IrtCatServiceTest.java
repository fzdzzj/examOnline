package com.exam.adaptive.service;

import com.exam.adaptive.dto.AdaptiveQuizAnswerRequest;
import com.exam.adaptive.dto.AdaptiveQuizAnswerResponse;
import com.exam.adaptive.dto.AdaptiveQuizReportResponse;
import com.exam.adaptive.dto.AdaptiveQuizStartRequest;
import com.exam.adaptive.dto.AdaptiveQuizStartResponse;
import com.exam.adaptive.entity.CatQuizSession;
import com.exam.adaptive.entity.QuestionIrtParam;
import com.exam.adaptive.mapper.CatQuizSessionMapper;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.grading.model.GradeResult;
import com.exam.grading.model.GradingConfig;
import com.exam.grading.model.GradingQuestion;
import com.exam.grading.strategy.GradingStrategy;
import com.exam.grading.strategy.GradingStrategyRegistry;
import com.exam.question.entity.Question;
import com.exam.question.entity.QuestionType;
import com.exam.question.mapper.QuestionMapper;
import com.exam.score.entity.PracticeAttempt;
import com.exam.score.mapper.PracticeAttemptMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * CAT 引擎单测（spec add-irt-adaptive-testing，Mockito 隔离 Spring）：
 *
 * <ul>
 *   <li>开局：b 最接近 0 的题（题池顺序无关，保证不是"取第一条"）；</li>
 *   <li>θ 更新（m1）：答对上调 θ、答错下调 θ，且作答后 SE 收紧；</li>
 *   <li>最大信息量选题（m2）：排除已答题，选信息量更大者而非题池顺序第一条；</li>
 *   <li>终止规则（m3）：MAX_ITEMS / SE_CONVERGED / POOL_EXHAUSTED 三路；</li>
 *   <li>判分同源：逐题落 practice_attempt；归属隔离：越权 403 且不落任何作答；</li>
 *   <li>报告：θ/SE/正确率/终止原因/逐题轨迹。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class IrtCatServiceTest {

    private static final Long STUDENT = 7L;
    private static final Long OTHER_STUDENT = 8L;
    private static final Long SESSION_ID = 55L;
    private static final long Q1 = 101L;
    private static final long Q2 = 102L;
    private static final long Q3 = 103L;

    @Mock
    private IrtParamService irtParamService;
    @Mock
    private CatQuizSessionMapper catQuizSessionMapper;
    @Mock
    private QuestionMapper questionMapper;
    @Mock
    private PracticeAttemptMapper practiceAttemptMapper;
    @Mock
    private GradingStrategyRegistry strategyRegistry;
    @Mock
    private GradingStrategy strategy;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private IrtCatService service;

    @BeforeEach
    void setUp() {
        service = new IrtCatService(irtParamService, catQuizSessionMapper, questionMapper,
                practiceAttemptMapper, strategyRegistry, new GradingConfig(BigDecimal.ONE), objectMapper);
    }

    // ==================== 开局 ====================

    @Test
    @DisplayName("开局：取 b 最接近 0 的题（题池顺序无关），会话落库且响应不泄露答案")
    void startSelectsMediumDifficultyItem() {
        QuestionIrtParam easy = param(Q1, 1.2, -2.0, 0.25);
        QuestionIrtParam medium = param(Q2, 1.2, 0.05, 0.25);
        QuestionIrtParam hard = param(Q3, 1.2, 2.0, 0.25);
        // 故意把中等题放在最后：若实现退化成"取第一条"，断言立即击红
        when(irtParamService.usablePool()).thenReturn(List.of(easy, hard, medium));
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(
                question(Q1, QuestionType.SINGLE), question(Q2, QuestionType.SINGLE),
                question(Q3, QuestionType.SINGLE)));
        ArgumentCaptor<CatQuizSession> captor = ArgumentCaptor.forClass(CatQuizSession.class);
        when(catQuizSessionMapper.insert(any(CatQuizSession.class))).thenAnswer(invocation -> {
            CatQuizSession inserted = invocation.getArgument(0);
            inserted.setId(SESSION_ID);
            return 1;
        });

        AdaptiveQuizStartResponse response = service.start(STUDENT, new AdaptiveQuizStartRequest());

        assertEquals(SESSION_ID, response.getSessionId());
        assertNotNull(response.getCurrentQuestion());
        assertEquals(Q2, response.getCurrentQuestion().getQuestionId());
        assertEquals(0, response.getTheta().doubleValue(), 1e-9);
        assertEquals(10, response.getMaxItems());
        assertEquals(0.30, response.getSeThreshold().doubleValue(), 1e-9);
        verify(catQuizSessionMapper).insert(captor.capture());
        assertEquals(Q2, captor.getValue().getCurrentQuestionId());
        assertEquals(CatQuizSession.STATUS_IN_PROGRESS, captor.getValue().getStatus());
    }

    @Test
    @DisplayName("开局：题池为空（无已标定可用题）按 400 明确拒绝")
    void startRejectsEmptyPool() {
        when(irtParamService.usablePool()).thenReturn(List.of());

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.start(STUDENT, new AdaptiveQuizStartRequest()));

        assertEquals(ResponseCode.BAD_REQUEST.getCode(), error.getCode());
    }

    // ==================== θ 更新（m1） ====================

    @Test
    @DisplayName("答对：θ 上调、SE 收紧，并同源落 practice_attempt")
    void correctAnswerRaisesThetaAndPersistsAttempt() {
        stubAnswerContext(true);

        AdaptiveQuizAnswerResponse response = service.answer(STUDENT, SESSION_ID,
                answer(Q1, "A"));

        assertTrue(response.getTheta().doubleValue() > 0.2);
        assertTrue(response.getSe().doubleValue() < 1.0);
        assertTrue(response.getCorrect());
        assertEquals(1, response.getAnsweredCount());
        assertFalse(response.getTerminated());
        ArgumentCaptor<PracticeAttempt> captor = ArgumentCaptor.forClass(PracticeAttempt.class);
        verify(practiceAttemptMapper).insert(captor.capture());
        assertEquals(STUDENT, captor.getValue().getStudentId());
        assertEquals(Q1, captor.getValue().getQuestionId());
        assertTrue(captor.getValue().getIsCorrect());
    }

    @Test
    @DisplayName("答错：θ 下调（θ 更新不是单向写死）")
    void wrongAnswerLowersTheta() {
        stubAnswerContext(false);

        AdaptiveQuizAnswerResponse response = service.answer(STUDENT, SESSION_ID, answer(Q1, "B"));

        assertTrue(response.getTheta().doubleValue() < -0.1);
        assertFalse(response.getCorrect());
    }

    // ==================== 最大信息量选题（m2） ====================

    @Test
    @DisplayName("选题：排除已答题，选 Fisher 信息量最大者而非题池顺序第一条")
    void nextItemMaximizesInformationAndExcludesAnswered() {
        stubAnswerContext(true);

        AdaptiveQuizAnswerResponse response = service.answer(STUDENT, SESSION_ID, answer(Q1, "A"));

        assertNotNull(response.getNextQuestion());
        // 题池顺序为 [Q1(已答), Q3(a=0.5,b=-3 低信息), Q2(a=2.5,b≈θ 高信息)]
        assertEquals(Q2, response.getNextQuestion().getQuestionId());
        assertEquals(2, response.getNextQuestion().getOrder());
    }

    // ==================== 终止规则（m3） ====================

    @Test
    @DisplayName("终止：达最大题数 → MAX_ITEMS，不再返回下一题")
    void terminatesAtMaxItems() {
        stubAnswerContext(true, 1, new BigDecimal("0.0001"));

        AdaptiveQuizAnswerResponse response = service.answer(STUDENT, SESSION_ID, answer(Q1, "A"));

        assertTrue(response.getTerminated());
        assertEquals(CatQuizSession.TERMINATED_MAX_ITEMS, response.getTerminationReason());
        assertNull(response.getNextQuestion());
    }

    @Test
    @DisplayName("终止：SE < 阈值 → SE_CONVERGED")
    void terminatesWhenSeConverges() {
        stubAnswerContext(true, 20, new BigDecimal("2.0"));

        AdaptiveQuizAnswerResponse response = service.answer(STUDENT, SESSION_ID, answer(Q1, "A"));

        assertTrue(response.getTerminated());
        assertEquals(CatQuizSession.TERMINATED_SE_CONVERGED, response.getTerminationReason());
        assertNull(response.getNextQuestion());
    }

    @Test
    @DisplayName("终止：无未作答题可选 → POOL_EXHAUSTED")
    void terminatesWhenPoolExhausted() {
        CatQuizSession session = inProgressSession();
        when(catQuizSessionMapper.selectById(SESSION_ID)).thenReturn(session);
        stubGrading(true);
        when(irtParamService.findParam(Q1)).thenReturn(param(Q1, 1.5, 0.0, 0.25));
        // 题池只剩刚作答的那道题
        when(irtParamService.usablePool()).thenReturn(List.of(param(Q1, 1.5, 0.0, 0.25)));
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(question(Q1, QuestionType.SINGLE)));

        AdaptiveQuizAnswerResponse response = service.answer(STUDENT, SESSION_ID, answer(Q1, "A"));

        assertTrue(response.getTerminated());
        assertEquals(CatQuizSession.TERMINATED_POOL_EXHAUSTED, response.getTerminationReason());
        assertNull(response.getNextQuestion());
    }

    // ==================== 归属隔离 ====================

    @Test
    @DisplayName("越权：他人会话作答/查询一律 403，且不落任何作答明细")
    void otherStudentCannotAccessSession() {
        CatQuizSession session = inProgressSession();
        session.setStudentId(OTHER_STUDENT);
        when(catQuizSessionMapper.selectById(SESSION_ID)).thenReturn(session);

        BusinessException answerError = assertThrows(BusinessException.class,
                () -> service.answer(STUDENT, SESSION_ID, answer(Q1, "A")));
        assertEquals(ResponseCode.FORBIDDEN.getCode(), answerError.getCode());
        BusinessException reportError = assertThrows(BusinessException.class,
                () -> service.report(STUDENT, SESSION_ID));
        assertEquals(ResponseCode.FORBIDDEN.getCode(), reportError.getCode());
        verifyNoInteractions(practiceAttemptMapper);
    }

    @Test
    @DisplayName("已终止会话再作答按 400 拒绝")
    void answeredCompletedSessionRejected() {
        CatQuizSession session = inProgressSession();
        session.setStatus(CatQuizSession.STATUS_COMPLETED);
        when(catQuizSessionMapper.selectById(SESSION_ID)).thenReturn(session);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.answer(STUDENT, SESSION_ID, answer(Q1, "A")));

        assertEquals(ResponseCode.BAD_REQUEST.getCode(), error.getCode());
    }

    // ==================== 能力报告 ====================

    @Test
    @DisplayName("报告：θ/SE/正确率/终止原因/逐题轨迹（含题目内容与作答后 θ）")
    void reportReturnsTrajectory() throws Exception {
        CatQuizSession session = inProgressSession();
        List<IrtCatService.TrailEntry> trail = List.of(
                new IrtCatService.TrailEntry(Q1, "A", true, 1.5, 0.0, 0.25,
                        new BigDecimal("0.7"), new BigDecimal("0.85")),
                new IrtCatService.TrailEntry(Q2, "B", false, 2.0, 0.5, 0.25,
                        new BigDecimal("0.3"), new BigDecimal("0.62")));
        session.setItemTrail(objectMapper.writeValueAsString(trail));
        session.setAnsweredCount(2);
        session.setCorrectCount(1);
        session.setTheta(new BigDecimal("0.3"));
        session.setSe(new BigDecimal("0.62"));
        session.setStatus(CatQuizSession.STATUS_COMPLETED);
        session.setTerminationReason(CatQuizSession.TERMINATED_SE_CONVERGED);
        when(catQuizSessionMapper.selectById(SESSION_ID)).thenReturn(session);
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(
                question(Q1, QuestionType.SINGLE), question(Q2, QuestionType.SINGLE)));

        AdaptiveQuizReportResponse report = service.report(STUDENT, SESSION_ID);

        assertEquals(SESSION_ID, report.getSessionId());
        assertEquals(2, report.getAnsweredCount());
        assertEquals(1, report.getCorrectCount());
        assertEquals(50.0, report.getAccuracy().doubleValue(), 1e-9);
        assertEquals(CatQuizSession.TERMINATED_SE_CONVERGED, report.getTerminationReason());
        assertEquals(2, report.getItems().size());
        assertEquals(1, report.getItems().get(0).getOrder());
        assertEquals(Q1, report.getItems().get(0).getQuestionId());
        assertTrue(report.getItems().get(0).getCorrect());
        assertEquals("题干-" + Q1, report.getItems().get(0).getQuestionContent());
        assertEquals(0.7, report.getItems().get(0).getThetaAfter().doubleValue(), 1e-9);
        assertEquals(Q2, report.getItems().get(1).getQuestionId());
        assertFalse(report.getItems().get(1).getCorrect());
    }

    @Test
    @DisplayName("报告：会话不存在按 404 拒绝")
    void reportMissingSession() {
        when(catQuizSessionMapper.selectById(SESSION_ID)).thenReturn(null);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.report(STUDENT, SESSION_ID));

        assertEquals(ResponseCode.NOT_FOUND.getCode(), error.getCode());
    }

    // ==================== 夹具 ====================

    private void stubAnswerContext(boolean correct) {
        stubAnswerContext(correct, 10, new BigDecimal("0.0001"));
    }

    private void stubAnswerContext(boolean correct, int maxItems, BigDecimal seThreshold) {
        CatQuizSession session = inProgressSession();
        session.setMaxItems(maxItems);
        session.setSeThreshold(seThreshold);
        when(catQuizSessionMapper.selectById(SESSION_ID)).thenReturn(session);
        stubGrading(correct);
        when(irtParamService.findParam(Q1)).thenReturn(param(Q1, 1.5, 0.0, 0.25));
        // 题池顺序：[已答 Q1, 低信息 Q3, 高信息 Q2]
        // 终止分支在选题之前返回，以下两条桩在终止用例中不被消费 → lenient 避免严格桩误报
        lenient().when(irtParamService.usablePool()).thenReturn(List.of(
                param(Q1, 1.5, 0.0, 0.25),
                param(Q3, 0.5, -3.0, 0.0),
                param(Q2, 2.5, 0.7, 0.25)));
        lenient().when(questionMapper.selectBatchIds(any())).thenReturn(List.of(
                question(Q1, QuestionType.SINGLE),
                question(Q2, QuestionType.SINGLE),
                question(Q3, QuestionType.SINGLE)));
    }

    private void stubGrading(boolean correct) {
        when(questionMapper.selectById(Q1)).thenReturn(question(Q1, QuestionType.SINGLE));
        when(strategyRegistry.dispatch(any(GradingQuestion.class))).thenReturn(strategy);
        when(strategy.grade(any(GradingQuestion.class), any(), any()))
                .thenReturn(new GradeResult(BigDecimal.valueOf(correct ? 5 : 0), correct, "夹具判分"));
    }

    private CatQuizSession inProgressSession() {
        CatQuizSession session = new CatQuizSession();
        session.setId(SESSION_ID);
        session.setStudentId(STUDENT);
        session.setStatus(CatQuizSession.STATUS_IN_PROGRESS);
        session.setTheta(BigDecimal.ZERO);
        session.setSe(BigDecimal.ONE);
        session.setMaxItems(10);
        session.setSeThreshold(new BigDecimal("0.30"));
        session.setAnsweredCount(0);
        session.setCorrectCount(0);
        session.setCurrentQuestionId(Q1);
        session.setAnsweredQuestionIds("[]");
        session.setItemTrail("[]");
        return session;
    }

    private AdaptiveQuizAnswerRequest answer(long questionId, String value) {
        AdaptiveQuizAnswerRequest request = new AdaptiveQuizAnswerRequest();
        request.setQuestionId(questionId);
        request.setAnswer(value);
        return request;
    }

    private QuestionIrtParam param(long questionId, double a, double b, double c) {
        QuestionIrtParam param = new QuestionIrtParam();
        param.setQuestionId(questionId);
        param.setDiscrimination(BigDecimal.valueOf(a));
        param.setDifficulty(BigDecimal.valueOf(b));
        param.setGuessing(BigDecimal.valueOf(c));
        param.setSampleSize(30);
        param.setPoolAvailable(true);
        return param;
    }

    private Question question(long id, QuestionType type) {
        Question question = new Question();
        question.setId(id);
        question.setType(type.getCode());
        question.setContent("题干-" + id);
        question.setImageUrl(null);
        question.setChoices("[\"A\",\"B\"]");
        question.setCorrectAnswer("A");
        question.setAnalysis("解析-" + id);
        question.setScore(BigDecimal.valueOf(5));
        return question;
    }
}

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
import com.exam.score.dto.PracticePoolItem;
import com.exam.score.dto.PracticeQuizAnswer;
import com.exam.score.dto.PracticeQuizGenerateRequest;
import com.exam.score.dto.PracticeQuizResponse;
import com.exam.score.dto.PracticeQuizSubmitRequest;
import com.exam.score.dto.PracticeQuizSubmitResponse;
import com.exam.score.dto.PracticeSubmitRequest;
import com.exam.score.dto.PracticeSubmitResponse;
import com.exam.score.entity.PracticeAttempt;
import com.exam.score.mapper.PracticeAttemptMapper;
import com.exam.score.service.StudentPracticeService.PracticeProgress;
import com.exam.score.support.WrongQuestionJudgementCache;
import com.exam.submission.entity.ExamSubmission;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 错题强化自测组卷与批量判分单测（spec add-student-wrong-question-mastery，Mockito 隔离、无 Spring 上下文）：
 *
 * <ul>
 *   <li>自适应组卷：RANDOM 随机截断、PRACTICING_FIRST 攻克中优先（池超限时选中集恰为攻克中子集）、
 *       攻克中不足以未消灭补齐、池小于题量全量返回、空池 0 题、quizId 为无状态 UUID；</li>
 *   <li>参数防御：题量越界、非法策略、quizId 缺失、作答列表为空 → 400；</li>
 *   <li>批量判分：同源复用既有客观判分策略、逐题落 practice_attempt（零 DDL）、
 *       连对递增晋级 MASTERED 计入 newlyMasteredCount、答错清零退回 UNTOUCHED、
 *       正确率 scoreRate、同题重复计数去重；</li>
 *   <li>归属门控：非本人错题整单 400 拒绝且零写入；原题软删走快照回退；</li>
 *   <li>掌握度三态：连对 0/1/2/3 → UNTOUCHED/PRACTICING/PRACTICING/MASTERED，
 *       覆盖重练池条目与单题提交反馈。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class StudentPracticeQuizServiceTest {

    private static final Long STUDENT_ID = 42L;
    private static final Long EXAM_1 = 1001L;
    private static final BigDecimal FULL = new BigDecimal("5.0");
    private static final BigDecimal WRONG_SCORE = new BigDecimal("2.0");
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

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
        // 纯 Mockito 环境无 MyBatis 启动流程：为 lambda 条件/投影初始化实体元数据
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, GradingSubmission.class);
        TableInfoHelper.initTableInfo(assistant, Exam.class);
        TableInfoHelper.initTableInfo(assistant, Question.class);
        TableInfoHelper.initTableInfo(assistant, PracticeAttempt.class);
        service = new StudentPracticeService(examMapper, gradingSubmissionMapper, questionMapper,
                practiceAttemptMapper, scoreExportService, strategyRegistry,
                new GradingConfig(BigDecimal.ONE), new ObjectMapper());
    }

    // ==================== 组卷：RANDOM ====================

    @Test
    @DisplayName("RANDOM：池超限时截断至 limit，quizId 为无状态 UUID，作答前不泄露答案与解析")
    void quizRandomTruncatesToLimitAndLeaksNothing() throws Exception {
        stubPool(7);
        PracticeQuizResponse quiz = service.generateQuiz(STUDENT_ID, request(3, "RANDOM"));

        assertEquals(3, quiz.getTotal(), "池 7 题抽 3 题应截断至 limit");
        assertNotNull(quiz.getItems());
        assertEquals(3, quiz.getItems().size());
        List<Long> ids = quiz.getItems().stream().map(PracticePoolItem::getQuestionId).toList();
        assertEquals(3, ids.stream().distinct().count(), "抽中题目不得重复");
        assertTrue(ids.stream().allMatch(id -> id >= 201 && id <= 207), "抽中题目必须来自本人错题池");
        assertNotNull(quiz.getQuizId());
        assertTrue(UUID_PATTERN.matcher(quiz.getQuizId()).matches(),
                "quizId 必须是无状态 UUID，实际: " + quiz.getQuizId());

        String json = new ObjectMapper().writeValueAsString(quiz);
        assertFalse(json.contains("correctAnswer"), "组卷响应不得泄露 correctAnswer 字段");
        assertFalse(json.contains("analysis"), "组卷响应不得泄露 analysis 字段");
        assertFalse(json.contains("标准答案"), "组卷响应不得泄露标准答案内容");
    }

    @Test
    @DisplayName("RANDOM 缺省题量：limit 缺省为 5")
    void quizLimitDefaultsToFive() {
        stubPool(7);
        PracticeQuizResponse quiz = service.generateQuiz(STUDENT_ID, request(null, "RANDOM"));
        assertEquals(5, quiz.getTotal(), "limit 缺省应取 5");
    }

    // ==================== 组卷：PRACTICING_FIRST ====================

    @Test
    @DisplayName("PRACTICING_FIRST：池超限时选中集恰为攻克中子集（策略真实生效）")
    void quizPracticingFirstPicksPracticingSubsetWhenPoolExceedsLimit() {
        // 池自然顺序：前 4 题连对 0（UNTOUCHED），后 3 题连对 1~2（PRACTICING）
        stubPoolMixed();
        PracticeQuizResponse quiz = service.generateQuiz(STUDENT_ID, request(3, "PRACTICING_FIRST"));

        assertEquals(3, quiz.getTotal());
        Set<Long> selected = quiz.getItems().stream()
                .map(PracticePoolItem::getQuestionId).collect(Collectors.toSet());
        Set<Long> practicing = Set.of(205L, 206L, 207L);
        assertEquals(practicing, selected,
                "攻克中优先策略下，池超限时必须恰好选中全部攻克中题目（205/206/207）");
        quiz.getItems().forEach(item ->
                assertTrue(item.getMasteryStatus() >= 1, "选中条目应带攻克中及以上三态标识"));
    }

    @Test
    @DisplayName("PRACTICING_FIRST：攻克中不足 limit 时以未消灭（UNTOUCHED）补齐")
    void quizPracticingFirstFillsWithUntouchedWhenBelowLimit() {
        // 池：1 题连对 2（PRACTICING）+ 4 题连对 0（UNTOUCHED）
        stubPoolMixed();
        PracticeQuizResponse quiz = service.generateQuiz(STUDENT_ID, request(3, "PRACTICING_FIRST"));

        assertEquals(3, quiz.getTotal());
        Set<Long> selected = quiz.getItems().stream()
                .map(PracticePoolItem::getQuestionId).collect(Collectors.toSet());
        assertTrue(selected.contains(207L), "唯一攻克中题目（207，连对 2）必须入选");
        assertEquals(3, selected.size(), "攻克中不足时补齐至 limit");
        assertFalse(selected.contains(201L) && selected.contains(202L) && selected.contains(203L)
                        && selected.contains(204L),
                "补齐后总量为 3，不可能包含全部 4 道 UNTOUCHED");
    }

    // ==================== 组卷：边界与参数防御 ====================

    @Test
    @DisplayName("池小于题量：全量返回实际错题；空池返回 0 题空卷")
    void quizPoolSmallerThanLimitReturnsAllAndEmptyPoolReturnsZero() {
        stubPool(2);
        PracticeQuizResponse quiz = service.generateQuiz(STUDENT_ID, request(5, "RANDOM"));
        assertEquals(2, quiz.getTotal(), "池不足 limit 时按实际数量返回");

        // 空池：该生没有已发布考试 → 无错题
        when(gradingSubmissionMapper.selectList(any())).thenReturn(List.of());
        PracticeQuizResponse empty = service.generateQuiz(STUDENT_ID, request(5, "RANDOM"));
        assertEquals(0, empty.getTotal(), "空池应返回 0 题");
        assertNotNull(empty.getItems());
        assertTrue(empty.getItems().isEmpty(), "空池题目列表应为空");
        assertNotNull(empty.getQuizId());
        assertTrue(UUID_PATTERN.matcher(empty.getQuizId()).matches(), "空卷也应有无状态 UUID");
    }

    @Test
    @DisplayName("题量越界（0 与 21）→ 400 参数校验失败")
    void quizLimitOutOfRangeRejected() {
        BusinessException exLow = assertThrows(BusinessException.class,
                () -> service.generateQuiz(STUDENT_ID, request(0, "RANDOM")));
        assertEquals(ResponseCode.BAD_REQUEST.getCode(), exLow.getCode());

        BusinessException exHigh = assertThrows(BusinessException.class,
                () -> service.generateQuiz(STUDENT_ID, request(21, "RANDOM")));
        assertEquals(ResponseCode.BAD_REQUEST.getCode(), exHigh.getCode());
    }

    @Test
    @DisplayName("非法抽题策略 → 400 参数校验失败")
    void quizInvalidStrategyRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.generateQuiz(STUDENT_ID, request(5, "LATEST")));
        assertEquals(ResponseCode.BAD_REQUEST.getCode(), ex.getCode());
    }

    // ==================== 批量交卷：判分、落库与掌握度流转 ====================

    @Test
    @DisplayName("批量交卷全对：连对 2→3 晋级 MASTERED 计入 newlyMasteredCount，scoreRate=100，逐题落 practice_attempt")
    void quizSubmitAllCorrectMastersAndCountsNewly() {
        // q1、q2 均为本人错题（错题判定 2.0 < 5.0），自测前连对各 2
        stubPublishedExams(EXAM_1);
        when(scoreExportService.wrongQuestionScores(any(), any())).thenReturn(wrongScores(201L, 202L));
        stubBankQuestions(201L, 202L);
        stubGrading();
        stubAttemptHistory(
                Map.of(201L, List.of(true, true), 202L, List.of(true, true)),
                Map.of(201L, List.of(true, true, true), 202L, List.of(true, true, true)));

        PracticeQuizSubmitResponse res = service.submitQuiz(STUDENT_ID,
                submitRequest("quiz-1", answer(201L, "A"), answer(202L, "A")));

        assertEquals(2, res.getTotal());
        assertEquals(2, res.getCorrectCount());
        assertEquals(0, new BigDecimal("100.00").compareTo(res.getScoreRate()), "全对正确率应为 100");
        assertEquals(2, res.getNewlyMasteredCount(), "两题连对 2→3 均应计入新消灭");
        assertEquals(2, res.getResults().size());
        res.getResults().forEach(item -> {
            assertEquals(Boolean.TRUE, item.getCorrect());
            assertEquals(3, item.getConsecutiveCorrect());
            assertEquals(Boolean.TRUE, item.getMastered());
            assertEquals(2, item.getMasteryStatus(), "晋级题目三态应为 MASTERED");
            assertEquals("MASTERED", item.getMasteryStatusName());
            assertEquals("A", item.getCorrectAnswer(), "交卷后应返回标准答案");
        });
        verify(practiceAttemptMapper, org.mockito.Mockito.times(2)).insert(any(PracticeAttempt.class));
    }

    @Test
    @DisplayName("批量交卷混合：答错清零退回 UNTOUCHED，正确率 50%，新消灭仅计晋级题")
    void quizSubmitMixedBatchResetsAndCountsRate() {
        stubPublishedExams(EXAM_1);
        when(scoreExportService.wrongQuestionScores(any(), any())).thenReturn(wrongScores(201L, 202L));
        stubBankQuestions(201L, 202L);
        stubGrading();
        // q1 连对 2→3（晋级），q2 连对 2→0（答错清零）
        stubAttemptHistory(
                Map.of(201L, List.of(true, true), 202L, List.of(true, true)),
                Map.of(201L, List.of(true, true, true), 202L, List.of(false, true, true)));

        PracticeQuizSubmitResponse res = service.submitQuiz(STUDENT_ID,
                submitRequest("quiz-1", answer(201L, "A"), answer(202L, "B")));

        assertEquals(2, res.getTotal());
        assertEquals(1, res.getCorrectCount());
        assertEquals(0, new BigDecimal("50.00").compareTo(res.getScoreRate()));
        assertEquals(1, res.getNewlyMasteredCount(), "仅 q1 晋级计入新消灭");
        var q1 = res.getResults().stream()
                .filter(item -> item.getQuestionId() == 201L).findFirst().orElse(null);
        assertNotNull(q1);
        assertEquals(Boolean.TRUE, q1.getMastered());
        assertEquals(2, q1.getMasteryStatus());
        var q2 = res.getResults().stream()
                .filter(item -> item.getQuestionId() == 202L).findFirst().orElse(null);
        assertNotNull(q2);
        assertEquals(Boolean.FALSE, q2.getCorrect());
        assertEquals(0, q2.getMasteryStatus(), "答错题三态应退回 UNTOUCHED");
    }

    @Test
    @DisplayName("批量交卷答错：连对清零、三态退回 UNTOUCHED、不计新消灭")
    void quizSubmitWrongAnswerResetsStreakAndThreeStates() {
        stubPublishedExams(EXAM_1);
        when(scoreExportService.wrongQuestionScores(any(), any())).thenReturn(wrongScores(201L));
        stubBankQuestions(201L);
        stubGrading();
        stubAttemptHistory(
                Map.of(201L, List.of(true, true)),
                Map.of(201L, List.of(false, true, true)));

        PracticeQuizSubmitResponse res = service.submitQuiz(STUDENT_ID,
                submitRequest("quiz-1", answer(201L, "B")));

        assertEquals(1, res.getTotal());
        assertEquals(0, res.getCorrectCount());
        assertEquals(0, res.getNewlyMasteredCount(), "答错不得计入新消灭");
        assertEquals(1, res.getResults().size());
        var item = res.getResults().get(0);
        assertEquals(Boolean.FALSE, item.getCorrect());
        assertEquals(0, item.getConsecutiveCorrect(), "答错连对必须清零");
        assertEquals(Boolean.FALSE, item.getMastered());
        assertEquals(0, item.getMasteryStatus(), "清零后三态应退回 UNTOUCHED");
        assertEquals("UNTOUCHED", item.getMasteryStatusName());
    }

    @Test
    @DisplayName("同题在同一份自测卷中重复作答：新消灭只计一次")
    void quizSubmitDuplicateQuestionCountsNewlyOnce() {
        stubPublishedExams(EXAM_1);
        when(scoreExportService.wrongQuestionScores(any(), any())).thenReturn(wrongScores(201L));
        stubBankQuestions(201L);
        stubGrading();
        stubAttemptHistory(
                Map.of(201L, List.of(true, true)),
                Map.of(201L, List.of(true, true, true)));

        PracticeQuizSubmitResponse res = service.submitQuiz(STUDENT_ID,
                submitRequest("quiz-1", answer(201L, "A"), answer(201L, "A")));

        assertEquals(2, res.getTotal(), "重复作答按作答列表长度计总题数");
        assertEquals(1, res.getNewlyMasteredCount(), "同题新消灭在一次交卷内只计一次");
    }

    // ==================== 批量交卷：门控与参数防御 ====================

    @Test
    @DisplayName("归属越权：作答含非本人错题 → 整单 400 拒绝且零写入")
    @org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
    void quizSubmitForeignQuestionRejectedAndNothingPersisted() {
        stubPublishedExams(EXAM_1);
        // 错题池仅含 201；999 非本人错题（门控不命中）
        when(scoreExportService.wrongQuestionScores(any(), any())).thenReturn(wrongScores(201L));
        // 999 在题库真实存在：若门控被抽除，判分与落库会真实发生（变异可见性）
        stubBankQuestions(201L, 999L);
        stubGrading();
        stubAttemptHistory(Map.of(), Map.of());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.submitQuiz(STUDENT_ID,
                        submitRequest("quiz-1", answer(201L, "A"), answer(999L, "A"))));

        assertEquals(ResponseCode.BAD_REQUEST.getCode(), ex.getCode());
        assertEquals("该题目不属于您的错题重练池", ex.getMessage());
        verify(practiceAttemptMapper, never()).insert(any(PracticeAttempt.class));
    }

    @Test
    @DisplayName("参数防御：quizId 缺失/空白 → 400；作答列表为空 → 400")
    void quizSubmitBlankQuizIdAndEmptyAnswersRejected() {
        BusinessException exQuiz = assertThrows(BusinessException.class,
                () -> service.submitQuiz(STUDENT_ID, submitRequest("  ", answer(201L, "A"))));
        assertEquals(ResponseCode.BAD_REQUEST.getCode(), exQuiz.getCode());

        BusinessException exEmpty = assertThrows(BusinessException.class,
                () -> service.submitQuiz(STUDENT_ID, submitRequest("quiz-1")));
        assertEquals(ResponseCode.BAD_REQUEST.getCode(), exEmpty.getCode());
    }

    @Test
    @DisplayName("原题已软删：自测判分回退到考试快照同源判分，链路不受题库变动影响")
    void quizSubmitDeletedQuestionFallsBackToSnapshot() {
        stubPublishedExams(EXAM_1);
        when(scoreExportService.wrongQuestionScores(any(), any())).thenReturn(wrongScores(201L));
        // 题库查无此题（软删）
        when(questionMapper.selectById(anyLong())).thenReturn(null);
        // 快照回退：本人已发布考试成绩单包含该题
        GradingQuestion snapshotQuestion = new GradingQuestion(1, 201L, QuestionType.SINGLE,
                "快照单选", null, List.of("A", "B"), "A", FULL);
        when(scoreExportService.getPersonalReportWithoutRank(any(), any())).thenReturn(
                new ScoreExportService.PersonalReport("stu", "学生", FULL, BigDecimal.ZERO, FULL,
                        1, false, List.of(snapshotQuestion),
                        Map.of(201L, new QuestionScoreResolverScore().toResolved()),
                        Map.of(201L, "B")));
        when(strategyRegistry.dispatch(any())).thenReturn(strategy);
        when(strategy.grade(any(), any(), any())).thenReturn(new GradeResult(FULL, true, "答对"));
        stubAttemptHistory(Map.of(201L, List.of(true, true)),
                Map.of(201L, List.of(true, true, true)));

        PracticeQuizSubmitResponse res = service.submitQuiz(STUDENT_ID,
                submitRequest("quiz-1", answer(201L, "A")));

        assertEquals(1, res.getCorrectCount());
        assertEquals(Boolean.TRUE, res.getResults().get(0).getMastered(), "快照回退链路照常晋级");
    }

    // ==================== 掌握度三态推导（重练池与单题反馈） ====================

    @Test
    @DisplayName("重练池条目携带三态：连对 0/1/2 → UNTOUCHED/PRACTICING/PRACTICING，已消灭不入池")
    void practicePoolItemsCarryMasteryThreeStates() {
        // 池：201 连对 0、202 连对 1、203 连对 2；204 连对 3（已消灭，不入池）
        stubPoolSource(question(201L, "A"), question(202L, "A"), question(203L, "A"), question(204L, "A"));
        stubAttemptHistory(
                Map.of(201L, List.of(false),
                        202L, List.of(true),
                        203L, List.of(true, true),
                        204L, List.of(true, true, true)),
                null);

        List<PracticePoolItem> pool = service.getPracticePool(STUDENT_ID);

        assertEquals(3, pool.size(), "已消灭（连对 3）不得入池");
        Map<Long, PracticePoolItem> byId = pool.stream()
                .collect(Collectors.toMap(PracticePoolItem::getQuestionId, item -> item));
        assertMastery(byId.get(201L), 0, 0, "UNTOUCHED");
        assertMastery(byId.get(202L), 1, 1, "PRACTICING");
        assertMastery(byId.get(203L), 2, 1, "PRACTICING");
    }

    @Test
    @DisplayName("单题重练反馈携带三态：连对 1 → PRACTICING，答错 → UNTOUCHED")
    void singlePracticeSubmitCarriesMasteryThreeStates() {
        // 连对 0 → 本次答对 → 连对 1（PRACTICING）
        stubPublishedExams(EXAM_1);
        when(scoreExportService.wrongQuestionScores(any(), any())).thenReturn(wrongScores(201L));
        stubBankQuestions(201L);
        stubGrading();
        stubAttemptHistory(Map.of(201L, List.of(false)), Map.of(201L, List.of(true, false)));

        PracticeSubmitResponse res = service.submitPractice(STUDENT_ID, singleRequest(201L, "A"));

        assertEquals(1, res.getConsecutiveCorrect());
        assertEquals(1, res.getMasteryStatus(), "连对 1 应为 PRACTICING");
        assertEquals("PRACTICING", res.getMasteryStatusName());

        // 连对 1 → 本次答错 → 清零 UNTOUCHED
        stubAttemptHistory(Map.of(201L, List.of(true)), Map.of(201L, List.of(false, true)));
        PracticeSubmitResponse reset = service.submitPractice(STUDENT_ID, singleRequest(201L, "B"));
        assertEquals(0, reset.getMasteryStatus(), "答错清零应退回 UNTOUCHED");
        assertEquals("UNTOUCHED", reset.getMasteryStatusName());
    }

    // ==================== 造数与断言工具 ====================

    private void assertMastery(PracticePoolItem item, int consecutive, int status, String name) {
        assertNotNull(item);
        assertEquals(consecutive, item.getConsecutiveCorrect());
        assertEquals(status, item.getMasteryStatus(), "三态值与连对次数不符");
        assertEquals(name, item.getMasteryStatusName(), "三态名称与连对次数不符");
    }

    /** 组一个 n 题全 UNTOUCHED（连对 0）的错题池。 */
    private void stubPool(int n) {
        GradingQuestion[] questions = new GradingQuestion[n];
        for (int i = 0; i < n; i++) {
            questions[i] = question(201L + i, "A");
        }
        stubPoolSource(questions);
        Map<Long, List<Boolean>> streaks = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            streaks.put(201L + i, List.of(false));
        }
        stubAttemptHistory(streaks, null);
    }

    /** 组一个混合池：201~204 连对 0（UNTOUCHED），205~207 连对 1/1/2（PRACTICING），自然顺序 UNTOUCHED 在前。 */
    private void stubPoolMixed() {
        GradingQuestion[] questions = new GradingQuestion[7];
        for (int i = 0; i < 7; i++) {
            questions[i] = question(201L + i, "A");
        }
        stubPoolSource(questions);
        Map<Long, List<Boolean>> streaks = new LinkedHashMap<>();
        streaks.put(201L, List.of(false));
        streaks.put(202L, List.of(false));
        streaks.put(203L, List.of(false));
        streaks.put(204L, List.of(false));
        streaks.put(205L, List.of(true));
        streaks.put(206L, List.of(true));
        streaks.put(207L, List.of(true, true));
        stubAttemptHistory(streaks, null);
    }

    /** 错题池数据源：单场已发布考试 + 全部题判分严格小于满分。 */
    private void stubPoolSource(GradingQuestion... questions) {
        stubPublishedExams(EXAM_1);
        when(scoreExportService.wrongQuestionView(any(), any())).thenReturn(
                new ScoreExportService.WrongQuestionView(List.of(questions), wrongScores(questions)));
    }

    private Map<Long, WrongQuestionJudgementCache.ScoredQuestion> wrongScores(GradingQuestion... questions) {
        Map<Long, WrongQuestionJudgementCache.ScoredQuestion> map = new LinkedHashMap<>();
        for (GradingQuestion q : questions) {
            map.put(q.questionId(), new WrongQuestionJudgementCache.ScoredQuestion(WRONG_SCORE, q.score(), q.type()));
        }
        return map;
    }

    private Map<Long, WrongQuestionJudgementCache.ScoredQuestion> wrongScores(Long... qids) {
        Map<Long, WrongQuestionJudgementCache.ScoredQuestion> map = new LinkedHashMap<>();
        for (Long qid : qids) {
            map.put(qid, new WrongQuestionJudgementCache.ScoredQuestion(WRONG_SCORE, FULL, QuestionType.SINGLE));
        }
        return map;
    }

    private void stubPublishedExams(Long... examIds) {
        when(gradingSubmissionMapper.selectList(any())).thenReturn(
                Arrays.stream(examIds).map(this::gradedSubmission).toList());
        List<Exam> exams = new ArrayList<>();
        LocalDateTime base = LocalDateTime.of(2026, 1, 1, 0, 0);
        for (int i = 0; i < examIds.length; i++) {
            Exam exam = publishedExam(examIds[i]);
            exam.setStartTime(base.plusHours(examIds.length - i));
            exams.add(exam);
        }
        when(examMapper.selectBatchIds(any())).thenReturn(exams);
    }

    /** 批量交卷判分桩：题库按 qid 取题，判分=作答与标准答案相等（同源策略口径的测试替身）。 */
    private void stubBankQuestions(Long... qids) {
        Map<Long, Question> bank = new LinkedHashMap<>();
        for (Long qid : qids) {
            Question q = new Question();
            q.setId(qid);
            q.setType(QuestionType.SINGLE.getCode());
            q.setContent("单选题" + qid);
            q.setCorrectAnswer("A");
            q.setScore(FULL);
            q.setAnalysis("解析" + qid);
            bank.put(qid, q);
        }
        when(questionMapper.selectById(anyLong())).thenAnswer(inv -> bank.get(inv.getArgument(0, Long.class)));
    }

    private void stubGrading() {
        when(strategyRegistry.dispatch(any())).thenReturn(strategy);
        when(strategy.grade(any(), any(), any())).thenAnswer(inv -> {
            GradingQuestion q = inv.getArgument(0);
            String ans = inv.getArgument(1);
            boolean correct = q.correctAnswer() != null && q.correctAnswer().equals(ans);
            return new GradeResult(FULL, correct, correct ? "答对" : "答错");
        });
    }

    /**
     * attempt 历史桩：calculateProgressMap（无 LIMIT）走 preStreaks 全量历史；
     * 连对回读（LIMIT 3）按 postWindow 逐题返回窗口（head-first，即最新一条在前）。
     */
    private void stubAttemptHistory(Map<Long, List<Boolean>> preStreaks, Map<Long, List<Boolean>> postWindow) {
        when(practiceAttemptMapper.selectList(any())).thenAnswer(inv -> {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Wrapper<PracticeAttempt> wrapper = (Wrapper) inv.getArgument(0);
            if (wrapper == null) {
                // Mockito 重新打桩时的探针调用传入 null 匹配器占位，直接返回空集
                return java.util.Collections.emptyList();
            }
            String sql = wrapper.getSqlSegment();
            if (postWindow != null && sql != null && sql.contains("LIMIT 3")) {
                Long qid = extractQuestionIdParam(wrapper);
                List<PracticeAttempt> window = new ArrayList<>();
                long id = 1000;
                for (Boolean ok : postWindow.getOrDefault(qid, List.of())) {
                    window.add(attempt(id++, qid, ok));
                }
                return window;
            }
            List<PracticeAttempt> all = new ArrayList<>();
            long id = 1;
            for (Map.Entry<Long, List<Boolean>> e : preStreaks.entrySet()) {
                List<Boolean> oldestFirst = new ArrayList<>(e.getValue());
                java.util.Collections.reverse(oldestFirst);
                for (Boolean ok : oldestFirst) {
                    all.add(attempt(id++, e.getKey(), ok));
                }
            }
            all.sort(Comparator.comparingLong(PracticeAttempt::getId).reversed());
            return all;
        });
    }

    private Long extractQuestionIdParam(Wrapper<PracticeAttempt> wrapper) {
        @SuppressWarnings({"unchecked", "rawtypes"})
        com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?> abstractWrapper =
                (com.baomidou.mybatisplus.core.conditions.AbstractWrapper) wrapper;
        for (Object v : abstractWrapper.getParamNameValuePairs().values()) {
            if (v instanceof Long val && !val.equals(STUDENT_ID)) {
                return val;
            }
        }
        return null;
    }

    private PracticeQuizGenerateRequest request(Integer limit, String strategy) {
        PracticeQuizGenerateRequest request = new PracticeQuizGenerateRequest();
        request.setLimit(limit);
        request.setStrategy(strategy);
        return request;
    }

    private PracticeQuizSubmitRequest submitRequest(String quizId, PracticeQuizAnswer... answers) {
        PracticeQuizSubmitRequest request = new PracticeQuizSubmitRequest();
        request.setQuizId(quizId);
        request.setAnswers(new ArrayList<>(List.of(answers)));
        return request;
    }

    private PracticeQuizAnswer answer(Long questionId, String answer) {
        PracticeQuizAnswer item = new PracticeQuizAnswer();
        item.setQuestionId(questionId);
        item.setAnswer(answer);
        return item;
    }

    private PracticeSubmitRequest singleRequest(Long questionId, String answer) {
        PracticeSubmitRequest request = new PracticeSubmitRequest();
        request.setQuestionId(questionId);
        request.setAnswer(answer);
        return request;
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

    private PracticeAttempt attempt(long id, Long questionId, boolean correct) {
        PracticeAttempt attempt = new PracticeAttempt();
        attempt.setId(id);
        attempt.setStudentId(STUDENT_ID);
        attempt.setQuestionId(questionId);
        attempt.setIsCorrect(correct);
        return attempt;
    }

    /** ResolvedQuestionScore 测试替身（避免直接依赖 support 包内部构造）。 */
    private static final class QuestionScoreResolverScore {
        com.exam.score.support.QuestionScoreResolver.ResolvedQuestionScore toResolved() {
            return new com.exam.score.support.QuestionScoreResolver.ResolvedQuestionScore(
                    WRONG_SCORE, FULL, false, true, null, "判定明细");
        }
    }
}

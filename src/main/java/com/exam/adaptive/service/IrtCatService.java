package com.exam.adaptive.service;

import com.exam.adaptive.dto.AdaptiveQuizAnswerRequest;
import com.exam.adaptive.dto.AdaptiveQuizAnswerResponse;
import com.exam.adaptive.dto.AdaptiveQuizQuestionItem;
import com.exam.adaptive.dto.AdaptiveQuizReportItem;
import com.exam.adaptive.dto.AdaptiveQuizReportResponse;
import com.exam.adaptive.dto.AdaptiveQuizStartRequest;
import com.exam.adaptive.dto.AdaptiveQuizStartResponse;
import com.exam.adaptive.entity.CatQuizSession;
import com.exam.adaptive.entity.QuestionIrtParam;
import com.exam.adaptive.mapper.CatQuizSessionMapper;
import com.exam.adaptive.support.IrtEstimator;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.grading.model.GradeResult;
import com.exam.grading.model.GradingConfig;
import com.exam.grading.model.GradingQuestion;
import com.exam.grading.strategy.GradingStrategyRegistry;
import com.exam.question.entity.Question;
import com.exam.question.entity.QuestionType;
import com.exam.question.mapper.QuestionMapper;
import com.exam.score.entity.PracticeAttempt;
import com.exam.score.mapper.PracticeAttemptMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CAT 计算机化自适应测验引擎（spec add-irt-adaptive-testing，在线侧）：
 *
 * <ul>
 *   <li><b>开局</b>：取题池中 b 最接近 0 的中等难度题（未作答，初值 θ=0）；</li>
 *   <li><b>θ 更新</b>：每次作答后经 {@link IrtEstimator#eap} 即时重估（EAP 为主，
 *       Newton-Raphson 兜底），SE 由后验标准差给出；</li>
 *   <li><b>选题</b>：未作答题中 Fisher 信息量 {@code I(θ)} 最大者（θ 上调 → 选中更高 b）；</li>
 *   <li><b>终止</b>：达最大题数（缺省 10，可配 1~20）／{@code SE(θ) < 0.30}（可配）／题池耗尽；</li>
 *   <li><b>判分同源</b>：逐题复用 {@link GradingStrategyRegistry} 判分并落 {@code practice_attempt}，
 *       与错题本/掌握度三态天然联动，零口径分叉；</li>
 *   <li><b>正确性落 DB</b>：会话（θ 轨迹、已答题集、终止原因）写 {@code cat_quiz_sessions}，
 *       零新 Redis 键族；归属人隔离在每个入口统一裁决。</li>
 * </ul>
 */
@Slf4j
@Service
public class IrtCatService {

    /** 缺省最大题数 */
    public static final int DEFAULT_MAX_ITEMS = 10;
    /** 最大题数下界 */
    public static final int MIN_MAX_ITEMS = 1;
    /** 最大题数上界 */
    public static final int MAX_MAX_ITEMS = 20;
    /** 缺省终止阈值 SE */
    public static final double DEFAULT_SE_THRESHOLD = 0.30;
    /** SE 阈值可配下界（再小无测量学意义） */
    public static final double MIN_SE_THRESHOLD = 0.05;
    /** SE 阈值可配上界（防"开局即终止"的荒谬配置） */
    public static final double MAX_SE_THRESHOLD = 2.0;

    private static final String EMPTY_JSON_ARRAY = "[]";
    /** b 的初值（中等难度，CAT 开局据此选题） */
    private static final double OPENING_B_TARGET = 0.0;

    private final IrtParamService irtParamService;
    private final CatQuizSessionMapper catQuizSessionMapper;
    private final QuestionMapper questionMapper;
    private final PracticeAttemptMapper practiceAttemptMapper;
    private final GradingStrategyRegistry strategyRegistry;
    private final GradingConfig gradingConfig;
    private final ObjectMapper objectMapper;

    public IrtCatService(IrtParamService irtParamService,
                         CatQuizSessionMapper catQuizSessionMapper,
                         QuestionMapper questionMapper,
                         PracticeAttemptMapper practiceAttemptMapper,
                         GradingStrategyRegistry strategyRegistry,
                         GradingConfig gradingConfig,
                         ObjectMapper objectMapper) {
        this.irtParamService = irtParamService;
        this.catQuizSessionMapper = catQuizSessionMapper;
        this.questionMapper = questionMapper;
        this.practiceAttemptMapper = practiceAttemptMapper;
        this.strategyRegistry = strategyRegistry;
        this.gradingConfig = gradingConfig;
        this.objectMapper = objectMapper;
    }

    /** 题池条目：题目参数 + 题库实体 + 解析后的题型。 */
    private record PoolItem(QuestionIrtParam param, Question question, QuestionType type) {
    }

    /** 逐题作答轨迹（JSON 持久化在 cat_quiz_sessions.item_trail）。 */
    public record TrailEntry(Long questionId, String answer, boolean correct,
                             double a, double b, double c,
                             BigDecimal thetaAfter, BigDecimal seAfter) {
    }

    // ==================== 开局 ====================

    /**
     * 开局：惰性标定本人已判分考试覆盖的题池 → 建会话 → 返回 b 最接近 0 的首题。
     * 作答前严格不泄露正确答案与解析。
     */
    public AdaptiveQuizStartResponse start(Long studentId, AdaptiveQuizStartRequest request) {
        if (studentId == null) {
            throw new BusinessException(ResponseCode.TOKEN_INVALID);
        }
        int maxItems = normalizeMaxItems(request == null ? null : request.getMaxItems());
        BigDecimal seThreshold = normalizeSeThreshold(request == null ? null : request.getSeThreshold());

        irtParamService.calibrateStudentPool(studentId);
        List<PoolItem> pool = loadPool();
        if (pool.isEmpty()) {
            throw new BusinessException(ResponseCode.BAD_REQUEST,
                    "暂无可用自适应题池（需至少一场已判分且样本充足的考试）");
        }

        PoolItem opening = selectOpeningItem(pool);
        LocalDateTime now = LocalDateTime.now();
        CatQuizSession session = new CatQuizSession();
        session.setStudentId(studentId);
        session.setStatus(CatQuizSession.STATUS_IN_PROGRESS);
        session.setTheta(scaleTheta(IrtEstimator.PRIOR_MEAN));
        session.setSe(scaleTheta(IrtEstimator.PRIOR_SD));
        session.setMaxItems(maxItems);
        session.setSeThreshold(seThreshold);
        session.setAnsweredCount(0);
        session.setCorrectCount(0);
        session.setCurrentQuestionId(opening.param().getQuestionId());
        session.setAnsweredQuestionIds(EMPTY_JSON_ARRAY);
        session.setItemTrail(EMPTY_JSON_ARRAY);
        session.setCreatedTime(now);
        session.setUpdatedTime(now);
        catQuizSessionMapper.insert(session);
        log.info("CAT 开局: student={} session={} 题池={} 首题={} maxItems={}",
                studentId, session.getId(), pool.size(), session.getCurrentQuestionId(), maxItems);

        AdaptiveQuizStartResponse response = new AdaptiveQuizStartResponse();
        response.setSessionId(session.getId());
        response.setStatus(session.getStatus());
        response.setTheta(session.getTheta());
        response.setSe(session.getSe());
        response.setAnsweredCount(0);
        response.setMaxItems(maxItems);
        response.setSeThreshold(seThreshold);
        response.setCurrentQuestion(toQuestionItem(1, opening));
        return response;
    }

    // ==================== 作答 ====================

    /**
     * 提交单题作答：归属校验 → 同源判分 → 落 practice_attempt → 即时重估 θ/SE →
     * 终止判定或按最大信息量选出下一题。
     */
    public AdaptiveQuizAnswerResponse answer(Long studentId, Long sessionId,
                                             AdaptiveQuizAnswerRequest request) {
        CatQuizSession session = loadOwnedSession(studentId, sessionId);
        if (CatQuizSession.STATUS_COMPLETED.equals(session.getStatus())) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "本次自适应测验已结束，请查看能力报告");
        }
        if (request == null || request.getQuestionId() == null) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "题目 ID 不能为空");
        }
        if (!request.getQuestionId().equals(session.getCurrentQuestionId())) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "请按当前题目作答");
        }

        Question question = questionMapper.selectById(request.getQuestionId());
        QuestionType type = question == null ? null : QuestionType.of(question.getType());
        if (question == null || type == null || type == QuestionType.SHORT_ANSWER) {
            throw new BusinessException(ResponseCode.QUESTION_NOT_FOUND, "题目不存在或已从题池移除");
        }
        QuestionIrtParam param = irtParamService.findParam(question.getId());
        if (param == null) {
            throw new BusinessException(ResponseCode.QUESTION_NOT_FOUND, "题目 IRT 参数缺失");
        }

        GradingQuestion gradingQuestion = toGradingQuestion(question, type,
                session.getAnsweredCount() == null ? 1 : session.getAnsweredCount() + 1);
        GradeResult gradeResult = strategyRegistry.dispatch(gradingQuestion)
                .grade(gradingQuestion, request.getAnswer(), gradingConfig);
        boolean correct = gradeResult.correct();

        // 判分同源落 practice_attempt（与错题重练/掌握度三态共用一张明细表，零口径分叉）
        PracticeAttempt attempt = new PracticeAttempt();
        attempt.setStudentId(studentId);
        attempt.setQuestionId(question.getId());
        attempt.setIsCorrect(correct);
        attempt.setCreatedTime(LocalDateTime.now());
        practiceAttemptMapper.insert(attempt);

        List<TrailEntry> trail = readTrail(session);
        // θ 重估必须含本次作答（先并入再估计，否则首题永远按先验 θ=0 落轨迹）
        List<IrtEstimator.Administered> administered = buildAdministered(trail);
        administered.add(administeredOf(param, correct));
        IrtEstimator.Estimate estimate = updateTheta(administered);
        BigDecimal theta = scaleTheta(estimate.theta());
        BigDecimal se = scaleTheta(estimate.se());

        TrailEntry entry = new TrailEntry(question.getId(), request.getAnswer(), correct,
                param.getDiscrimination().doubleValue(),
                param.getDifficulty().doubleValue(),
                param.getGuessing().doubleValue(), theta, se);
        trail.add(entry);

        int answeredCount = (session.getAnsweredCount() == null ? 0 : session.getAnsweredCount()) + 1;
        int correctCount = (session.getCorrectCount() == null ? 0 : session.getCorrectCount())
                + (correct ? 1 : 0);
        session.setAnsweredCount(answeredCount);
        session.setCorrectCount(correctCount);
        session.setTheta(theta);
        session.setSe(se);
        session.setItemTrail(writeJson(trail));
        session.setAnsweredQuestionIds(writeJson(answeredIdsOf(trail)));

        String terminationReason = terminationReasonOf(session);
        AdaptiveQuizQuestionItem nextQuestion = null;
        if (terminationReason == null) {
            Set<Long> answered = answeredIdsOf(trail);
            PoolItem next = selectNextItem(loadPool(), answered, theta.doubleValue());
            if (next == null) {
                terminationReason = CatQuizSession.TERMINATED_POOL_EXHAUSTED;
            } else {
                session.setCurrentQuestionId(next.param().getQuestionId());
                nextQuestion = toQuestionItem(answeredCount + 1, next);
            }
        }
        if (terminationReason != null) {
            session.setStatus(CatQuizSession.STATUS_COMPLETED);
            session.setTerminationReason(terminationReason);
            session.setCurrentQuestionId(null);
        }
        session.setUpdatedTime(LocalDateTime.now());
        catQuizSessionMapper.updateById(session);

        AdaptiveQuizAnswerResponse response = new AdaptiveQuizAnswerResponse();
        response.setSessionId(session.getId());
        response.setQuestionId(question.getId());
        response.setCorrect(correct);
        response.setCorrectAnswer(question.getCorrectAnswer() == null ? "" : question.getCorrectAnswer());
        response.setAnalysis(question.getAnalysis() == null ? "" : question.getAnalysis());
        response.setTheta(theta);
        response.setSe(se);
        response.setAnsweredCount(answeredCount);
        response.setTerminated(terminationReason != null);
        response.setTerminationReason(terminationReason);
        response.setNextQuestion(nextQuestion);
        return response;
    }

    // ==================== 能力报告 ====================

    /** 能力报告：θ、SE、正确率、终止原因与逐题轨迹；仅会话归属人可查。 */
    public AdaptiveQuizReportResponse report(Long studentId, Long sessionId) {
        CatQuizSession session = loadOwnedSession(studentId, sessionId);
        List<TrailEntry> trail = readTrail(session);
        Map<Long, Question> questions = loadQuestions(trail);

        List<AdaptiveQuizReportItem> items = new ArrayList<>(trail.size());
        int order = 1;
        for (TrailEntry entry : trail) {
            AdaptiveQuizReportItem item = new AdaptiveQuizReportItem();
            item.setOrder(order++);
            item.setQuestionId(entry.questionId());
            item.setAnswer(entry.answer());
            item.setCorrect(entry.correct());
            item.setThetaAfter(scaleTheta(entry.thetaAfter() == null ? 0 : entry.thetaAfter().doubleValue()));
            item.setSeAfter(scaleTheta(entry.seAfter() == null ? 0 : entry.seAfter().doubleValue()));
            Question question = questions.get(entry.questionId());
            if (question != null) {
                QuestionType type = QuestionType.of(question.getType());
                item.setQuestionType(type == null ? "未知" : type.getLabel());
                item.setQuestionContent(question.getContent());
                item.setCorrectAnswer(question.getCorrectAnswer() == null ? "" : question.getCorrectAnswer());
            }
            items.add(item);
        }

        int answeredCount = session.getAnsweredCount() == null ? 0 : session.getAnsweredCount();
        int correctCount = session.getCorrectCount() == null ? 0 : session.getCorrectCount();

        AdaptiveQuizReportResponse response = new AdaptiveQuizReportResponse();
        response.setSessionId(session.getId());
        response.setStudentId(session.getStudentId());
        response.setStatus(session.getStatus());
        response.setTheta(session.getTheta());
        response.setSe(session.getSe());
        response.setAnsweredCount(answeredCount);
        response.setMaxItems(session.getMaxItems());
        response.setCorrectCount(correctCount);
        response.setAccuracy(answeredCount == 0 ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.valueOf(correctCount * 100L)
                .divide(BigDecimal.valueOf(answeredCount), 2, RoundingMode.HALF_UP));
        response.setTerminationReason(session.getTerminationReason());
        response.setItems(items);
        return response;
    }

    // ==================== 变异点 m1：θ 更新 ====================

    /**
     * θ 更新（变异点 m1）：把既有作答轨迹交给 EAP 估计器重估。
     * 抽除本调用即"作答后 θ 恒为初值"，选题序列与能力报告断言随之击红。
     */
    private IrtEstimator.Estimate updateTheta(List<IrtEstimator.Administered> administered) {
        return IrtEstimator.eap(administered);
    }

    // ==================== 变异点 m2：最大信息量选题 ====================

    /**
     * 下一题选题（变异点 m2）：未作答题中 Fisher 信息量 {@code I(θ)} 最大者；
     * 信息量并列时取 |b - θ| 更小者，再以 questionId 兜底保证确定性。
     * 抽除本方法的信息量比较（退化为题池顺序取题）→ 选题断言击红。
     */
    private PoolItem selectNextItem(List<PoolItem> pool, Set<Long> answered, double theta) {
        PoolItem best = null;
        double bestInfo = Double.NEGATIVE_INFINITY;
        for (PoolItem candidate : pool) {
            if (answered.contains(candidate.param().getQuestionId())) {
                continue;
            }
            IrtEstimator.Administered item = administeredOf(candidate.param(), true);
            double info = IrtEstimator.information(item, theta);
            if (best == null
                    || info > bestInfo + 1e-12
                    || (Math.abs(info - bestInfo) <= 1e-12
                    && Math.abs(candidate.param().getDifficulty().doubleValue() - theta)
                    < Math.abs(best.param().getDifficulty().doubleValue() - theta))) {
                best = candidate;
                bestInfo = info;
            }
        }
        return best;
    }

    /** 开局选题：b 最接近 0 的中等难度题（并列取 questionId 更小者保证确定性）。 */
    private PoolItem selectOpeningItem(List<PoolItem> pool) {
        PoolItem best = null;
        for (PoolItem candidate : pool) {
            if (best == null) {
                best = candidate;
                continue;
            }
            double distance = Math.abs(candidate.param().getDifficulty().doubleValue() - OPENING_B_TARGET);
            double bestDistance = Math.abs(best.param().getDifficulty().doubleValue() - OPENING_B_TARGET);
            if (distance < bestDistance - 1e-12
                    || (Math.abs(distance - bestDistance) <= 1e-12
                    && candidate.param().getQuestionId() < best.param().getQuestionId())) {
                best = candidate;
            }
        }
        return best;
    }

    // ==================== 变异点 m3：终止规则 ====================

    /**
     * 终止判定（变异点 m3）：达最大题数 → MAX_ITEMS；{@code SE < 阈值} → SE_CONVERGED；
     * 否则继续（null）。题池耗尽在选题后判定。
     * 抽除本方法的返回（恒不终止）→ 终止与报告断言击红。
     */
    private String terminationReasonOf(CatQuizSession session) {
        int answered = session.getAnsweredCount() == null ? 0 : session.getAnsweredCount();
        if (session.getMaxItems() != null && answered >= session.getMaxItems()) {
            return CatQuizSession.TERMINATED_MAX_ITEMS;
        }
        if (session.getSe() != null && session.getSeThreshold() != null
                && session.getSe().compareTo(session.getSeThreshold()) < 0) {
            return CatQuizSession.TERMINATED_SE_CONVERGED;
        }
        return null;
    }

    // ==================== 内部装配 ====================

    private CatQuizSession loadOwnedSession(Long studentId, Long sessionId) {
        if (studentId == null) {
            throw new BusinessException(ResponseCode.TOKEN_INVALID);
        }
        CatQuizSession session = sessionId == null ? null : catQuizSessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "自适应测验会话不存在");
        }
        if (!studentId.equals(session.getStudentId())) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "无权访问该自适应测验会话");
        }
        return session;
    }

    private List<PoolItem> loadPool() {
        List<QuestionIrtParam> params = irtParamService.usablePool();
        if (params == null || params.isEmpty()) {
            return List.of();
        }
        List<Long> ids = params.stream().map(QuestionIrtParam::getQuestionId).toList();
        List<Question> questions = questionMapper.selectBatchIds(ids);
        if (questions == null || questions.isEmpty()) {
            return List.of();
        }
        Map<Long, Question> byId = new LinkedHashMap<>();
        for (Question question : questions) {
            byId.put(question.getId(), question);
        }
        List<PoolItem> pool = new ArrayList<>();
        for (QuestionIrtParam param : params) {
            Question question = byId.get(param.getQuestionId());
            if (question == null) {
                continue;
            }
            QuestionType type = QuestionType.of(question.getType());
            if (type == null || type == QuestionType.SHORT_ANSWER) {
                continue;
            }
            pool.add(new PoolItem(param, question, type));
        }
        return pool;
    }

    private Map<Long, Question> loadQuestions(List<TrailEntry> trail) {
        Set<Long> ids = answeredIdsOf(trail);
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<Question> questions = questionMapper.selectBatchIds(ids);
        Map<Long, Question> byId = new LinkedHashMap<>();
        if (questions != null) {
            for (Question question : questions) {
                byId.put(question.getId(), question);
            }
        }
        return byId;
    }

    private List<IrtEstimator.Administered> buildAdministered(List<TrailEntry> trail) {
        List<IrtEstimator.Administered> items = new ArrayList<>(trail.size());
        for (TrailEntry entry : trail) {
            items.add(new IrtEstimator.Administered(entry.a(), entry.b(), entry.c(), entry.correct()));
        }
        return items;
    }

    private IrtEstimator.Administered administeredOf(QuestionIrtParam param, boolean correct) {
        return new IrtEstimator.Administered(
                param.getDiscrimination().doubleValue(),
                param.getDifficulty().doubleValue(),
                param.getGuessing().doubleValue(),
                correct);
    }

    private Set<Long> answeredIdsOf(List<TrailEntry> trail) {
        Set<Long> ids = new LinkedHashSet<>();
        for (TrailEntry entry : trail) {
            if (entry.questionId() != null) {
                ids.add(entry.questionId());
            }
        }
        return ids;
    }

    private AdaptiveQuizQuestionItem toQuestionItem(int order, PoolItem item) {
        AdaptiveQuizQuestionItem view = new AdaptiveQuizQuestionItem();
        view.setOrder(order);
        view.setQuestionId(item.question().getId());
        view.setQuestionType(item.type().getLabel());
        view.setQuestionContent(item.question().getContent());
        view.setImageUrl(item.question().getImageUrl());
        view.setChoices(parseChoices(item.question()));
        return view;
    }

    private GradingQuestion toGradingQuestion(Question question, QuestionType type, int number) {
        BigDecimal score = question.getScore() == null ? BigDecimal.ONE : question.getScore();
        return new GradingQuestion(number, question.getId(), type, question.getContent(),
                question.getImageUrl(), parseChoices(question), question.getCorrectAnswer(), score);
    }

    private List<String> parseChoices(Question question) {
        if (question.getChoices() == null || question.getChoices().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(question.getChoices(), new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.warn("解析题目选项失败: questionId={}", question.getId());
            return null;
        }
    }

    private List<TrailEntry> readTrail(CatQuizSession session) {
        if (session.getItemTrail() == null || session.getItemTrail().isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<TrailEntry> trail = objectMapper.readValue(session.getItemTrail(),
                    new TypeReference<List<TrailEntry>>() {
                    });
            return trail == null ? new ArrayList<>() : new ArrayList<>(trail);
        } catch (Exception e) {
            log.warn("解析 CAT 会话轨迹失败: session={}", session.getId());
            return new ArrayList<>();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BusinessException(ResponseCode.INTERNAL_ERROR, "自适应测验会话序列化失败");
        }
    }

    private int normalizeMaxItems(Integer maxItems) {
        if (maxItems == null) {
            return DEFAULT_MAX_ITEMS;
        }
        return Math.max(MIN_MAX_ITEMS, Math.min(MAX_MAX_ITEMS, maxItems));
    }

    private BigDecimal normalizeSeThreshold(Double seThreshold) {
        double value = seThreshold == null ? DEFAULT_SE_THRESHOLD
                : Math.max(MIN_SE_THRESHOLD, Math.min(MAX_SE_THRESHOLD, seThreshold));
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal scaleTheta(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }
}

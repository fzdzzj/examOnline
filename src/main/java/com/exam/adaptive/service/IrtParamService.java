package com.exam.adaptive.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.adaptive.entity.QuestionIrtParam;
import com.exam.adaptive.mapper.QuestionIrtParamMapper;
import com.exam.submission.entity.ExamSubmission;import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.question.entity.Question;
import com.exam.question.entity.QuestionType;
import com.exam.question.mapper.QuestionMapper;
import com.exam.score.entity.PracticeAttempt;
import com.exam.score.mapper.PracticeAttemptMapper;
import com.exam.score.service.ExamAnalysisReportService;
import com.exam.score.service.ScoreExportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * IRT 题目参数标定服务（spec add-irt-adaptive-testing，离线侧）：
 *
 * <ul>
 *   <li><b>CTT→3PL 经验转换</b>（唯一出处，禁止另造测量学公式）：
 *     <ul>
 *       <li>{@code a = 0.5 + 2D}，钳制 [0.5, 2.5]（D 为既有区分度，D=0.5 映射中性 a=1.5）；</li>
 *       <li>{@code b = ln(P / (1 - P))}，P 先中值修正钳制到 [0.05, 0.95] 防 logit 发散；</li>
 *       <li>{@code c} 按题型固定：单选 0.25、判断 0.50、其余（多选/填空）0；</li>
 *     </ul>
 *   </li>
 *   <li><b>数据源同源</b>：P/D 取自既有 CTT 聚合
 *       （{@link ExamAnalysisReportService#questionMetrics} → {@code ScoreExportService.aggregateQuestionStats}）；
 *       练习作答（{@code practice_attempt}）按题合并进样本量与正确率，使 CAT 自身的作答也反哺标定；</li>
 *   <li><b>样本不足不入池</b>：合并样本量 &lt; 4 或缺少区分度 D（实考不足 4 人）的题目
 *       {@code poolAvailable=false}，不进 CAT 题池；</li>
 *   <li><b>幂等 upsert</b>：按 {@code uk_irt_question}（question_id）写入；
 *       同一场考试在 {@link #REFRESH_TTL} 内不重复标定（避免每次开局都做一遍 CTT 全量聚合）。</li>
 * </ul>
 *
 * <p>本服务只写 {@code question_irt_params}，不改判分、掌握度与错题本口径；零新 Redis 键族。
 */
@Slf4j
@Service
public class IrtParamService {

    /** 入池最小样本量（对齐 C3 既有「至少 4 人作答」的区分度可算口径）。 */
    public static final int MIN_SAMPLE_SIZE = 4;
    /** a 的下界（区分度极低的题仍保留最低区分力） */
    public static final double A_MIN = 0.5;
    /** a 的上界（防极端区分度把题池挤出数值可用域） */
    public static final double A_MAX = 2.5;
    /** P 的中值修正下界（防 ln(0) 发散） */
    public static final double P_FLOOR = 0.05;
    /** P 的中值修正上界（防 ln(±∞) 发散） */
    public static final double P_CEIL = 0.95;
    /** b 的可用域（±3 logit 覆盖 P∈[0.05,0.95]，超出无测量学意义） */
    public static final double B_MIN = -3.0;
    public static final double B_MAX = 3.0;
    /** 同场次标定新鲜期：TTL 内复用既有参数，不重复聚合。 */
    public static final Duration REFRESH_TTL = Duration.ofHours(12);

    private final ExamAnalysisReportService examAnalysisReportService;
    private final GradingSubmissionMapper gradingSubmissionMapper;
    private final QuestionMapper questionMapper;
    private final PracticeAttemptMapper practiceAttemptMapper;
    private final QuestionIrtParamMapper questionIrtParamMapper;

    public IrtParamService(ExamAnalysisReportService examAnalysisReportService,
                           GradingSubmissionMapper gradingSubmissionMapper,
                           QuestionMapper questionMapper,
                           PracticeAttemptMapper practiceAttemptMapper,
                           QuestionIrtParamMapper questionIrtParamMapper) {
        this.examAnalysisReportService = examAnalysisReportService;
        this.gradingSubmissionMapper = gradingSubmissionMapper;
        this.questionMapper = questionMapper;
        this.practiceAttemptMapper = practiceAttemptMapper;
        this.questionIrtParamMapper = questionIrtParamMapper;
    }

    /** CTT 区分度 D → IRT 区分度参数 a（单调映射，钳制 [0.5, 2.5]）。 */
    public static BigDecimal discriminationToA(Double discrimination) {
        double d = discrimination == null ? 0.5 : discrimination;
        double raw = A_MIN + 2.0 * d;
        return BigDecimal.valueOf(clamp(raw, A_MIN, A_MAX)).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * CTT 难度系数 P → IRT 难度参数 b（logit 变换，中值修正防发散）：
     * P=0.5 → b=0（中等难度）；P 越大题越易、b 越小。P 先钳制到 [0.05, 0.95]。
     */
    public static BigDecimal difficultyToB(Double difficulty) {
        double p = difficulty == null ? 0.5 : clamp(difficulty, P_FLOOR, P_CEIL);
        double b = Math.log(p / (1.0 - p));
        return BigDecimal.valueOf(clamp(b, B_MIN, B_MAX)).setScale(4, RoundingMode.HALF_UP);
    }

    /** 猜测系数 c：单选 0.25、判断 0.50、其余（多选/填空/简答）0。 */
    public static BigDecimal guessingOf(QuestionType type) {
        if (type == QuestionType.SINGLE) {
            return BigDecimal.valueOf(0.25).setScale(4, RoundingMode.HALF_UP);
        }
        if (type == QuestionType.JUDGE) {
            return BigDecimal.valueOf(0.50).setScale(4, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * 标定某场考试的题目参数（幂等）：TTL 内已标定则跳过；
     * 否则按同源 CTT 指标 + 练习作答合并样本，逐题 upsert 并返回本次标定题数。
     */
    public int calibrateExam(Long examId) {
        if (examId == null) {
            return 0;
        }
        if (isExamFresh(examId)) {
            return 0;
        }
        List<ScoreExportService.QuestionStatMetric> metrics =
                examAnalysisReportService.questionMetrics(examId);
        if (metrics == null || metrics.isEmpty()) {
            return 0;
        }
        List<Long> questionIds = metrics.stream()
                .map(metric -> metric.question().questionId())
                .toList();
        Map<Long, int[]> practiceStats = practiceStats(questionIds);

        int calibrated = 0;
        LocalDateTime now = LocalDateTime.now();
        for (ScoreExportService.QuestionStatMetric metric : metrics) {
            Long questionId = metric.question().questionId();
            QuestionType type = metric.question().type();
            int[] attempts = practiceStats.getOrDefault(questionId, new int[2]);
            int examAnswered = metric.answeredCount();
            int examCorrect = (int) Math.round(metric.correctRate() * examAnswered);
            int totalAnswered = examAnswered + attempts[0];
            int totalCorrect = examCorrect + attempts[1];
            Double difficulty = totalAnswered == 0
                    ? null : (double) totalCorrect / totalAnswered;
            Double discrimination = metric.discrimination();
            // 样本不足（合并样本 < 4）或缺区分度（实考不足 4 人）→ 标记不可用，不进题池
            boolean available = type != QuestionType.SHORT_ANSWER
                    && totalAnswered >= MIN_SAMPLE_SIZE
                    && discrimination != null;

            QuestionIrtParam row = findOrNew(questionId);
            row.setDiscrimination(discriminationToA(discrimination));
            row.setDifficulty(difficultyToB(difficulty));
            row.setGuessing(guessingOf(type));
            row.setSampleSize(totalAnswered);
            row.setPoolAvailable(available);
            row.setSourceExamId(examId);
            row.setCalibratedTime(now);
            upsert(row);
            calibrated++;
        }
        log.info("IRT 标定完成: exam={} 题目={} 可入池={}", examId, calibrated,
                metrics.stream().filter(m -> m.answeredCount() >= MIN_SAMPLE_SIZE).count());
        return calibrated;
    }

    /**
     * 标定该学生已判分考试覆盖的题池（CAT 开局惰性触发）：逐场幂等标定，
     * 单场异常只记 warn 并跳过（不因一场数据问题让整次 CAT 失败）。返回已标定场次数。
     */
    public int calibrateStudentPool(Long studentId) {
        if (studentId == null) {
            return 0;
        }
        List<GradingSubmission> submissions = gradingSubmissionMapper.selectList(
                Wrappers.<GradingSubmission>lambdaQuery()
                        .eq(GradingSubmission::getStudentId, studentId)
                        .eq(GradingSubmission::getStatus, ExamSubmission.STATUS_GRADED)
                        .select(GradingSubmission::getExamId));
        Set<Long> examIds = new LinkedHashSet<>();
        for (GradingSubmission submission : submissions) {
            if (submission.getExamId() != null) {
                examIds.add(submission.getExamId());
            }
        }
        int calibratedExams = 0;
        for (Long examId : examIds) {
            try {
                calibrateExam(examId);
                calibratedExams++;
            } catch (Exception e) {
                log.warn("IRT 标定跳过考试 {}: {}", examId, e.getMessage());
            }
        }
        return calibratedExams;
    }

    /** 当前可入池的题目参数（pool_available=true，按 question_id 升序稳定）。 */
    public List<QuestionIrtParam> availablePool() {
        List<QuestionIrtParam> rows = questionIrtParamMapper.selectList(
                Wrappers.<QuestionIrtParam>lambdaQuery()
                        .eq(QuestionIrtParam::getPoolAvailable, true)
                        .orderByAsc(QuestionIrtParam::getQuestionId));
        return rows == null ? List.of() : rows;
    }

    /** 按题目取参数（不存在返回 null）。 */
    public QuestionIrtParam findParam(Long questionId) {
        if (questionId == null) {
            return null;
        }
        return questionIrtParamMapper.selectOne(
                Wrappers.<QuestionIrtParam>lambdaQuery()
                        .eq(QuestionIrtParam::getQuestionId, questionId)
                        .last("LIMIT 1"));
    }

    /**
     * 把题池参数过滤成"题目仍存在且为客观题"的可用集合（CAT 选题范围）：
     * 软删题目与简答题一律排除，返回按参数表顺序稳定的参数列表。
     */
    public List<QuestionIrtParam> usablePool() {
        List<QuestionIrtParam> pool = availablePool();
        if (pool.isEmpty()) {
            return List.of();
        }
        List<Long> ids = pool.stream().map(QuestionIrtParam::getQuestionId).toList();
        List<Question> questions = questionMapper.selectBatchIds(ids);
        if (questions == null || questions.isEmpty()) {
            return List.of();
        }
        Set<Long> usableIds = new LinkedHashSet<>();
        for (Question question : questions) {
            QuestionType type = QuestionType.of(question.getType());
            if (type != null && type != QuestionType.SHORT_ANSWER) {
                usableIds.add(question.getId());
            }
        }
        List<QuestionIrtParam> usable = new ArrayList<>();
        for (QuestionIrtParam param : pool) {
            if (usableIds.contains(param.getQuestionId())) {
                usable.add(param);
            }
        }
        return usable;
    }

    private boolean isExamFresh(Long examId) {
        Long fresh = questionIrtParamMapper.selectCount(
                Wrappers.<QuestionIrtParam>lambdaQuery()
                        .eq(QuestionIrtParam::getSourceExamId, examId)
                        .gt(QuestionIrtParam::getCalibratedTime, LocalDateTime.now().minus(REFRESH_TTL)));
        return fresh != null && fresh > 0;
    }

    private QuestionIrtParam findOrNew(Long questionId) {
        QuestionIrtParam existing = findParam(questionId);
        if (existing != null) {
            return existing;
        }
        QuestionIrtParam created = new QuestionIrtParam();
        created.setQuestionId(questionId);
        return created;
    }

    private void upsert(QuestionIrtParam row) {
        if (row.getId() == null) {
            questionIrtParamMapper.insert(row);
        } else {
            questionIrtParamMapper.updateById(row);
        }
    }

    /** 按题聚合练习作答（次数 / 答对次数），与考试作答合并成标定样本。 */
    private Map<Long, int[]> practiceStats(Collection<Long> questionIds) {
        if (questionIds == null || questionIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<PracticeAttempt> attempts = practiceAttemptMapper.selectList(
                Wrappers.<PracticeAttempt>lambdaQuery()
                        .in(PracticeAttempt::getQuestionId, questionIds));
        if (attempts == null || attempts.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, int[]> stats = new HashMap<>();
        for (PracticeAttempt attempt : attempts) {
            int[] counts = stats.computeIfAbsent(attempt.getQuestionId(), key -> new int[2]);
            counts[0]++;
            if (Boolean.TRUE.equals(attempt.getIsCorrect())) {
                counts[1]++;
            }
        }
        return stats;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}

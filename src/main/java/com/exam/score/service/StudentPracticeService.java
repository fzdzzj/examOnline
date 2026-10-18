package com.exam.score.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.grading.model.GradeResult;
import com.exam.grading.model.GradingConfig;
import com.exam.grading.model.GradingQuestion;
import com.exam.grading.strategy.GradingStrategyRegistry;
import com.exam.question.entity.Question;
import com.exam.question.entity.QuestionType;
import com.exam.question.mapper.QuestionMapper;
import com.exam.score.dto.PracticePoolItem;
import com.exam.score.dto.PracticeQuizAnswer;
import com.exam.score.dto.PracticeQuizGenerateRequest;
import com.exam.score.dto.PracticeQuizResponse;
import com.exam.score.dto.PracticeQuizResultItem;
import com.exam.score.dto.PracticeQuizSubmitRequest;
import com.exam.score.dto.PracticeQuizSubmitResponse;
import com.exam.score.dto.PracticeSubmitRequest;
import com.exam.score.dto.PracticeSubmitResponse;
import com.exam.score.entity.PracticeAttempt;
import com.exam.score.mapper.PracticeAttemptMapper;
import com.exam.score.support.WrongQuestionJudgementCache;
import com.exam.submission.entity.ExamSubmission;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 错题重练与消灭计划服务（spec add-wrong-question-practice）：
 * <ul>
 *   <li>重练池构建：本人全部已发布考试中判定为错题的题目、跨考试去重、仅客观题、排除已消灭；</li>
 *   <li>判分同源复用：复用既有客观判分核心策略（{@link GradingStrategyRegistry}），零另造口径；</li>
 *   <li>消灭口径：纯练习序列，同一题目连续 3 次答对自动消灭，任一次答错连对归零。</li>
 * </ul>
 */
@Slf4j
@Service
public class StudentPracticeService {

    /** 掌握度三态名称：连对 0 待攻克 / 1~2 攻克中 / >=3 已掌握（spec add-student-wrong-question-mastery） */
    public static final String MASTERY_UNTOUCHED = "UNTOUCHED";
    public static final String MASTERY_PRACTICING = "PRACTICING";
    public static final String MASTERY_MASTERED = "MASTERED";

    /** 抽题策略：全局随机 / 攻克中优先 */
    public static final String STRATEGY_RANDOM = "RANDOM";
    public static final String STRATEGY_PRACTICING_FIRST = "PRACTICING_FIRST";

    private static final int QUIZ_LIMIT_DEFAULT = 5;
    private static final int QUIZ_LIMIT_MAX = 20;
    private static final int MASTERY_CONSECUTIVE_THRESHOLD = 3;

    private final ExamMapper examMapper;
    private final GradingSubmissionMapper gradingSubmissionMapper;
    private final QuestionMapper questionMapper;
    private final PracticeAttemptMapper practiceAttemptMapper;
    private final ScoreExportService scoreExportService;
    private final GradingStrategyRegistry strategyRegistry;
    private final GradingConfig gradingConfig;
    private final ObjectMapper objectMapper;

    public record PracticeProgress(int consecutiveCorrect, boolean mastered) {
        public static final PracticeProgress ZERO = new PracticeProgress(0, false);
    }

    public StudentPracticeService(ExamMapper examMapper,
                                  GradingSubmissionMapper gradingSubmissionMapper,
                                  QuestionMapper questionMapper,
                                  PracticeAttemptMapper practiceAttemptMapper,
                                  ScoreExportService scoreExportService,
                                  GradingStrategyRegistry strategyRegistry,
                                  GradingConfig gradingConfig,
                                  ObjectMapper objectMapper) {
        this.examMapper = examMapper;
        this.gradingSubmissionMapper = gradingSubmissionMapper;
        this.questionMapper = questionMapper;
        this.practiceAttemptMapper = practiceAttemptMapper;
        this.scoreExportService = scoreExportService;
        this.strategyRegistry = strategyRegistry;
        this.gradingConfig = gradingConfig;
        this.objectMapper = objectMapper;
    }

    /**
     * 获取学生的错题重练池（去重、仅机判题型、排除已消灭）。
     */
    public List<PracticePoolItem> getPracticePool(Long studentId) {
        List<Exam> publishedExams = loadPublishedExams(studentId);
        if (publishedExams.isEmpty()) {
            return Collections.emptyList();
        }

        // 收集所有已发布考试的错题（保持题目初次遇到顺序）
        Map<Long, GradingQuestion> wrongQuestions = new LinkedHashMap<>();
        for (Exam exam : publishedExams) {
            ScoreExportService.WrongQuestionView view;
            try {
                // 错题判定缓存：命中免逐场全卷判分重算（assembleGradingCore），未命中按现算口径回填
                view = scoreExportService.wrongQuestionView(exam, studentId);
            } catch (Exception e) {
                log.warn("重练池加载错题判定异常，跳过考试 {}: {}", exam.getId(), e.getMessage());
                continue;
            }
            if (view == null || view.questions() == null) {
                continue;
            }

            for (GradingQuestion q : view.questions()) {
                WrongQuestionJudgementCache.ScoredQuestion rs = view.scores().get(q.questionId());
                if (rs == null || rs.score() == null || rs.max() == null) {
                    continue;
                }
                // 错题判定：得分严格小于满分
                if (rs.score().compareTo(rs.max()) < 0) {
                    // 仅客观题入池（简答题排除）
                    if (q.type() != QuestionType.SHORT_ANSWER) {
                        wrongQuestions.putIfAbsent(q.questionId(), q);
                    }
                }
            }
        }

        if (wrongQuestions.isEmpty()) {
            return Collections.emptyList();
        }

        // 批量计算连对与消灭进度
        Map<Long, PracticeProgress> progressMap = calculateProgressMap(studentId, wrongQuestions.keySet());

        List<PracticePoolItem> poolItems = new ArrayList<>();
        for (Map.Entry<Long, GradingQuestion> entry : wrongQuestions.entrySet()) {
            Long qid = entry.getKey();
            GradingQuestion q = entry.getValue();
            PracticeProgress progress = progressMap.getOrDefault(qid, PracticeProgress.ZERO);
            // 排除已消灭题目
            if (progress.mastered()) {
                continue;
            }

            PracticePoolItem item = new PracticePoolItem();
            item.setQuestionId(qid);
            item.setQuestionType(labelOf(q.type()));
            item.setQuestionContent(q.content());
            item.setImageUrl(q.imageUrl());
            item.setChoices(q.choices());
            item.setConsecutiveCorrect(progress.consecutiveCorrect());
            item.setMasteryStatus(masteryStatusOf(progress.consecutiveCorrect()));
            item.setMasteryStatusName(masteryStatusNameOf(progress.consecutiveCorrect()));
            poolItems.add(item);
        }

        return poolItems;
    }

    /**
     * 学生提交单题重练作答并即时判分反馈。
     */
    public PracticeSubmitResponse submitPractice(Long studentId, PracticeSubmitRequest request) {
        Long questionId = request.getQuestionId();
        if (questionId == null) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "题目 ID 不能为空");
        }

        // 门控：题目必须属于学生本人已发布考试的错题池（短路定位，见 {@link #isWrongQuestionOf}）
        if (!isWrongQuestionOf(studentId, questionId)) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "该题目不属于您的错题重练池");
        }

        // 同源双路解析：题库实体优先，原题软删回退考试快照（与批量交卷共用）
        ResolvedGrading resolved = resolveGradingQuestion(studentId, questionId);
        if (resolved == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "题目不存在");
        }
        GradingQuestion gradingQuestion = resolved.question();
        String analysis = resolved.analysis();

        // 复用既有客观判分核心
        GradeResult gradeResult = strategyRegistry.dispatch(gradingQuestion)
                .grade(gradingQuestion, request.getAnswer(), gradingConfig);
        // 满分即答对；多选漏选部分分时 correct=false
        boolean isCorrect = gradeResult.correct();

        // 持久化一条 attempt 明细
        PracticeAttempt attempt = new PracticeAttempt();
        attempt.setStudentId(studentId);
        attempt.setQuestionId(questionId);
        attempt.setIsCorrect(isCorrect);
        attempt.setCreatedTime(LocalDateTime.now());
        practiceAttemptMapper.insert(attempt);

        // 推导连对进度：连对窗口即 3（consecutiveCorrect >= 3 即消灭），回读只取最近 3 条——
        // 第四条及以前必然已被窗口截断，语义与全量回读等价（mastered 判定不因历史更长而改变）
        int consecutiveCorrect = readConsecutiveCorrect(studentId, questionId);
        boolean mastered = consecutiveCorrect >= 3;

        PracticeSubmitResponse response = new PracticeSubmitResponse();
        response.setCorrect(isCorrect);
        response.setCorrectAnswer(gradingQuestion.correctAnswer() != null ? gradingQuestion.correctAnswer() : "");
        response.setAnalysis(analysis != null ? analysis : "");
        response.setConsecutiveCorrect(consecutiveCorrect);
        response.setMastered(mastered);
        response.setMasteryStatus(masteryStatusOf(consecutiveCorrect));
        response.setMasteryStatusName(masteryStatusNameOf(consecutiveCorrect));
        return response;
    }

    /**
     * 掌握度三态推导（唯一事实源，spec add-student-wrong-question-mastery）：
     * 错题本条目、重练池条目、单题反馈与自测逐题结果共用同一推导，严禁旁路另算。
     * <ul>
     *   <li>连对 0：0 / UNTOUCHED（待攻克，从未练习或最近一次答错清零）；</li>
     *   <li>连对 1~2：1 / PRACTICING（攻克中）；</li>
     *   <li>连对 >= 3：2 / MASTERED（已掌握，达成消灭标准出本）。</li>
     * </ul>
     */
    public static int masteryStatusOf(int consecutiveCorrect) {
        if (consecutiveCorrect >= MASTERY_CONSECUTIVE_THRESHOLD) {
            return 2;
        }
        if (consecutiveCorrect >= 1) {
            return 1;
        }
        return 0;
    }

    /** 三态名称推导（与 {@link #masteryStatusOf} 同源）。 */
    public static String masteryStatusNameOf(int consecutiveCorrect) {
        return switch (masteryStatusOf(consecutiveCorrect)) {
            case 2 -> MASTERY_MASTERED;
            case 1 -> MASTERY_PRACTICING;
            default -> MASTERY_UNTOUCHED;
        };
    }

    /**
     * 学生生成错题强化自测卷（spec add-student-wrong-question-mastery）：
     * 从本人未消灭客观错题池按策略抽题组卷（纯内存、无状态、零 DDL），
     * quizId 为无状态 UUID（仅作答卷回执关联，不落库），作答前严格不泄露正确答案与解析。
     */
    public PracticeQuizResponse generateQuiz(Long studentId, PracticeQuizGenerateRequest request) {
        int limit = request.getLimit() != null ? request.getLimit() : QUIZ_LIMIT_DEFAULT;
        if (limit < 1 || limit > QUIZ_LIMIT_MAX) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "抽题量必须在 1~20 之间");
        }
        boolean practicingFirst;
        if (request.getStrategy() == null || request.getStrategy().isBlank()
                || STRATEGY_RANDOM.equals(request.getStrategy())) {
            practicingFirst = false;
        } else if (STRATEGY_PRACTICING_FIRST.equals(request.getStrategy())) {
            practicingFirst = true;
        } else {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "抽题策略仅支持 RANDOM 或 PRACTICING_FIRST");
        }

        List<PracticePoolItem> pool = getPracticePool(studentId);
        List<PracticePoolItem> selected = selectQuizItems(pool, limit, practicingFirst);

        PracticeQuizResponse response = new PracticeQuizResponse();
        response.setQuizId(UUID.randomUUID().toString());
        response.setTotal(selected.size());
        response.setItems(selected);
        return response;
    }

    /**
     * 按策略抽题：RANDOM 全局乱序截断；PRACTICING_FIRST 优先攻克中（连对 &gt; 0）促成消灭，
     * 不足时以未消灭（UNTOUCHED）补齐。池不超 limit 时全量返回（攻克中优先仍生效）。
     */
    private List<PracticePoolItem> selectQuizItems(List<PracticePoolItem> pool, int limit, boolean practicingFirst) {
        List<PracticePoolItem> ordered = new ArrayList<>();
        if (practicingFirst) {
            List<PracticePoolItem> practicing = new ArrayList<>();
            List<PracticePoolItem> untouched = new ArrayList<>();
            for (PracticePoolItem item : pool) {
                int consecutive = item.getConsecutiveCorrect() != null ? item.getConsecutiveCorrect() : 0;
                if (consecutive > 0) {
                    practicing.add(item);
                } else {
                    untouched.add(item);
                }
            }
            Collections.shuffle(practicing);
            Collections.shuffle(untouched);
            ordered.addAll(practicing);
            ordered.addAll(untouched);
        } else {
            ordered.addAll(pool);
            Collections.shuffle(ordered);
        }
        if (ordered.size() > limit) {
            return new ArrayList<>(ordered.subList(0, limit));
        }
        return ordered;
    }

    /**
     * 学生批量交卷强化自测（spec add-student-wrong-question-mastery）：
     * 先整单归属门控（严禁越权写入），再逐题同源复用既有客观判分策略、
     * 逐题落既有 practice_attempt 表（零 DDL），并推导最新连对与掌握度三态，
     * 汇总返回总题数、答对数、正确率与新消灭数。
     */
    public PracticeQuizSubmitResponse submitQuiz(Long studentId, PracticeQuizSubmitRequest request) {
        if (request.getQuizId() == null || request.getQuizId().isBlank()) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "quizId 不能为空");
        }
        List<PracticeQuizAnswer> answers = request.getAnswers();
        if (answers == null || answers.isEmpty()) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "作答列表不能为空");
        }
        for (PracticeQuizAnswer answer : answers) {
            if (answer == null || answer.getQuestionId() == null) {
                throw new BusinessException(ResponseCode.BAD_REQUEST, "题目 ID 不能为空");
            }
            // 门控：逐题验证归属于本人已发布考试错题池（整单校验先行，越权严禁落库）
            if (!isWrongQuestionOf(studentId, answer.getQuestionId())) {
                throw new BusinessException(ResponseCode.BAD_REQUEST, "该题目不属于您的错题重练池");
            }
        }

        // 自测前各题连对基线：新消灭 = 本次晋级已掌握 且 自测前未掌握（同题重复作答只计一次）
        Map<Long, PracticeProgress> prevProgressMap = calculateProgressMap(studentId,
                answers.stream().map(PracticeQuizAnswer::getQuestionId).toList());

        List<PracticeQuizResultItem> results = new ArrayList<>();
        int correctCount = 0;
        int newlyMasteredCount = 0;
        Set<Long> newlyCounted = new HashSet<>();
        for (PracticeQuizAnswer answer : answers) {
            Long questionId = answer.getQuestionId();
            ResolvedGrading resolved = resolveGradingQuestion(studentId, questionId);
            if (resolved == null) {
                throw new BusinessException(ResponseCode.NOT_FOUND, "题目不存在");
            }

            // 复用既有客观判分核心（满分即答对；多选漏选部分分时 correct=false）
            GradeResult gradeResult = strategyRegistry.dispatch(resolved.question())
                    .grade(resolved.question(), answer.getAnswer(), gradingConfig);
            boolean isCorrect = gradeResult.correct();

            PracticeAttempt attempt = new PracticeAttempt();
            attempt.setStudentId(studentId);
            attempt.setQuestionId(questionId);
            attempt.setIsCorrect(isCorrect);
            attempt.setCreatedTime(LocalDateTime.now());
            practiceAttemptMapper.insert(attempt);

            int consecutiveCorrect = readConsecutiveCorrect(studentId, questionId);
            boolean mastered = consecutiveCorrect >= MASTERY_CONSECUTIVE_THRESHOLD;
            boolean wasMastered = prevProgressMap.getOrDefault(questionId, PracticeProgress.ZERO).mastered();
            if (mastered && !wasMastered && newlyCounted.add(questionId)) {
                newlyMasteredCount++;
            }
            if (isCorrect) {
                correctCount++;
            }

            PracticeQuizResultItem item = new PracticeQuizResultItem();
            item.setQuestionId(questionId);
            item.setCorrect(isCorrect);
            item.setCorrectAnswer(resolved.question().correctAnswer() != null
                    ? resolved.question().correctAnswer() : "");
            item.setAnalysis(resolved.analysis() != null ? resolved.analysis() : "");
            item.setConsecutiveCorrect(consecutiveCorrect);
            item.setMastered(mastered);
            item.setMasteryStatus(masteryStatusOf(consecutiveCorrect));
            item.setMasteryStatusName(masteryStatusNameOf(consecutiveCorrect));
            results.add(item);
        }

        PracticeQuizSubmitResponse response = new PracticeQuizSubmitResponse();
        response.setTotal(answers.size());
        response.setCorrectCount(correctCount);
        response.setScoreRate(BigDecimal.valueOf(correctCount * 100L)
                .divide(BigDecimal.valueOf(answers.size()), 2, RoundingMode.HALF_UP));
        response.setNewlyMasteredCount(newlyMasteredCount);
        response.setResults(results);
        return response;
    }

    /** 连对回读：与单题提交同口径——连对窗口即 3，回读只取最近 3 条。 */
    private int readConsecutiveCorrect(Long studentId, Long questionId) {
        List<PracticeAttempt> history = practiceAttemptMapper.selectList(
                Wrappers.<PracticeAttempt>lambdaQuery()
                        .eq(PracticeAttempt::getStudentId, studentId)
                        .eq(PracticeAttempt::getQuestionId, questionId)
                        .orderByDesc(PracticeAttempt::getId)
                        .last("LIMIT 3"));
        int consecutiveCorrect = 0;
        for (PracticeAttempt a : history) {
            if (Boolean.TRUE.equals(a.getIsCorrect())) {
                consecutiveCorrect++;
            } else {
                break;
            }
        }
        return consecutiveCorrect;
    }

    /** 判分题目解析结果：判分题（题库实体或考试快照）+ 解析（快照路径为 null）。 */
    private record ResolvedGrading(GradingQuestion question, String analysis) {
    }

    /**
     * 判分题目解析（单题提交与批量交卷共用的同源双路）：题库命中走实体装配；
     * 原题已软删则回退本人已发布考试快照；两路皆未命中返回 null（由调用方决定 404 语义）。
     */
    private ResolvedGrading resolveGradingQuestion(Long studentId, Long questionId) {
        Question question = questionMapper.selectById(questionId);
        if (question != null) {
            QuestionType type = QuestionType.of(question.getType());
            if (type == null || type == QuestionType.SHORT_ANSWER) {
                throw new BusinessException(ResponseCode.BAD_REQUEST, "简答题不支持自动重练判分");
            }

            List<String> choices = null;
            if (question.getChoices() != null && !question.getChoices().isBlank()) {
                try {
                    choices = objectMapper.readValue(question.getChoices(), new TypeReference<List<String>>() {});
                } catch (Exception e) {
                    log.warn("解析题目选项失败: questionId={}", questionId, e);
                }
            }

            BigDecimal score = question.getScore() != null ? question.getScore() : BigDecimal.ONE;
            GradingQuestion gradingQuestion = new GradingQuestion(
                    1,
                    question.getId(),
                    type,
                    question.getContent(),
                    question.getImageUrl(),
                    choices,
                    question.getCorrectAnswer(),
                    score
            );
            return new ResolvedGrading(gradingQuestion, question.getAnalysis());
        }

        // 原题已在题库中逻辑删除：回退到该生已发布考试快照装配同源题目判分，
        // 保证历史错题重练、连对进度与消灭计划不受题库变动影响（harden 点 5）。
        GradingQuestion snapshotQuestion = findSnapshotGradingQuestion(studentId, questionId);
        if (snapshotQuestion == null) {
            return null;
        }
        if (snapshotQuestion.type() == QuestionType.SHORT_ANSWER) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "简答题不支持自动重练判分");
        }
        GradingQuestion gradingQuestion = new GradingQuestion(
                snapshotQuestion.number(),
                snapshotQuestion.questionId(),
                snapshotQuestion.type(),
                snapshotQuestion.content(),
                snapshotQuestion.imageUrl(),
                snapshotQuestion.choices(),
                snapshotQuestion.correctAnswer(),
                snapshotQuestion.score()
        );
        // 快照不存解析字段，回退路径解析留空（标准答案随快照原样返回）
        return new ResolvedGrading(gradingQuestion, null);
    }

    /**
     * 在本人已发布考试快照中检索包含指定 questionId 的判分题目（原题被软删后的平滑回退数据源）。
     * 复用 {@link #loadPublishedExams} 与 {@link ScoreExportService#getPersonalReportWithoutRank}：
     * 快照锁定题目内容与标准答案，题库变动不影响历史错题链路。查不到返回 null。
     */
    private GradingQuestion findSnapshotGradingQuestion(Long studentId, Long questionId) {
        List<Exam> publishedExams = loadPublishedExams(studentId);
        for (Exam exam : publishedExams) {
            ScoreExportService.PersonalReport report;
            try {
                report = scoreExportService.getPersonalReportWithoutRank(exam, studentId);
            } catch (Exception e) {
                log.warn("重练快照回退读取成绩单异常，跳过考试 {}: {}", exam.getId(), e.getMessage());
                continue;
            }
            if (report == null || report.questions() == null) {
                continue;
            }
            for (GradingQuestion q : report.questions()) {
                if (questionId.equals(q.questionId())) {
                    return q;
                }
            }
        }
        return null;
    }

    /**
     * 批量计算题目连对进度（供错题本列表与重练池共用）。
     */
    public Map<Long, PracticeProgress> calculateProgressMap(Long studentId, Collection<Long> questionIds) {
        if (questionIds == null || questionIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<PracticeAttempt> attempts = practiceAttemptMapper.selectList(
                Wrappers.<PracticeAttempt>lambdaQuery()
                        .eq(PracticeAttempt::getStudentId, studentId)
                        .in(PracticeAttempt::getQuestionId, questionIds)
                        .orderByDesc(PracticeAttempt::getId));

        Map<Long, List<PracticeAttempt>> byQuestion = attempts.stream()
                .collect(Collectors.groupingBy(PracticeAttempt::getQuestionId));

        Map<Long, PracticeProgress> result = new HashMap<>();
        for (Long qid : questionIds) {
            List<PracticeAttempt> qAttempts = byQuestion.getOrDefault(qid, Collections.emptyList());
            int count = 0;
            for (PracticeAttempt a : qAttempts) {
                if (Boolean.TRUE.equals(a.getIsCorrect())) {
                    count++;
                } else {
                    break;
                }
            }
            result.put(qid, new PracticeProgress(count, count >= 3));
        }
        return result;
    }

    /**
     * 获取学生本人所有已发布考试中判定为错题的题目 ID 集合。
     */
    public Set<Long> getStudentWrongQuestionIds(Long studentId) {
        List<Exam> publishedExams = loadPublishedExams(studentId);
        Set<Long> wrongQids = new HashSet<>();
        for (Exam exam : publishedExams) {
            Map<Long, WrongQuestionJudgementCache.ScoredQuestion> scored;
            try {
                // 错题判定缓存（命中免逐场全卷判分重算，未命中按现算口径回填）
                scored = scoreExportService.wrongQuestionScores(exam, studentId);
            } catch (Exception e) {
                continue;
            }
            if (scored == null) {
                continue;
            }
            for (Map.Entry<Long, WrongQuestionJudgementCache.ScoredQuestion> e : scored.entrySet()) {
                WrongQuestionJudgementCache.ScoredQuestion rs = e.getValue();
                if (rs != null && rs.score() != null && rs.max() != null
                        && rs.score().compareTo(rs.max()) < 0) {
                    wrongQids.add(e.getKey());
                }
            }
        }
        return wrongQids;
    }

    /**
     * 单题提交门控的短路定位：该题是否属于本人错题池（optimize-practice-pool-hot-path）。
     *
     * <p>逐场装配成绩单快照，命中即短路——找到该生某场已判分快照包含该题且判分严格小于满分
     * 即通过；全部场扫描完不命中返回 false。判定口径与 {@link #getStudentWrongQuestionIds}
     * 完全一致（得分严格小于满分），正常路径（题在池中）止步于命中场，不再全量重建错题集合
     * （O(场数×题数) 叠加在提交热路径上）。门控定位循环中单场装配异常记录 warn 后跳过该场
     * （对齐同文件 L93/L265 同型循环口径），不得静默吞。
     */
    public boolean isWrongQuestionOf(Long studentId, Long questionId) {
        List<Exam> publishedExams = loadPublishedExams(studentId);
        for (Exam exam : publishedExams) {
            Map<Long, WrongQuestionJudgementCache.ScoredQuestion> scored;
            try {
                // 错题判定缓存（命中免逐场全卷判分重算，未命中按现算口径回填）
                scored = scoreExportService.wrongQuestionScores(exam, studentId);
            } catch (Exception e) {
                log.warn("错题池门控定位成绩单异常，跳过考试 {}: {}", exam.getId(), e.getMessage());
                continue;
            }
            if (scored == null) {
                continue;
            }
            WrongQuestionJudgementCache.ScoredQuestion rs = scored.get(questionId);
            if (rs != null && rs.score() != null && rs.max() != null
                    && rs.score().compareTo(rs.max()) < 0) {
                return true;
            }
        }
        return false;
    }

    private List<Exam> loadPublishedExams(Long studentId) {
        List<GradingSubmission> submissions = gradingSubmissionMapper.selectList(
                Wrappers.<GradingSubmission>lambdaQuery()
                        .eq(GradingSubmission::getStudentId, studentId)
                        .eq(GradingSubmission::getStatus, ExamSubmission.STATUS_GRADED)
                        .isNotNull(GradingSubmission::getTotalScore)
                        .select(GradingSubmission::getExamId));
        if (submissions.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> examIds = submissions.stream()
                .map(GradingSubmission::getExamId)
                .distinct()
                .toList();
        List<Exam> exams = examMapper.selectBatchIds(examIds);
        return exams.stream()
                .filter(this::isExamPublished)
                .sorted(Comparator.comparing(Exam::getStartTime, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Exam::getId, Comparator.reverseOrder()))
                .toList();
    }

    private boolean isExamPublished(Exam exam) {
        if (exam == null) {
            return false;
        }
        Exam root = exam;
        while (root.getParentExamId() != null) {
            Exam parent = examMapper.selectById(root.getParentExamId());
            if (parent == null) {
                break;
            }
            root = parent;
        }
        return root.getStatus() != null && root.getStatus() == Exam.STATUS_PUBLISHED;
    }

    private String labelOf(QuestionType type) {
        if (type == null) {
            return "未知题型";
        }
        return switch (type) {
            case SINGLE -> "单选";
            case MULTIPLE -> "多选";
            case JUDGE -> "判断";
            case SHORT_ANSWER -> "简答";
            case FILL_BLANK -> "填空";
        };
    }
}

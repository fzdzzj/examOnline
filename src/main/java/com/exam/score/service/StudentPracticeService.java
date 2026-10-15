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
import com.exam.score.dto.PracticeSubmitRequest;
import com.exam.score.dto.PracticeSubmitResponse;
import com.exam.score.entity.PracticeAttempt;
import com.exam.score.mapper.PracticeAttemptMapper;
import com.exam.score.support.QuestionScoreResolver;
import com.exam.submission.entity.ExamSubmission;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
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
            ScoreExportService.PersonalReport report;
            try {
                report = scoreExportService.getPersonalReportWithoutRank(exam, studentId);
            } catch (Exception e) {
                log.warn("重练池加载成绩单异常，跳过考试 {}: {}", exam.getId(), e.getMessage());
                continue;
            }
            if (report == null || report.questions() == null) {
                continue;
            }

            for (GradingQuestion q : report.questions()) {
                QuestionScoreResolver.ResolvedQuestionScore rs = report.scores().get(q.questionId());
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

        // 门控：题目必须属于学生本人已发布考试的错题池
        Set<Long> myWrongQids = getStudentWrongQuestionIds(studentId);
        if (!myWrongQids.contains(questionId)) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "该题目不属于您的错题重练池");
        }

        Question question = questionMapper.selectById(questionId);
        String analysis;
        GradingQuestion gradingQuestion;
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
            gradingQuestion = new GradingQuestion(
                    1,
                    question.getId(),
                    type,
                    question.getContent(),
                    question.getImageUrl(),
                    choices,
                    question.getCorrectAnswer(),
                    score
            );
            analysis = question.getAnalysis();
        } else {
            // 原题已在题库中逻辑删除：回退到该生已发布考试快照装配同源题目判分，
            // 保证历史错题重练、连对进度与消灭计划不受题库变动影响（harden 点 5）。
            GradingQuestion snapshotQuestion = findSnapshotGradingQuestion(studentId, questionId);
            if (snapshotQuestion == null) {
                throw new BusinessException(ResponseCode.NOT_FOUND, "题目不存在");
            }
            if (snapshotQuestion.type() == QuestionType.SHORT_ANSWER) {
                throw new BusinessException(ResponseCode.BAD_REQUEST, "简答题不支持自动重练判分");
            }
            gradingQuestion = new GradingQuestion(
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
            analysis = null;
        }

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

        // 推导连对进度
        List<PracticeAttempt> history = practiceAttemptMapper.selectList(
                Wrappers.<PracticeAttempt>lambdaQuery()
                        .eq(PracticeAttempt::getStudentId, studentId)
                        .eq(PracticeAttempt::getQuestionId, questionId)
                        .orderByDesc(PracticeAttempt::getId));
        int consecutiveCorrect = 0;
        for (PracticeAttempt a : history) {
            if (Boolean.TRUE.equals(a.getIsCorrect())) {
                consecutiveCorrect++;
            } else {
                break;
            }
        }
        boolean mastered = consecutiveCorrect >= 3;

        PracticeSubmitResponse response = new PracticeSubmitResponse();
        response.setCorrect(isCorrect);
        response.setCorrectAnswer(gradingQuestion.correctAnswer() != null ? gradingQuestion.correctAnswer() : "");
        response.setAnalysis(analysis != null ? analysis : "");
        response.setConsecutiveCorrect(consecutiveCorrect);
        response.setMastered(mastered);
        return response;
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
            ScoreExportService.PersonalReport report;
            try {
                report = scoreExportService.getPersonalReportWithoutRank(exam, studentId);
            } catch (Exception e) {
                continue;
            }
            if (report == null || report.questions() == null) {
                continue;
            }
            for (GradingQuestion q : report.questions()) {
                QuestionScoreResolver.ResolvedQuestionScore rs = report.scores().get(q.questionId());
                if (rs != null && rs.score() != null && rs.max() != null
                        && rs.score().compareTo(rs.max()) < 0) {
                    wrongQids.add(q.questionId());
                }
            }
        }
        return wrongQids;
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

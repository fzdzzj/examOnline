package com.exam.score.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.clazz.service.ClassService;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.grading.model.GradingPaper;
import com.exam.grading.model.GradingQuestion;
import com.exam.grading.support.GradingPaperReader;
import com.exam.question.entity.QuestionTag;
import com.exam.question.entity.QuestionType;
import com.exam.question.entity.Tag;
import com.exam.question.mapper.QuestionTagMapper;
import com.exam.question.mapper.TagMapper;
import com.exam.score.dto.ExamAnalysisReportResponse;
import com.exam.submission.entity.ExamSubmission;
import com.exam.submission.mapper.ExamSubmissionMapper;
import com.exam.user.entity.User;
import com.exam.user.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 考试数据分析报告服务（add-exam-analysis-report，创新点4 考试数据分析报告）。
 *
 * <p>只读聚合四块：班级概览 / 逐题指标 / 知识点薄弱 / 学生关注名单。逐题指标复用
 * {@link ScoreExportService#aggregateQuestionStats}（题目统计导出的唯一聚合来源，口径同源）。
 * 班级概览的应考/缺席沿用缺考域口径（应考=班级名单，缺席=应考−有答卷者），及格线口径
 * 见 {@link #PASS_LINE}。不写任何数据，不逐生×逐题交叉矩阵（留二期）。
 */
@Service
public class ExamAnalysisReportService {

    /**
     * 及格线（分析报告口径）：≥ {@code PASS_LINE} 记为及格。既有成绩汇总/导出口径未定义
     * 及格率，本值为本次新增指标口径，注释在案。学生关注名单取低于该线的学生。
     */
    private static final BigDecimal PASS_LINE = new BigDecimal("60");

    private final ExamMapper examMapper;
    private final ScoreExportService scoreExportService;
    private final GradingPaperReader paperReader;
    private final GradingSubmissionMapper gradingSubmissionMapper;
    private final ExamSubmissionMapper submissionMapper;
    private final ClassService classService;
    private final UserMapper userMapper;
    private final QuestionTagMapper questionTagMapper;
    private final TagMapper tagMapper;

    public ExamAnalysisReportService(ExamMapper examMapper,
                                     ScoreExportService scoreExportService,
                                     GradingPaperReader paperReader,
                                     GradingSubmissionMapper gradingSubmissionMapper,
                                     ExamSubmissionMapper submissionMapper,
                                     ClassService classService,
                                     UserMapper userMapper,
                                     QuestionTagMapper questionTagMapper,
                                     TagMapper tagMapper) {
        this.examMapper = examMapper;
        this.scoreExportService = scoreExportService;
        this.paperReader = paperReader;
        this.gradingSubmissionMapper = gradingSubmissionMapper;
        this.submissionMapper = submissionMapper;
        this.classService = classService;
        this.userMapper = userMapper;
        this.questionTagMapper = questionTagMapper;
        this.tagMapper = tagMapper;
    }

    /** 生成考试数据分析报告（只读）。考试不存在/非归属越权由控制器 {@code requireOwnedExam} 裁决。 */
    public ExamAnalysisReportResponse buildReport(Long examId) {
        Exam exam = examMapper.selectById(examId);
        if (exam == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "考试不存在");
        }
        if (exam.getStatus() < Exam.STATUS_GRADED) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "成绩尚未汇总，请先执行汇总再查看分析报告");
        }

        GradingPaper paper = paperReader.readByExamId(examId);
        List<GradingQuestion> questions = paper.questions();

        // 逐题指标：与题目统计导出同一聚合来源（同源同口径，禁止另造第二套公式）
        List<ScoreExportService.QuestionStatMetric> metrics =
                scoreExportService.aggregateQuestionStats(examId, paper, questions);

        List<GradingSubmission> graded = listGraded(examId);
        List<Integer> gradedStudentIds = graded.stream()
                .map(GradingSubmission::getStudentId)
                .filter(Objects::nonNull)
                .map(Long::intValue)
                .distinct()
                .sorted()
                .toList();

        ExamAnalysisReportResponse response = new ExamAnalysisReportResponse();
        response.setClassOverview(buildClassOverview(exam, gradedStudentIds, graded));
        response.setQuestionStats(buildQuestionStats(questions, metrics));
        TagDimension tagDimension = buildTagWeakness(questions, metrics);
        response.setTagWeakness(tagDimension.items());
        response.setHasTagDimension(tagDimension.hasDimension());
        response.setFocusList(buildFocusList(graded, gradedStudentIds));
        return response;
    }

    // ==================== 班级概览 ====================

    private ExamAnalysisReportResponse.ClassOverview buildClassOverview(Exam exam,
                                                                        List<Integer> gradedStudentIds,
                                                                        List<GradingSubmission> graded) {
        ExamAnalysisReportResponse.ClassOverview overview = new ExamAnalysisReportResponse.ClassOverview();

        List<Long> expectedIds = exam.getClassId() == null
                ? List.of()
                : classService.listStudentIds(exam.getClassId());
        overview.setExpectedCount(expectedIds.size());

        // 缺席 = 应考 − 有答卷（沿用缺考域 AbsenceService 口径：应考名单 − 有答卷者）
        int absent = 0;
        if (!expectedIds.isEmpty()) {
            List<Long> submittedIds = submissionMapper.selectList(Wrappers.<ExamSubmission>lambdaQuery()
                            .select(ExamSubmission::getStudentId)
                            .eq(ExamSubmission::getExamId, exam.getId())
                            .in(ExamSubmission::getStudentId, expectedIds)).stream()
                    .map(ExamSubmission::getStudentId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            for (Long expectedId : expectedIds) {
                if (!submittedIds.contains(expectedId)) {
                    absent++;
                }
            }
        }
        overview.setAbsenceCount(absent);

        // 实考 = 已汇总（GRADED）答卷数（与导出处 path 同源）
        overview.setActualCount(gradedStudentIds.size());

        List<BigDecimal> totals = graded.stream()
                .map(GradingSubmission::getTotalScore)
                .filter(Objects::nonNull)
                .toList();
        overview.setAverageScore(averageOf(totals));
        overview.setPassRate(passRateOf(totals));
        overview.setScoreBands(scoreBandsOf(totals));
        return overview;
    }

    private static BigDecimal averageOf(List<BigDecimal> totals) {
        if (totals.isEmpty()) {
            return null;
        }
        BigDecimal sum = totals.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(totals.size()), 1, RoundingMode.HALF_UP);
    }

    private static Double passRateOf(List<BigDecimal> totals) {
        if (totals.isEmpty()) {
            return null;
        }
        long pass = totals.stream().filter(t -> t.compareTo(PASS_LINE) >= 0).count();
        return (double) pass / totals.size();
    }

    private static List<ExamAnalysisReportResponse.ScoreBandItem> scoreBandsOf(List<BigDecimal> totals) {
        int[] bands = {0, 0, 0, 0, 0};
        for (BigDecimal total : totals) {
            int score = total.setScale(0, RoundingMode.HALF_UP).intValue();
            if (score < 60) {
                bands[0]++;
            } else if (score < 70) {
                bands[1]++;
            } else if (score < 80) {
                bands[2]++;
            } else if (score < 90) {
                bands[3]++;
            } else {
                bands[4]++;
            }
        }
        return List.of(
                new ExamAnalysisReportResponse.ScoreBandItem("0-59", bands[0]),
                new ExamAnalysisReportResponse.ScoreBandItem("60-69", bands[1]),
                new ExamAnalysisReportResponse.ScoreBandItem("70-79", bands[2]),
                new ExamAnalysisReportResponse.ScoreBandItem("80-89", bands[3]),
                new ExamAnalysisReportResponse.ScoreBandItem("90-100", bands[4]));
    }

    // ==================== 逐题指标 ====================

    private List<ExamAnalysisReportResponse.QuestionStatItem> buildQuestionStats(
            List<GradingQuestion> questions, List<ScoreExportService.QuestionStatMetric> metrics) {
        List<ExamAnalysisReportResponse.QuestionStatItem> items = new ArrayList<>(questions.size());
        for (int i = 0; i < questions.size(); i++) {
            GradingQuestion question = questions.get(i);
            ScoreExportService.QuestionStatMetric metric = metrics.get(i);
            double max = question.score().doubleValue();
            BigDecimal average = BigDecimal.valueOf(metric.averageScore())
                    .setScale(1, RoundingMode.HALF_UP);
            Double scoreRate = metric.answeredCount() == 0 || max == 0
                    ? null : metric.scoreRate();
            Double correctRate = metric.answeredCount() == 0 ? null : metric.correctRate();
            items.add(new ExamAnalysisReportResponse.QuestionStatItem(
                    question.number(),
                    labelOfType(question.type()),
                    question.score(),
                    average,
                    scoreRate,
                    correctRate,
                    metric.discrimination(),
                    metric.answeredCount()));
        }
        return items;
    }

    private static String labelOfType(QuestionType type) {
        return switch (type) {
            case SINGLE -> "单选";
            case MULTIPLE -> "多选";
            case JUDGE -> "判断";
            case SHORT_ANSWER -> "简答";
        };
    }

    // ==================== 知识点薄弱 ====================

    /** 知识点聚合结果：维度是否具备 + 按标签得分率升序列表。 */
    private record TagDimension(boolean hasDimension,
                                List<ExamAnalysisReportResponse.TagWeaknessItem> items) {
    }

    private TagDimension buildTagWeakness(List<GradingQuestion> questions,
                                          List<ScoreExportService.QuestionStatMetric> metrics) {
        if (questions.isEmpty()) {
            return new TagDimension(false, List.of());
        }
        // questionId → 得分率（来自同一聚合来源，口径同逐题指标）
        Map<Long, Double> scoreRateByQuestion = new HashMap<>();
        for (int i = 0; i < metrics.size(); i++) {
            ScoreExportService.QuestionStatMetric metric = metrics.get(i);
            if (metric.question().questionId() != null) {
                scoreRateByQuestion.put(metric.question().questionId(), metric.scoreRate());
            }
        }

        List<Long> questionIds = questions.stream()
                .map(GradingQuestion::questionId).filter(Objects::nonNull).toList();
        if (questionIds.isEmpty()) {
            return new TagDimension(false, List.of());
        }
        List<QuestionTag> links = questionTagMapper.selectList(
                Wrappers.<QuestionTag>lambdaQuery().in(QuestionTag::getQuestionId, questionIds));
        if (links.isEmpty()) {
            return new TagDimension(false, List.of());
        }
        List<Long> tagIds = links.stream().map(QuestionTag::getTagId).distinct().toList();
        Map<Long, Tag> tagById = tagMapper.selectBatchIds(tagIds).stream()
                .collect(Collectors.toMap(Tag::getId, Function.identity()));

        // 每个标签：收集携带该标签题目的得分率，取均值（一题多标签时该题得分率计入每个标签）
        Map<Long, List<Double>> ratesByTag = new HashMap<>();
        for (QuestionTag link : links) {
            Double rate = scoreRateByQuestion.get(link.getQuestionId());
            if (rate != null && tagById.containsKey(link.getTagId())) {
                ratesByTag.computeIfAbsent(link.getTagId(), k -> new ArrayList<>()).add(rate);
            }
        }
        if (ratesByTag.isEmpty()) {
            return new TagDimension(false, List.of());
        }
        List<ExamAnalysisReportResponse.TagWeaknessItem> items = new ArrayList<>();
        for (Map.Entry<Long, List<Double>> entry : ratesByTag.entrySet()) {
            double mean = entry.getValue().stream().mapToDouble(Double::doubleValue).average().orElse(0);
            items.add(new ExamAnalysisReportResponse.TagWeaknessItem(
                    entry.getKey(), tagById.get(entry.getKey()).getName(), mean));
        }
        // 薄弱点优先：得分率升序
        items.sort(Comparator.comparingDouble(a -> a.getScoreRate() == null ? 0 : a.getScoreRate()));
        return new TagDimension(true, items);
    }

    // ==================== 学生关注名单 ====================

    private List<ExamAnalysisReportResponse.FocusStudentItem> buildFocusList(
            List<GradingSubmission> graded, List<Integer> gradedStudentIds) {
        if (gradedStudentIds.isEmpty()) {
            return List.of();
        }
        List<GradingSubmission> below = graded.stream()
                .filter(g -> g.getTotalScore() != null)
                .filter(g -> g.getTotalScore().compareTo(PASS_LINE) < 0)
                .distinct()
                .toList();
        if (below.isEmpty()) {
            return List.of();
        }
        List<Long> studentIds = below.stream()
                .map(GradingSubmission::getStudentId).filter(Objects::nonNull).distinct().toList();
        Map<Long, User> users = studentIds.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(studentIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return below.stream()
                .sorted(Comparator.comparing(g -> g.getTotalScore() == null
                        ? BigDecimal.ZERO : g.getTotalScore()))
                .map(g -> new ExamAnalysisReportResponse.FocusStudentItem(
                        g.getStudentId(),
                        users.get(g.getStudentId()) == null ? "" : users.get(g.getStudentId()).getUsername(),
                        users.get(g.getStudentId()) == null ? "未知学生" : users.get(g.getStudentId()).getName(),
                        g.getTotalScore()))
                .toList();
    }

    // ==================== 查询辅助 ====================

    /** 已汇总（GRADED）答卷列表（实考口径，与导出处 path 同源）。 */
    private List<GradingSubmission> listGraded(Long examId) {
        return gradingSubmissionMapper.selectList(
                Wrappers.<GradingSubmission>lambdaQuery()
                        .eq(GradingSubmission::getExamId, examId)
                        .eq(GradingSubmission::getStatus, ExamSubmission.STATUS_GRADED)
                        .isNotNull(GradingSubmission::getTotalScore));
    }
}
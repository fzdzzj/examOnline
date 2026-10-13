package com.exam.score.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.clazz.service.ClassService;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.exam.entity.Exam;
import static com.exam.exam.service.AbsenceService.isReservationRequired;
import com.exam.exam.mapper.ExamMapper;
import com.exam.exam.service.ExamReservationService;
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
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 考试数据分析报告服务（add-exam-analysis-report，创新点4 考试数据分析报告）。
 *
 * <p>只读聚合四块：班级概览 / 逐题指标 / 知识点薄弱 / 学生关注名单。逐题指标复用
 * {@link ScoreExportService#aggregateQuestionStats}（题目统计导出的唯一聚合来源，口径同源）。
 * 班级概览的应考/缺席沿用缺考域口径（应考=班级名单，缺席=应考−有答卷者）。
 * 及格线/优秀线不再绝对（fix-analysis-report-pass-line）：按试卷总分 ×60% / ×90% 派生
 * （HALF_UP 取整到整数分），三处消费（及格率/分数段切分/关注名单）统一用派生值。
 * 不写任何数据，不逐生×逐题交叉矩阵（留二期）。
 */
@Service
public class ExamAnalysisReportService {

    /** 及格线比例：试卷总分 × {@code PASS_RATE}（HALF_UP 取整），100 分制下为 60。 */
    private static final BigDecimal PASS_RATE = new BigDecimal("0.60");
    /** 优秀线比例：试卷总分 × {@code EXCELLENT_RATE}（HALF_UP 取整），100 分制下为 90。 */
    private static final BigDecimal EXCELLENT_RATE = new BigDecimal("0.90");

    private final ExamMapper examMapper;
    private final ScoreExportService scoreExportService;
    private final GradingPaperReader paperReader;
    private final GradingSubmissionMapper gradingSubmissionMapper;
    private final ExamSubmissionMapper submissionMapper;
    private final ClassService classService;
    private final ExamReservationService reservationService;
    private final UserMapper userMapper;
    private final QuestionTagMapper questionTagMapper;
    private final TagMapper tagMapper;

    public ExamAnalysisReportService(ExamMapper examMapper,
                                     ScoreExportService scoreExportService,
                                     GradingPaperReader paperReader,
                                     GradingSubmissionMapper gradingSubmissionMapper,
                                     ExamSubmissionMapper submissionMapper,
                                     ClassService classService,
                                     ExamReservationService reservationService,
                                     UserMapper userMapper,
                                     QuestionTagMapper questionTagMapper,
                                     TagMapper tagMapper) {
        this.examMapper = examMapper;
        this.scoreExportService = scoreExportService;
        this.paperReader = paperReader;
        this.gradingSubmissionMapper = gradingSubmissionMapper;
        this.submissionMapper = submissionMapper;
        this.classService = classService;
        this.reservationService = reservationService;
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

        // 及格线/优秀线统一派生（总分 ×60% / ×90%，HALF_UP 取整到整数分）；
        // 总分不可得即 fail-fast 明确报错，不回退任何默认总分值（fix-analysis-report-pass-line）
        int passLine = passLineOf(paper.totalScore());
        int excellentLine = excellentLineOf(paper.totalScore());

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
        response.setPassLine(passLine);
        response.setExcellentLine(excellentLine);
        response.setClassOverview(buildClassOverview(exam, gradedStudentIds, graded,
                passLine, excellentLine, paper.totalScore()));
        response.setQuestionStats(buildQuestionStats(questions, metrics));
        TagDimension tagDimension = buildTagWeakness(questions, metrics);
        response.setTagWeakness(tagDimension.items());
        response.setHasTagDimension(tagDimension.hasDimension());
        response.setFocusList(buildFocusList(graded, gradedStudentIds, passLine));
        return response;
    }

    /** 及格线 = 总分 ×60%（HALF_UP 取整到整数分）。总分不可得（null/非正）→ fail-fast。 */
    private static int passLineOf(BigDecimal total) {
        return fractionOf(total, PASS_RATE);
    }

    /** 优秀线 = 总分 ×90%（HALF_UP 取整到整数分）。总分不可得（null/非正）→ fail-fast。 */
    private static int excellentLineOf(BigDecimal total) {
        return fractionOf(total, EXCELLENT_RATE);
    }

    /** 总分 × 比例，HALF_UP 取整到整数分；总分不可得（null/≤0）明确报错，不回退默认值。 */
    private static int fractionOf(BigDecimal total, BigDecimal rate) {
        if (total == null || total.signum() <= 0) {
            throw new BusinessException(ResponseCode.INTERNAL_ERROR,
                    "无法取到试卷总分，无法派生及格线/优秀线");
        }
        return total.multiply(rate).setScale(0, RoundingMode.HALF_UP).intValue();
    }

    // ==================== 班级概览 ====================

    private ExamAnalysisReportResponse.ClassOverview buildClassOverview(Exam exam,
                                                                        List<Integer> gradedStudentIds,
                                                                        List<GradingSubmission> graded,
                                                                        int passLine,
                                                                        int excellentLine,
                                                                        BigDecimal totalScore) {
        ExamAnalysisReportResponse.ClassOverview overview = new ExamAnalysisReportResponse.ClassOverview();

        // add-exam-reservation 应考口径分流：预约制考试按有效预约名单计应考（与缺考域 exam_absence 同源，
        // 不出现两套缺席数）；非预约制维持既有班级名单口径（ClassService.listStudentIds 仍是唯一班级名单入口）。
        List<Long> expectedIds;
        if (isReservationRequired(exam)) {
            expectedIds = reservationService.listReservedStudentIds(exam.getId());
        } else {
            expectedIds = exam.getClassId() == null
                    ? List.of()
                    : classService.listStudentIds(exam.getClassId());
        }
        overview.setExpectedCount(expectedIds.size());

        // 缺席 = 应考 − 有答卷（沿用缺考域 AbsenceService 口径：应考名单 − 有答卷者）
        int absent = 0;
        if (!expectedIds.isEmpty()) {
            // 有答卷者集合（Set 语义查重 O(1)，缺席计数口径与既有 List 判重逐字一致）
            Set<Long> submittedIds = submissionMapper.selectList(Wrappers.<ExamSubmission>lambdaQuery()
                            .select(ExamSubmission::getStudentId)
                            .eq(ExamSubmission::getExamId, exam.getId())
                            .in(ExamSubmission::getStudentId, expectedIds)).stream()
                    .map(ExamSubmission::getStudentId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
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
        overview.setPassRate(passRateOf(totals, passLine));
        overview.setScoreBands(scoreBandsOf(totals, passLine, excellentLine, totalScore));
        return overview;
    }

    private static BigDecimal averageOf(List<BigDecimal> totals) {
        if (totals.isEmpty()) {
            return null;
        }
        BigDecimal sum = totals.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(totals.size()), 1, RoundingMode.HALF_UP);
    }

    private static Double passRateOf(List<BigDecimal> totals, int passLine) {
        if (totals.isEmpty()) {
            return null;
        }
        long pass = totals.stream().filter(t -> t.compareTo(BigDecimal.valueOf(passLine)) >= 0).count();
        return (double) pass / totals.size();
    }

    /**
     * 分数段（5 段制维持，切点由总分百分比派生，fix-analysis-report-pass-line）：
     * 切点 = 总分 ×{@literal 60%/70%/80%/90%}（HALF_UP 取整）。100 分制下恒为
     * 0-59/60-69/70-79/80-89/90-100（与既有输出逐字一致，零回归）。50 分制下为
     * 0-29/30-34/35-39/40-44/45-50；150 分制下为 0-89/90-104/105-119/120-134/135-150
     * （最外切点恰为及格线/优秀线）。
     */
    private static List<ExamAnalysisReportResponse.ScoreBandItem> scoreBandsOf(
            List<BigDecimal> totals, int passLine, int excellentLine, BigDecimal totalScore) {
        // 5 个切点阈值：60%/70%/80%/90% 的整数分（首/末切点即及格线/优秀线）
        int[] thresholds = {
                passLine,
                fractionOf(totalScore, new BigDecimal("0.70")),
                fractionOf(totalScore, new BigDecimal("0.80")),
                excellentLine
        };
        int[] bands = {0, 0, 0, 0, 0};
        for (BigDecimal total : totals) {
            if (total == null) {
                continue;
            }
            int score = total.setScale(0, RoundingMode.HALF_UP).intValue();
            if (score < thresholds[0]) {
                bands[0]++;
            } else if (score < thresholds[1]) {
                bands[1]++;
            } else if (score < thresholds[2]) {
                bands[2]++;
            } else if (score < thresholds[3]) {
                bands[3]++;
            } else {
                bands[4]++;
            }
        }
        return List.of(
                new ExamAnalysisReportResponse.ScoreBandItem("0-" + (thresholds[0] - 1), bands[0]),
                new ExamAnalysisReportResponse.ScoreBandItem(
                        thresholds[0] + "-" + (thresholds[1] - 1), bands[1]),
                new ExamAnalysisReportResponse.ScoreBandItem(
                        thresholds[1] + "-" + (thresholds[2] - 1), bands[2]),
                new ExamAnalysisReportResponse.ScoreBandItem(
                        thresholds[2] + "-" + (thresholds[3] - 1), bands[3]),
                new ExamAnalysisReportResponse.ScoreBandItem(
                        thresholds[3] + "-" + totalScore.setScale(0, RoundingMode.HALF_UP), bands[4]));
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
            List<ExamAnalysisReportResponse.OptionDistributionItem> optionDistribution =
                    metric.optionDistribution() == null
                            ? List.of()
                            : metric.optionDistribution().stream()
                                    .map(d -> new ExamAnalysisReportResponse.OptionDistributionItem(
                                            d.option(), d.count(), d.rate()))
                                    .toList();
            items.add(new ExamAnalysisReportResponse.QuestionStatItem(
                    question.number(),
                    labelOfType(question.type()),
                    question.score(),
                    average,
                    scoreRate,
                    correctRate,
                    metric.discrimination(),
                    metric.answeredCount(),
                    optionDistribution));
        }
        return items;
    }

    private static String labelOfType(QuestionType type) {
        return switch (type) {
            case SINGLE -> "单选";
            case MULTIPLE -> "多选";
            case JUDGE -> "判断";
            case SHORT_ANSWER -> "简答";
            case FILL_BLANK -> "填空";
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
            List<GradingSubmission> graded, List<Integer> gradedStudentIds, int passLine) {
        if (gradedStudentIds.isEmpty()) {
            return List.of();
        }
        List<GradingSubmission> below = graded.stream()
                .filter(g -> g.getTotalScore() != null)
                .filter(g -> g.getTotalScore().compareTo(BigDecimal.valueOf(passLine)) < 0)
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

    /** 已汇总（GRADED）答卷列表（实考口径，与导出处 path 同源）。只投影消费列 studentId/totalScore；
     *  消费列若增列须同步扩投影（常驻护栏 AnalysisReportGradedProjectionGuardTest 防漂移）。 */
    private List<GradingSubmission> listGraded(Long examId) {
        return gradingSubmissionMapper.selectList(
                Wrappers.<GradingSubmission>lambdaQuery()
                        .eq(GradingSubmission::getExamId, examId)
                        .eq(GradingSubmission::getStatus, ExamSubmission.STATUS_GRADED)
                        .isNotNull(GradingSubmission::getTotalScore)
                        .select(GradingSubmission::getStudentId, GradingSubmission::getTotalScore));
    }
}
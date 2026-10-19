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
import com.exam.score.support.ExcelSheetWriter;
import com.exam.score.support.QuestionMetricsEvaluator;
import com.exam.submission.entity.ExamSubmission;
import com.exam.submission.mapper.ExamSubmissionMapper;
import com.exam.user.entity.User;
import com.exam.user.mapper.UserMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
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

    /**
     * 逐题 CTT 指标快照（IRT 标定的数据源，add-irt-adaptive-testing）：
     * 与 {@link #buildReport} 完全同源（同一 {@code ScoreExportService.aggregateQuestionStats}），
     * 只读、不改写任何数据。考试不存在或尚未达已判分状态返回空列表——标定方按"无样本"处理，
     * 不把"报告不可看"的语义泄漏成标定失败。
     */
    public List<ScoreExportService.QuestionStatMetric> questionMetrics(Long examId) {
        if (examId == null) {
            return List.of();
        }
        Exam exam = examMapper.selectById(examId);
        if (exam == null || exam.getStatus() == null || exam.getStatus() < Exam.STATUS_GRADED) {
            return List.of();
        }
        GradingPaper paper = paperReader.readByExamId(examId);
        return scoreExportService.aggregateQuestionStats(examId, paper, paper.questions());
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
            // 测量学诊断（add-exam-question-discrimination）：难度取报告口径平均分（1 位小数）
            // 与满分同源相除；五档评级/诊断建议由评估器统一派生，Excel 与 JSON 共用同源口径
            Double difficulty = QuestionMetricsEvaluator.difficultyOf(average, max, metric.answeredCount());
            ExamAnalysisReportResponse.QuestionStatItem item =
                    new ExamAnalysisReportResponse.QuestionStatItem(
                            question.number(),
                            labelOfType(question.type()),
                            question.score(),
                            average,
                            scoreRate,
                            correctRate,
                            metric.discrimination(),
                            metric.answeredCount(),
                            optionDistribution);
            item.setDifficulty(difficulty);
            item.setDifficultyLevel(QuestionMetricsEvaluator.difficultyLevelOf(difficulty));
            item.setDiscriminationLevel(
                    QuestionMetricsEvaluator.discriminationLevelOf(metric.discrimination()));
            item.setQualityEvaluation(
                    QuestionMetricsEvaluator.qualityEvaluationOf(metric.discrimination(), difficulty));
            items.add(item);
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

    // ==================== 多 Sheet Excel 导出（add-exam-analysis-export） ====================

    /**
     * 分析报告 Excel 导出（4 工作表：班级概览与分数段/逐题指标/知识点薄弱/学生关注名单）。
     * 数据面完全取自 {@link #buildReport}（口径同源，导出层零统计公式，红线第 4 条）；
     * 经 {@link ExcelSheetWriter#withStreamingWorkbook} SXSSF 流式写（窗口 100 行防 OOM）；
     * 数据派生的文本单元格一律过 {@link ExcelSheetWriter#sanitizeFormula}（CWE-1236 公式/DDE 注入）。
     */
    public byte[] exportReport(Long examId) {
        ExamAnalysisReportResponse report = buildReport(examId);
        return ExcelSheetWriter.withStreamingWorkbook(workbook -> {
            writeOverviewSheet(workbook, report);
            writeQuestionSheet(workbook, report.getQuestionStats());
            writeTagSheet(workbook, report);
            writeFocusSheet(workbook, report.getFocusList());
        });
    }

    /** Sheet 1「班级概览与分数段」：概览指标键值区（行 0-7）+ 空行 + 五段分布表（行 9 起）。 */
    private static void writeOverviewSheet(SXSSFWorkbook workbook, ExamAnalysisReportResponse report) {
        SXSSFSheet sheet = ExcelSheetWriter.createSheet(workbook, "班级概览与分数段");
        ExamAnalysisReportResponse.ClassOverview overview = report.getClassOverview();
        ExcelSheetWriter.writeHeader(sheet, "指标", "值");
        List<Object[]> metrics = List.of(
                new Object[]{"应考人数", overview.getExpectedCount()},
                new Object[]{"实考人数", overview.getActualCount()},
                new Object[]{"缺席人数", overview.getAbsenceCount()},
                new Object[]{"及格线", report.getPassLine()},
                new Object[]{"优秀线", report.getExcellentLine()},
                new Object[]{"平均分", overview.getAverageScore() == null
                        ? "-" : overview.getAverageScore().doubleValue()},
                new Object[]{"及格率", percentText(overview.getPassRate())});
        int rowIndex = 1;
        for (Object[] metric : metrics) {
            Row row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue((String) metric[0]);
            setCell(row.createCell(1), metric[1]);
        }
        rowIndex++;   // 空行分隔概览与分数段小节（行 8 不创建，即留空）
        ExcelSheetWriter.writeHeaderAt(sheet, rowIndex++, "分数段", "人数", "占比");
        int actual = overview.getActualCount();
        for (ExamAnalysisReportResponse.ScoreBandItem band : overview.getScoreBands()) {
            Row row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(ExcelSheetWriter.sanitizeFormula(band.getBand()));
            row.createCell(1).setCellValue(band.getCount());
            row.createCell(2).setCellValue(actual == 0
                    ? "-" : percentText((double) band.getCount() / actual));
        }
        ExcelSheetWriter.autoSize(sheet, 3);
    }

    /** Sheet 2「逐题指标」：口径同 buildReport.questionStats（null 语义照搬：「样本不足」/「-」）；
     *  测量学诊断 4 列（难度系数/难度等级/区分度等级/命题建议）由评估器同源派生
     *  （add-exam-question-discrimination），文本列一律过 sanitizeFormula。 */
    private static void writeQuestionSheet(SXSSFWorkbook workbook,
                                           List<ExamAnalysisReportResponse.QuestionStatItem> items) {
        SXSSFSheet sheet = ExcelSheetWriter.createSheet(workbook, "逐题指标");
        ExcelSheetWriter.writeHeader(sheet, "题号", "题型", "满分", "平均分", "得分率", "答对率",
                "区分度", "作答人数", "选项分布", "难度系数", "难度等级", "区分度等级", "命题建议");
        int rowIndex = 1;
        for (ExamAnalysisReportResponse.QuestionStatItem item : items) {
            Row row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(item.getOrder());
            row.createCell(1).setCellValue(ExcelSheetWriter.sanitizeFormula(item.getType()));
            setCell(row.createCell(2), item.getFullScore() == null ? 0 : item.getFullScore());
            setCell(row.createCell(3), item.getAverageScore() == null ? 0 : item.getAverageScore());
            setCell(row.createCell(4), item.getScoreRate() == null ? "-" : item.getScoreRate());
            setCell(row.createCell(5), item.getCorrectRate() == null ? "-" : item.getCorrectRate());
            setCell(row.createCell(6), item.getDiscrimination() == null ? "样本不足" : item.getDiscrimination());
            row.createCell(7).setCellValue(item.getAnsweredCount());
            row.createCell(8).setCellValue(
                    ExcelSheetWriter.sanitizeFormula(optionDistributionText(item.getOptionDistribution())));
            setCell(row.createCell(9), item.getDifficulty() == null ? "-" : item.getDifficulty());
            row.createCell(10).setCellValue(ExcelSheetWriter.sanitizeFormula(
                    item.getDifficultyLevel() == null ? "-" : item.getDifficultyLevel()));
            row.createCell(11).setCellValue(ExcelSheetWriter.sanitizeFormula(
                    item.getDiscriminationLevel() == null ? "-" : item.getDiscriminationLevel()));
            row.createCell(12).setCellValue(ExcelSheetWriter.sanitizeFormula(item.getQualityEvaluation() == null ? "-" : item.getQualityEvaluation()));
        }
        ExcelSheetWriter.autoSize(sheet, 13);
    }

    /** Sheet 3「知识点薄弱」：无标签维度时单行提示（与页面同款文案，优雅降级非错误）。 */
    private static void writeTagSheet(SXSSFWorkbook workbook, ExamAnalysisReportResponse report) {
        SXSSFSheet sheet = ExcelSheetWriter.createSheet(workbook, "知识点薄弱");
        ExcelSheetWriter.writeHeader(sheet, "知识点名称", "得分率");
        int rowIndex = 1;
        if (!report.isHasTagDimension()) {
            sheet.createRow(rowIndex).createCell(0).setCellValue("题库未打知识点标签");
            return;
        }
        for (ExamAnalysisReportResponse.TagWeaknessItem item : report.getTagWeakness()) {
            Row row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(ExcelSheetWriter.sanitizeFormula(item.getTagName()));
            row.createCell(1).setCellValue(item.getScoreRate() == null ? "-" : percentText(item.getScoreRate()));
        }
        ExcelSheetWriter.autoSize(sheet, 2);
    }

    /** Sheet 4「学生关注名单」：低于及格线学生（总分升序），空名单时单行提示。 */
    private static void writeFocusSheet(SXSSFWorkbook workbook,
                                        List<ExamAnalysisReportResponse.FocusStudentItem> items) {
        SXSSFSheet sheet = ExcelSheetWriter.createSheet(workbook, "学生关注名单");
        ExcelSheetWriter.writeHeader(sheet, "学号", "姓名", "总分");
        int rowIndex = 1;
        if (items.isEmpty()) {
            sheet.createRow(rowIndex).createCell(0).setCellValue("暂无低于及格线学生");
            return;
        }
        for (ExamAnalysisReportResponse.FocusStudentItem item : items) {
            Row row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(ExcelSheetWriter.sanitizeFormula(item.getUsername()));
            row.createCell(1).setCellValue(ExcelSheetWriter.sanitizeFormula(item.getStudentName()));
            setCell(row.createCell(2), item.getTotalScore() == null ? 0 : item.getTotalScore());
        }
        ExcelSheetWriter.autoSize(sheet, 3);
    }

    /** POI 的 setCellValue 无 Object 重载（String/Double 混合三元无法推断）：数值走 numeric，其余走文本。 */
    private static void setCell(Cell cell, Object value) {
        if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
        } else {
            cell.setCellValue(String.valueOf(value));
        }
    }

    /** 0~1 比例 → 一位小数百分比文本；null → 「-」（无数据语义，非 0%）。 */
    private static String percentText(Double rate) {
        if (rate == null) {
            return "-";
        }
        return BigDecimal.valueOf(rate * 100).setScale(1, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    /** 选项分布 → 与题目统计导出同格式文本（A:2(67%) B:1(33%)…），空分布 → 空串。 */
    private static String optionDistributionText(
            List<ExamAnalysisReportResponse.OptionDistributionItem> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ExamAnalysisReportResponse.OptionDistributionItem item : items) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(item.getOption()).append(':').append(item.getCount()).append('(')
                    .append(Math.round(item.getRate() * 100)).append("%)");
        }
        return sb.toString();
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
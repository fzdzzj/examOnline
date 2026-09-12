package com.exam.score.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.auth.security.OwnershipGuard;
import com.exam.auth.security.SecurityUtil;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.grading.model.GradingPaper;
import com.exam.grading.model.GradingQuestion;
import com.exam.grading.support.GradingPaperReader;
import com.exam.question.entity.QuestionType;
import com.exam.score.support.QuestionScoreResolver;
import com.exam.submission.entity.ExamSubmission;
import com.exam.user.entity.User;
import com.exam.user.mapper.UserMapper;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 成绩导出服务（spec「成绩导出」需求，docs/需求决策记录.md §8.4 四种导出 + §12.10 打印 PDF）：
 *
 * <p>防 OOM 双保险（spec「大数据量不溢出」场景）：
 * <ol>
 *   <li>SXSSF 流式写：滑动窗口只保留最近 100 行在内存，其余行压缩落临时文件，
 *       工作簿内存与总行数无关（XSSF 会把全部单元格堆在堆内，数千行即可能 OOM）；</li>
 *   <li>分批查询：答卷按 id 升序每批 {@value #PAGE_SIZE} 条分页读取
 *       （WHERE id &gt; lastId ORDER BY id LIMIT n，走主键索引），单批内存可控。</li>
 * </ol>
 * 最终字节一次性入 ByteArrayOutputStream 响应：万行量级文件仅数百 KB，
 * 若后续扩展到十万行级，可平滑替换为 StreamingResponseBody 边算边写。
 *
 * <p>口径：排名/逐题得分/统计与发布预览共用 RankCalculator 与判分引擎
 * （QuestionScoreResolver 现场重算客观题），多处展示永远一致。
 */
@Slf4j
@Service
public class ScoreExportService {

    /** 分批查询批大小：与批量落库批大小同量级，平衡往返次数与单批内存 */
    static final int PAGE_SIZE = 500;

    /** SXSSF 滑动窗口：内存中保留的行数，超出自动刷到临时文件 */
    private static final int SXSSF_WINDOW = 100;

    /** 区分度高低分组比例（教育测量学惯例：前后 27%） */
    private static final BigDecimal DISCRIMINATION_GROUP_RATIO = new BigDecimal("0.27");

    private final ExamMapper examMapper;
    private final GradingSubmissionMapper gradingSubmissionMapper;
    private final GradingPaperReader paperReader;
    private final QuestionScoreResolver scoreResolver;
    private final UserMapper userMapper;
    private final RankCalculator rankCalculator;

    public ScoreExportService(ExamMapper examMapper,
                              GradingSubmissionMapper gradingSubmissionMapper,
                              GradingPaperReader paperReader,
                              QuestionScoreResolver scoreResolver,
                              UserMapper userMapper,
                              RankCalculator rankCalculator) {
        this.examMapper = examMapper;
        this.gradingSubmissionMapper = gradingSubmissionMapper;
        this.paperReader = paperReader;
        this.scoreResolver = scoreResolver;
        this.userMapper = userMapper;
        this.rankCalculator = rankCalculator;
    }

    // ==================== 四种导出 ====================

    /** 全班成绩单：学号/姓名/客观/主观/总分/排名/批改状态。 */
    public byte[] exportClassSheet(Long examId) {
        Exam exam = assertExportable(examId);
        Map<Long, Integer> rankBySubmission = rankBySubmissionId(examId);

        return withStreamingWorkbook(workbook -> {
            SXSSFSheet sheet = createSheet(workbook, "全班成绩单");
            writeHeader(sheet, "学号", "姓名", "客观题得分", "主观题得分", "总分", "排名", "批改状态");
            int[] rowIndex = {1};
            forEachSubmissionPage(examId, page -> {
                Map<Long, User> users = loadUsers(page);
                for (GradingSubmission submission : page) {
                    Row row = sheet.createRow(rowIndex[0]++);
                    User user = users.get(submission.getStudentId());
                    row.createCell(0).setCellValue(user == null ? "" : user.getUsername());
                    row.createCell(1).setCellValue(user == null ? "未知学生" : user.getName());
                    row.createCell(2).setCellValue(decimal(submission.getObjectiveScore()));
                    row.createCell(3).setCellValue(decimal(submission.getSubjectiveScore()));
                    row.createCell(4).setCellValue(decimal(submission.getTotalScore()));
                    row.createCell(5).setCellValue(rankBySubmission.getOrDefault(submission.getId(), 0));
                    row.createCell(6).setCellValue(isPartial(submission) ? "部分批改" : "正常");
                }
            });
            autoSize(sheet, 7);
        });
    }

    /** 逐题得分明细：每生一行，逐题得分列 + 总分列。 */
    public byte[] exportQuestionDetail(Long examId) {
        Exam exam = assertExportable(examId);
        GradingPaper paper = paperReader.readByExamId(examId);
        List<GradingQuestion> questions = paper.questions();

        return withStreamingWorkbook(workbook -> {
            SXSSFSheet sheet = createSheet(workbook, "逐题得分明细");
            Object[] headers = new Object[questions.size() + 3];
            headers[0] = "学号";
            headers[1] = "姓名";
            for (int i = 0; i < questions.size(); i++) {
                GradingQuestion question = questions.get(i);
                headers[i + 2] = "第" + question.number() + "题(" + question.score() + "分)";
            }
            headers[headers.length - 1] = "总分";
            writeHeader(sheet, headers);

            int[] rowIndex = {1};
            forEachSubmissionPage(examId, page -> {
                Map<Long, User> users = loadUsers(page);
                Map<Long, Map<Long, QuestionScoreResolver.ResolvedQuestionScore>> resolved =
                        resolveQuietly(examId, page, paper);
                for (GradingSubmission submission : page) {
                    Map<Long, QuestionScoreResolver.ResolvedQuestionScore> perQuestion =
                            resolved.get(submission.getId());
                    if (perQuestion == null) {
                        continue;   // 答案损坏且无法解析的答卷跳过（已 warn 日志）
                    }
                    Row row = sheet.createRow(rowIndex[0]++);
                    User user = users.get(submission.getStudentId());
                    row.createCell(0).setCellValue(user == null ? "" : user.getUsername());
                    row.createCell(1).setCellValue(user == null ? "未知学生" : user.getName());
                    int col = 2;
                    for (GradingQuestion question : questions) {
                        QuestionScoreResolver.ResolvedQuestionScore score = perQuestion.get(question.questionId());
                        // 未批主观题显示"未批"而非 0 分，避免教师误读（总分中确按 0 计，§7.5）
                        row.createCell(col++).setCellValue(score.graded() ? decimal(score.score()) : "未批");
                    }
                    row.createCell(headers.length - 1).setCellValue(decimal(submission.getTotalScore()));
                }
            });
            autoSize(sheet, Math.min(headers.length, 20));
        });
    }

    /** 题目统计表：题号/题型/满分/平均分/得分率/答对率/区分度（前后 27% 高低分组法）。 */
    public byte[] exportQuestionStats(Long examId) {
        Exam exam = assertExportable(examId);
        GradingPaper paper = paperReader.readByExamId(examId);
        List<GradingQuestion> questions = paper.questions();

        // 聚合器：逐题得分/满分计数 + 区分度所需的 (总分, 该题得分) 对。
        // 内存量级：5000 生 × 30 题 × 16B ≈ 2.4MB，可控；再大可改两遍扫描。
        Map<Long, double[]> sums = new HashMap<>();
        Map<Long, int[]> counts = new HashMap<>();
        Map<Long, List<double[]>> pairs = new HashMap<>();

        forEachSubmissionPage(examId, page -> {
            Map<Long, Map<Long, QuestionScoreResolver.ResolvedQuestionScore>> resolved =
                    resolveQuietly(examId, page, paper);
            for (GradingSubmission submission : page) {
                Map<Long, QuestionScoreResolver.ResolvedQuestionScore> perQuestion =
                        resolved.get(submission.getId());
                if (perQuestion == null) {
                    continue;
                }
                double total = submission.getTotalScore() == null
                        ? 0 : submission.getTotalScore().doubleValue();
                for (GradingQuestion question : questions) {
                    QuestionScoreResolver.ResolvedQuestionScore score = perQuestion.get(question.questionId());
                    if (score == null) {
                        continue;
                    }
                    sums.computeIfAbsent(question.questionId(), k -> new double[1])[0]
                            += score.score().doubleValue();
                    counts.computeIfAbsent(question.questionId(), k -> new int[2])[0]++;
                    if (score.fullScore()) {
                        counts.get(question.questionId())[1]++;
                    }
                    pairs.computeIfAbsent(question.questionId(), k -> new ArrayList<>())
                            .add(new double[]{total, score.score().doubleValue()});
                }
            }
        });

        return withStreamingWorkbook(workbook -> {
            SXSSFSheet sheet = createSheet(workbook, "题目统计");
            writeHeader(sheet, "题号", "题型", "题干", "满分", "平均分", "得分率", "答对率(满分率)", "区分度", "作答人数");
            int rowIndex = 1;
            for (GradingQuestion question : questions) {
                Row row = sheet.createRow(rowIndex++);
                int[] questionCounts = counts.getOrDefault(question.questionId(), new int[2]);
                int n = questionCounts[0];
                int full = questionCounts[1];
                double sum = sums.getOrDefault(question.questionId(), new double[1])[0];
                double max = question.score().doubleValue();
                row.createCell(0).setCellValue(question.number());
                row.createCell(1).setCellValue(labelOf(question.type()));
                row.createCell(2).setCellValue(question.content());
                row.createCell(3).setCellValue(decimal(question.score()));
                row.createCell(4).setCellValue(n == 0 ? "-" : round2(sum / n));
                row.createCell(5).setCellValue(n == 0 || max == 0 ? "-" : percent(sum / n / max));
                row.createCell(6).setCellValue(n == 0 ? "-" : percent((double) full / n));
                row.createCell(7).setCellValue(n == 0 ? "-" : discrimination(
                        pairs.getOrDefault(question.questionId(), List.of()), max));
                row.createCell(8).setCellValue(n);
            }
            autoSize(sheet, 9);
        });
    }

    /** 个人成绩单 Excel：学生信息 + 逐题得分/评语 + 判分依据。 */
    public byte[] exportPersonalSheet(Long examId, Long studentId) {
        Exam exam = assertExportable(examId);
        PersonalReport report = loadPersonalReport(exam, studentId);

        return withStreamingWorkbook(workbook -> {
            SXSSFSheet sheet = createSheet(workbook, "个人成绩单");
            CellStyle bold = boldStyle(workbook);

            Row title = sheet.createRow(0);
            Cell titleCell = title.createCell(0);
            titleCell.setCellValue(exam.getTitle() + " 个人成绩单");
            titleCell.setCellStyle(bold);

            Row info = sheet.createRow(1);
            info.createCell(0).setCellValue("姓名：" + report.studentName()
                    + "    学号：" + report.username());
            info.createCell(1).setCellValue("客观题：" + decimal(report.objective())
                    + "    主观题：" + decimal(report.subjective())
                    + "    总分：" + decimal(report.total()));
            info.createCell(2).setCellValue("排名：第 " + report.rank() + " 名    批改状态："
                    + (report.partial() ? "部分批改" : "正常"));

            int rowIndex = 3;
            writeHeaderAt(sheet, rowIndex++, "题号", "题型", "题干", "满分", "得分", "批改状态", "评语/判分依据");
            for (GradingQuestion question : report.questions()) {
                QuestionScoreResolver.ResolvedQuestionScore score =
                        report.scores().get(question.questionId());
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(question.number());
                row.createCell(1).setCellValue(labelOf(question.type()));
                row.createCell(2).setCellValue(question.content());
                row.createCell(3).setCellValue(decimal(question.score()));
                row.createCell(4).setCellValue(score.graded() ? decimal(score.score()) : "未批");
                row.createCell(5).setCellValue(score.graded() ? "已批" : "未批");
                String note = score.comment() != null ? score.comment() : score.detail();
                row.createCell(6).setCellValue(note == null ? "" : note);
            }
            autoSize(sheet, 7);
        });
    }

    /**
     * 个人成绩单打印版 PDF（§12.10：题头 + 成绩表 + 签名栏）：
     * 中文用 STSong-Light（fonts-extra 提供 CMap，不内嵌字体，文件小；
     * 阅读器缺字体包时可能回退，如需绝对兼容可换内嵌 TTF）。
     */
    public byte[] exportPersonalPdf(Long examId, Long studentId) {
        Exam exam = assertExportable(examId);
        PersonalReport report = loadPersonalReport(exam, studentId);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            BaseFont baseFont = BaseFont.createFont("STSong-Light", "UniGB-UCS2-H", BaseFont.NOT_EMBEDDED);
            Font titleFont = new Font(baseFont, 18, Font.BOLD);
            Font infoFont = new Font(baseFont, 11);
            Font headerFont = new Font(baseFont, 11, Font.BOLD);
            Font cellFont = new Font(baseFont, 10);

            Document document = new Document(PageSize.A4, 36, 36, 48, 48);
            PdfWriter.getInstance(document, out);
            document.open();

            Paragraph title = new Paragraph(exam.getTitle() + " 个人成绩单", titleFont);
            title.setAlignment(Element.ALIGN_CENTER);
            document.add(title);

            Paragraph studentInfo = new Paragraph(
                    "姓名：" + report.studentName() + "    学号：" + report.username()
                            + "    总分：" + decimal(report.total())
                            + "    排名：第 " + report.rank() + " 名"
                            + (report.partial() ? "（部分批改，未批题目按 0 分计入）" : ""),
                    infoFont);
            studentInfo.setSpacingBefore(12);
            studentInfo.setSpacingAfter(12);
            document.add(studentInfo);

            PdfPTable table = new PdfPTable(new float[]{8, 12, 40, 12, 12, 16});
            table.setWidthPercentage(100);
            table.setHeaderRows(1);
            for (String header : new String[]{"题号", "题型", "题干", "满分", "得分", "批改状态"}) {
                PdfPCell cell = new PdfPCell(new Phrase(header, headerFont));
                cell.setBackgroundColor(new java.awt.Color(238, 238, 238));
                table.addCell(cell);
            }
            for (GradingQuestion question : report.questions()) {
                QuestionScoreResolver.ResolvedQuestionScore score =
                        report.scores().get(question.questionId());
                table.addCell(new PdfPCell(new Phrase(String.valueOf(question.number()), cellFont)));
                table.addCell(new PdfPCell(new Phrase(labelOf(question.type()), cellFont)));
                table.addCell(new PdfPCell(new Phrase(question.content(), cellFont)));
                table.addCell(new PdfPCell(new Phrase(decimal(question.score()), cellFont)));
                table.addCell(new PdfPCell(new Phrase(
                        score.graded() ? decimal(score.score()) : "未批", cellFont)));
                table.addCell(new PdfPCell(new Phrase(score.graded() ? "已批" : "未批", cellFont)));
            }
            document.add(table);

            Paragraph signature = new Paragraph(
                    "教师签名：________________    日期：________________", infoFont);
            signature.setSpacingBefore(40);
            document.add(signature);

            document.close();
            return out.toByteArray();
        } catch (Exception e) {
            log.error("个人成绩单 PDF 生成失败: exam={} student={}", examId, studentId, e);
            throw new BusinessException(ResponseCode.INTERNAL_ERROR, "PDF 生成失败");
        }
    }

    // ==================== 数据装配 ====================

    /** 个人成绩单装配结果。 */
    private record PersonalReport(String username, String studentName, BigDecimal objective,
                                  BigDecimal subjective, BigDecimal total, int rank, boolean partial,
                                  List<GradingQuestion> questions,
                                  Map<Long, QuestionScoreResolver.ResolvedQuestionScore> scores) {
    }

    private PersonalReport loadPersonalReport(Exam exam, Long studentId) {
        GradingPaper paper = paperReader.readByExamId(exam.getId());
        GradingSubmission submission = gradingSubmissionMapper.selectOne(
                Wrappers.<GradingSubmission>lambdaQuery()
                        .eq(GradingSubmission::getExamId, exam.getId())
                        .eq(GradingSubmission::getStudentId, studentId)
                        .eq(GradingSubmission::getStatus, ExamSubmission.STATUS_GRADED));
        if (submission == null || submission.getTotalScore() == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "该学生暂无已汇总成绩");
        }
        User user = userMapper.selectById(studentId);

        Map<Long, Integer> ranks = rankBySubmissionId(exam.getId());
        Map<Long, QuestionScoreResolver.ResolvedQuestionScore> scores =
                scoreResolver.resolve(submission, paper);
        return new PersonalReport(
                user == null ? "" : user.getUsername(),
                user == null ? "未知学生" : user.getName(),
                submission.getObjectiveScore(),
                submission.getSubjectiveScore(),
                submission.getTotalScore(),
                ranks.getOrDefault(submission.getId(), 0),
                isPartial(submission),
                paper.questions(),
                scores);
    }

    /** 全班排名：submissionId → 名次（与发布预览同一 RankCalculator 口径）。 */
    private Map<Long, Integer> rankBySubmissionId(Long examId) {
        // 轻量投影查询（仅 id/total），为排名提供全局视图；行数 = 交卷数，内存可控
        List<GradingSubmission> graded = gradingSubmissionMapper.selectList(
                Wrappers.<GradingSubmission>lambdaQuery()
                        .eq(GradingSubmission::getExamId, examId)
                        .eq(GradingSubmission::getStatus, ExamSubmission.STATUS_GRADED)
                        .isNotNull(GradingSubmission::getTotalScore)
                        .select(GradingSubmission::getId, GradingSubmission::getTotalScore));
        int[] ranks = rankCalculator.rank(graded.stream()
                .map(GradingSubmission::getTotalScore).toList());
        Map<Long, Integer> rankBySubmission = new LinkedHashMap<>();
        for (int i = 0; i < graded.size(); i++) {
            rankBySubmission.put(graded.get(i).getId(), ranks[i]);
        }
        return rankBySubmission;
    }

    /**
     * 分批迭代已汇总答卷：id 升序 + WHERE id &gt; lastId 走主键索引，
     * 单批 {@value #PAGE_SIZE} 条，导出全程不持有全量答卷。
     */
    private void forEachSubmissionPage(Long examId, Consumer<List<GradingSubmission>> pageConsumer) {
        long lastId = 0;
        while (true) {
            List<GradingSubmission> page = gradingSubmissionMapper.selectList(
                    Wrappers.<GradingSubmission>lambdaQuery()
                            .eq(GradingSubmission::getExamId, examId)
                            .eq(GradingSubmission::getStatus, ExamSubmission.STATUS_GRADED)
                            .gt(GradingSubmission::getId, lastId)
                            .orderByAsc(GradingSubmission::getId)
                            .last("LIMIT " + PAGE_SIZE));
            if (page.isEmpty()) {
                break;
            }
            pageConsumer.accept(page);
            lastId = page.get(page.size() - 1).getId();
            if (page.size() < PAGE_SIZE) {
                break;
            }
        }
    }

    /**
     * 逐题解析（整批）；个别答卷答案损坏时降级为逐份解析跳过坏卷，
     * 保证导出不因单份脏数据整体失败。
     */
    private Map<Long, Map<Long, QuestionScoreResolver.ResolvedQuestionScore>> resolveQuietly(
            Long examId, List<GradingSubmission> page, GradingPaper paper) {
        try {
            return scoreResolver.resolveBatch(page, paper);
        } catch (Exception e) {
            log.warn("逐题解析整批失败，降级逐份跳过坏卷: exam={} 原因={}", examId, e.getMessage());
        }
        Map<Long, Map<Long, QuestionScoreResolver.ResolvedQuestionScore>> result = new LinkedHashMap<>();
        for (GradingSubmission submission : page) {
            try {
                result.put(submission.getId(), scoreResolver.resolve(submission, paper));
            } catch (Exception e) {
                log.warn("答卷逐题解析失败，跳过: submission={} 原因={}", submission.getId(), e.getMessage());
            }
        }
        return result;
    }

    /** 区分度（前后 27% 高低分组法）：D = (高分组均分 - 低分组均分) / 满分，保留 2 位。 */
    private String discrimination(List<double[]> pairs, double max) {
        int n = pairs.size();
        int groupSize = (int) Math.floor(n * DISCRIMINATION_GROUP_RATIO.doubleValue());
        if (n < 4 || groupSize < 1) {
            return "样本不足";
        }
        List<double[]> sorted = new ArrayList<>(pairs);
        sorted.sort((a, b) -> Double.compare(b[0], a[0]));   // 按总分降序
        double highSum = 0;
        double lowSum = 0;
        for (int i = 0; i < groupSize; i++) {
            highSum += sorted.get(i)[1];
            lowSum += sorted.get(n - 1 - i)[1];
        }
        return round2((highSum / groupSize - lowSum / groupSize) / max);
    }

    // ==================== POI / 工具 ====================

    /** SXSSF 模板方法：创建滑动窗口工作簿 → 写入 → 单次序列化 → 清理临时文件。 */
    private byte[] withStreamingWorkbook(java.util.function.Consumer<SXSSFWorkbook> writer) {
        // SXSSF(100)：内存仅保留 100 行窗口，其余行压缩刷盘——防 OOM 的关键开关
        SXSSFWorkbook workbook = new SXSSFWorkbook(SXSSF_WINDOW);
        try {
            writer.accept(workbook);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessException(ResponseCode.INTERNAL_ERROR, "Excel 生成失败");
        } finally {
            // 必须显式 dispose：删除 SXSSF 刷盘产生的临时文件（否则堆积磁盘垃圾）
            workbook.dispose();
            try {
                workbook.close();
            } catch (IOException e) {
                log.warn("工作簿关闭异常", e);
            }
        }
    }

    /**
     * 建表并开启全列宽跟踪：SXSSF 的 autoSizeColumn 只能作用于已跟踪列，
     * 且跟踪必须在写行之前开启（滑动窗口外的行已刷盘，事后无法测量）。
     */
    private SXSSFSheet createSheet(SXSSFWorkbook workbook, String name) {
        SXSSFSheet sheet = workbook.createSheet(name);
        sheet.trackAllColumnsForAutoSizing();
        return sheet;
    }

    private void writeHeader(Sheet sheet, Object... headers) {
        writeHeaderAt(sheet, 0, headers);
    }

    private void writeHeaderAt(Sheet sheet, int rowIndex, Object... headers) {
        Row row = sheet.createRow(rowIndex);
        CellStyle headerStyle = headerStyle(sheet.getWorkbook());
        for (int i = 0; i < headers.length; i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(String.valueOf(headers[i]));
            cell.setCellStyle(headerStyle);
        }
    }

    private CellStyle headerStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        org.apache.poi.ss.usermodel.Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private CellStyle boldStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        org.apache.poi.ss.usermodel.Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    /** 列宽自适应（列数截断防止超宽表耗时过长）。 */
    private void autoSize(Sheet sheet, int columns) {
        for (int i = 0; i < columns; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    private Map<Long, User> loadUsers(List<GradingSubmission> page) {
        List<Long> studentIds = page.stream().map(GradingSubmission::getStudentId).distinct().toList();
        if (studentIds.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(studentIds).stream()
                .collect(java.util.stream.Collectors.toMap(User::getId,
                        java.util.function.Function.identity()));
    }

    private boolean isPartial(GradingSubmission submission) {
        return submission.getPartialGraded() != null && submission.getPartialGraded() == 1;
    }

    private Exam assertExportable(Long examId) {
        Exam exam = examMapper.selectById(examId);
        if (exam == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "考试不存在");
        }
        OwnershipGuard.assertOwner(exam.getCreatedBy(), SecurityUtil.getCurrentUser(), "考试");
        if (exam.getStatus() < Exam.STATUS_GRADED) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "成绩尚未汇总，请先执行汇总再导出");
        }
        return exam;
    }

    private String labelOf(QuestionType type) {
        return switch (type) {
            case SINGLE -> "单选";
            case MULTIPLE -> "多选";
            case JUDGE -> "判断";
            case SHORT_ANSWER -> "简答";
        };
    }

    /** 分值格式：1 位小数（与 DECIMAL(5,1) 口径一致），null 显示 "-"。 */
    private String decimal(BigDecimal value) {
        return value == null ? "-" : value.setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    private String round2(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String percent(double value) {
        return BigDecimal.valueOf(value * 100).setScale(1, RoundingMode.HALF_UP).toPlainString() + "%";
    }
}

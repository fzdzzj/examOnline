package com.exam.paper.service;

import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.paper.dto.PaperDetailResponse;
import com.exam.paper.dto.PaperQuestionItemResponse;
import com.exam.question.entity.Question;
import com.exam.question.entity.QuestionType;
import com.exam.question.mapper.QuestionMapper;
import com.lowagie.text.Chunk;
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
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 试卷标准纸质版 PDF 导出（add-paper-pdf-export，spec-delta question-bank）：
 *
 * <ul>
 *   <li>A4 排版：试卷卷头（标题/总分/题数/注意事项）、考生信息填空区（姓名/学号/班级/考场）、
 *       大题得分汇总表（一~五分题型 + 总分 + 评卷人）、按题型分组展示题目（题号/分值/题干/选项）；</li>
 *   <li>学生作答版（{@code withAnswer=false}）：填空题输出下划线横线、简答题预留作答空白框，
 *       不展示任何答案与解析；</li>
 *   <li>教师参考答案版（{@code withAnswer=true}）：每题后输出【参考答案与评分标准】，
 *       客观题标准答案、填空题可接受答案列表（| 分隔）、简答题参考答案与解析（analysis 非空时）；</li>
 *   <li>归属与权限门禁：经 {@link PaperService#detail} 的 owner 校验（非归属教师 403、不存在/软删 404）；</li>
 *   <li>全部写出文本先经 {@link #sanitizePdfText} 清洗（复用个人成绩单 PDF 的清洗口径，
 *       自包含实现避免跨能力域耦合）：STSong-Light 是 GB2312 映射字体（CMap 表长 65536），
 *       遇增补平面字符（Emoji/CJK 扩展 B）时 openpdf 直接越界中断导出，必须统一替换为 '?'。</li>
 * </ul>
 *
 * <p>零 DDL、零新依赖：直接复用既有 openpdf 依赖与 STSong-Light 中文字体配置。
 */
@Slf4j
@Service
public class PaperExportService {

    private final PaperService paperService;
    private final QuestionMapper questionMapper;

    public PaperExportService(PaperService paperService, QuestionMapper questionMapper) {
        this.paperService = paperService;
        this.questionMapper = questionMapper;
    }

    /** 题型分组顺序（单选、多选、判断、填空、简答）与汇总表列 一~五 一一对应。 */
    private static final Map<Integer, String> SECTION_BY_TYPE = Map.of(
            QuestionType.SINGLE.getCode(), "一",
            QuestionType.MULTIPLE.getCode(), "二",
            QuestionType.JUDGE.getCode(), "三",
            QuestionType.FILL_BLANK.getCode(), "四",
            QuestionType.SHORT_ANSWER.getCode(), "五");

    /**
     * 导出试卷 PDF 字节流。
     *
     * @param paperId   试卷 ID（detail 内部执行 owner 校验）
     * @param withAnswer true=教师参考答案版，false=学生作答版
     * @throws BusinessException 不存在 404 / 越权 403 / 生成失败 500
     */
    public byte[] exportPaperPdf(Long paperId, boolean withAnswer) {
        // 归属校验与题目数据一次性取自 detail：非 owner 且非 ADMIN 由 OwnershipGuard 拒绝（403）
        PaperDetailResponse detail = paperService.detail(paperId);
        Map<Long, String> analyses = loadAnalyses(detail);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            BaseFont baseFont = BaseFont.createFont("STSong-Light", "UniGB-UCS2-H", BaseFont.NOT_EMBEDDED);
            Font titleFont = new Font(baseFont, 18, Font.BOLD);
            Font sectionFont = new Font(baseFont, 12, Font.BOLD);
            Font bodyFont = new Font(baseFont, 10.5f);
            Font boldFont = new Font(baseFont, 10.5f, Font.BOLD);
            Font smallFont = new Font(baseFont, 9.5f);

            Document document = new Document(PageSize.A4, 45, 45, 48, 48);
            PdfWriter.getInstance(document, out);
            document.open();

            // ===== 卷头 =====
            Paragraph title = new Paragraph(sanitizePdfText(detail.getTitle()), titleFont);
            title.setAlignment(Element.ALIGN_CENTER);
            title.setSpacingAfter(6);
            document.add(title);

            Paragraph info = new Paragraph(sanitizePdfText(
                    "总分：" + decimal(detail.getTotalScore()) + " 分　　题数：" + detail.getQuestionCount()
                            + " 题　　注意事项：请在答题区域内作答，答案写在试卷上无效"), smallFont);
            info.setAlignment(Element.ALIGN_CENTER);
            info.setSpacingAfter(10);
            document.add(info);

            // ===== 考生信息填空区 =====
            Paragraph studentInfo = new Paragraph(sanitizePdfText(
                    "姓名：________________    学号：________________    班级：________________\n"
                            + "考场/座位号：________________    考试时间：120 分钟    试卷编号：________"),
                    bodyFont);
            studentInfo.setSpacingAfter(10);
            document.add(studentInfo);

            // ===== 大题得分汇总表 =====
            document.add(buildSummaryTable(detail, bodyFont, smallFont));

            // ===== 按题型分组展示题目 =====
            List<PaperQuestionItemResponse> items = detail.getQuestions();
            for (QuestionType type : List.of(QuestionType.SINGLE, QuestionType.MULTIPLE,
                    QuestionType.JUDGE, QuestionType.FILL_BLANK, QuestionType.SHORT_ANSWER)) {
                List<PaperQuestionItemResponse> group = items.stream()
                        .filter(it -> it.getQuestionType() != null && it.getQuestionType() == type.getCode())
                        .toList();
                if (group.isEmpty()) {
                    continue;
                }
                BigDecimal subtotal = group.stream()
                        .map(PaperQuestionItemResponse::getScore)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                Paragraph heading = new Paragraph(sanitizePdfText(
                        SECTION_BY_TYPE.get(type.getCode()) + "、" + type.getLabel() + "题（共 "
                                + group.size() + " 题，" + decimal(subtotal) + " 分）"), sectionFont);
                heading.setSpacingBefore(10);
                heading.setSpacingAfter(4);
                document.add(heading);
                for (PaperQuestionItemResponse item : group) {
                    addQuestion(document, item, analyses.get(item.getQuestionId()), withAnswer,
                            bodyFont, boldFont, smallFont);
                }
            }

            document.close();
            return out.toByteArray();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("试卷 PDF 导出失败: paperId={} withAnswer={}", paperId, withAnswer, e);
            throw new BusinessException(ResponseCode.INTERNAL_ERROR, "试卷 PDF 导出失败");
        }
    }

    // ==================== 排版 ====================

    /** 大题得分汇总表：题号 | 一 | 二 | 三 | 四 | 五 | 总分 | 评卷人，第二行填各题型小计与总分。 */
    private PdfPTable buildSummaryTable(PaperDetailResponse detail, Font bodyFont, Font smallFont) {
        PdfPTable table = new PdfPTable(8);
        table.setWidthPercentage(100);
        table.setSpacingAfter(6);
        for (String header : new String[]{"题号", "一", "二", "三", "四", "五", "总分", "评卷人"}) {
            PdfPCell cell = new PdfPCell(new Phrase(sanitizePdfText(header), bodyFont));
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            cell.setBackgroundColor(new java.awt.Color(238, 238, 238));
            table.addCell(cell);
        }
        table.addCell(new PdfPCell(new Phrase(sanitizePdfText("得分"), bodyFont)));
        for (QuestionType type : List.of(QuestionType.SINGLE, QuestionType.MULTIPLE,
                QuestionType.JUDGE, QuestionType.FILL_BLANK, QuestionType.SHORT_ANSWER)) {
            BigDecimal subtotal = detail.getQuestions().stream()
                    .filter(it -> it.getQuestionType() != null && it.getQuestionType() == type.getCode())
                    .map(PaperQuestionItemResponse::getScore)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            table.addCell(new PdfPCell(new Phrase(sanitizePdfText(decimal(subtotal)), smallFont)));
        }
        table.addCell(new PdfPCell(new Phrase(sanitizePdfText(decimal(detail.getTotalScore())), bodyFont)));
        table.addCell(new PdfPCell(new Phrase("", bodyFont)));
        return table;
    }

    /** 单题排版：题干（题号+分值）→ 选项 →（答案版）参考答案与评分标准 /（作答版）留白区域。 */
    private void addQuestion(Document document, PaperQuestionItemResponse item, String analysis,
                             boolean withAnswer, Font bodyFont, Font boldFont, Font smallFont) {
        QuestionType type = QuestionType.of(item.getQuestionType());
        String content = item.getContent() == null ? "（原题目已删除，仅保留题号与分值）" : item.getContent();

        Paragraph question = new Paragraph();
        question.add(new Chunk(sanitizePdfText(item.getNumber() + ".（" + decimal(item.getScore()) + " 分）"),
                boldFont));
        question.add(new Chunk("　" + sanitizePdfText(content), bodyFont));
        question.setSpacingBefore(6);
        document.add(question);

        // 选项（单选/多选）
        if ((type == QuestionType.SINGLE || type == QuestionType.MULTIPLE)
                && item.getChoices() != null && !item.getChoices().isEmpty()) {
            char letter = 'A';
            for (String choice : item.getChoices()) {
                Paragraph choiceParagraph = new Paragraph(sanitizePdfText("（" + letter + "）" + choice), bodyFont);
                choiceParagraph.setIndentationLeft(26);
                document.add(choiceParagraph);
                letter++;
            }
        }

        if (withAnswer) {
            Paragraph answerHead = new Paragraph(sanitizePdfText("【参考答案与评分标准】"), boldFont);
            answerHead.setIndentationLeft(26);
            answerHead.setSpacingBefore(2);
            document.add(answerHead);
            Paragraph answer = new Paragraph(sanitizePdfText(answerTextOf(item, type)), bodyFont);
            answer.setIndentationLeft(26);
            document.add(answer);
            if (type == QuestionType.SHORT_ANSWER && analysis != null && !analysis.isBlank()) {
                Paragraph analysisParagraph = new Paragraph(sanitizePdfText("解析：" + analysis), bodyFont);
                analysisParagraph.setIndentationLeft(26);
                document.add(analysisParagraph);
            }
        } else if (type == QuestionType.FILL_BLANK) {
            Paragraph blank = new Paragraph(sanitizePdfText("答：________________________________"), bodyFont);
            blank.setIndentationLeft(26);
            document.add(blank);
        } else if (type == QuestionType.SHORT_ANSWER) {
            // 简答留白：固定高度边框作答区
            PdfPTable box = new PdfPTable(1);
            PdfPCell cell = new PdfPCell(new Phrase("", bodyFont));
            cell.setFixedHeight(72);
            cell.setBorder(PdfPCell.BOX);
            box.setWidthPercentage(100);
            box.addCell(cell);
            document.add(box);
        }
    }

    /** 参考答案文本：客观题标准答案 / 填空可接受答案列表 / 简答参考答案。 */
    private String answerTextOf(PaperQuestionItemResponse item, QuestionType type) {
        String answer = item.getCorrectAnswer() == null ? "" : item.getCorrectAnswer().trim();
        if (type == QuestionType.FILL_BLANK) {
            String acceptable = Arrays.stream(answer.split("\\|"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.joining(" / "));
            return "可接受答案：" + (acceptable.isEmpty() ? "—" : acceptable);
        }
        if (type == QuestionType.MULTIPLE) {
            String pretty = Arrays.stream(answer.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.joining("、"));
            return "答案：" + (pretty.isEmpty() ? "—" : pretty);
        }
        if (type == QuestionType.JUDGE) {
            if ("T".equalsIgnoreCase(answer)) {
                return "答案：正确（T）";
            }
            if ("F".equalsIgnoreCase(answer)) {
                return "答案：错误（F）";
            }
        }
        return "答案：" + (answer.isEmpty() ? "—" : answer);
    }

    // ==================== 数据装配 ====================

    /** 批量加载题目解析（detail 响应不含 analysis 字段，仅答案版消费）。 */
    private Map<Long, String> loadAnalyses(PaperDetailResponse detail) {
        List<Long> ids = detail.getQuestions().stream()
                .filter(it -> !it.isQuestionDeleted())
                .map(PaperQuestionItemResponse::getQuestionId)
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return questionMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Question::getId,
                        q -> q.getAnalysis() == null ? "" : q.getAnalysis()));
    }

    // ==================== 字符清洗与格式化 ====================

    /**
     * PDF 文本安全清洗（与个人成绩单 PDF 同一口径，自包含实现）：
     * STSong-Light + UniGB-UCS2-H 是 GB2312 映射字体（CMap 表长 65536），
     * 遇增补平面字符（Emoji、CJK 扩展 B 生僻字，码点 &gt; 0xFFFF）时 openpdf 直接
     * {@code ArrayIndexOutOfBoundsException} 中断整个导出；不可见控制字符也会污染排版。
     * <ul>
     *   <li>增补平面码点与游离代理项 → '?'（通用占位符）；</li>
     *   <li>除换行/制表/回车外的 ISO 控制字符 → 空格。</li>
     * </ul>
     */
    public static String sanitizePdfText(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            int charCount = Character.charCount(cp);
            if (cp > 0xFFFF || (cp >= 0xD800 && cp <= 0xDFFF)) {
                sb.append('?');
            } else if (Character.isISOControl(cp) && cp != '\n' && cp != '\t' && cp != '\r') {
                sb.append(' ');
            } else {
                sb.appendCodePoint(cp);
            }
            i += charCount;
        }
        return sb.toString();
    }

    /** 分值格式：1 位小数（与 DECIMAL(5,1) 口径一致），null 显示 "-"。 */
    private static String decimal(BigDecimal value) {
        return value == null ? "-" : value.setScale(1, RoundingMode.HALF_UP).toPlainString();
    }
}

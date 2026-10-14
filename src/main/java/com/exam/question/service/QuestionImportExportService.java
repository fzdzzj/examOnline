package com.exam.question.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.auth.security.LoginUser;
import com.exam.auth.security.RoleHierarchy;
import com.exam.auth.security.SecurityUtil;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.question.dto.QuestionImportResult;
import com.exam.question.entity.Question;
import com.exam.question.entity.QuestionTag;
import com.exam.question.entity.QuestionType;
import com.exam.question.entity.Tag;
import com.exam.question.mapper.QuestionMapper;
import com.exam.question.mapper.QuestionTagMapper;
import com.exam.question.mapper.TagMapper;
import com.exam.question.support.AnswerNormalizer;
import com.exam.question.support.QuestionImportColumn;
import com.exam.score.support.ExcelSheetWriter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 题库 Excel 批量导入/导出/模板（add-question-import-export，决策 12.7 落地）。
 *
 * <ul>
 *   <li><b>导入</b>：multipart xlsx 单文件，逐行校验容错——错误行跳过记 {@code {行号,原因}}，
 *       正确行入库（含标签关联、难度、图片URL）；文件级前置校验（扩展名/可解析/数据行数上限，
 *       超限整批 400 拒绝）；每行独立事务（一行的库内失败记行错误，不拖垮整批）；不去重。渲染口径
 *       对齐单条建题（答案归一化/图片URL 契约/题干与答案长度上限）。</li>
 *   <li><b>导出</b>：全量未软删题目（对齐列表查询口径，非 ADMIN 仅导个人、ADMIN 全量），
 *       列口径与模板逐字一致（round-trip 可用），SXSSF 流式复用 {@link ExcelSheetWriter}。attachment 响应对齐成绩导出。</li>
 *   <li><b>模板</b>：表头 + 五题型示例行 + 填写说明，与导入校验共用 {@link QuestionImportColumn} 列定义。</li>
 * </ul>
 *
 * <p><b>事务策略</b>：导入方法本身不加 {@code @Transactional}，每行的「插题 + 标签关联」
 * 经 {@link TransactionTemplate} 独立提交——无论行校验失败还是库内写入异常，都只记为单行错误，
 * 已入库的正确行不因后续行失败而回滚。
 */
@Slf4j
@Service
public class QuestionImportExportService {

    /** 五题型词 → 枚举（模板列「题型」可读口径；解析时严格匹配。非法词记行错误）。 */
    private static final Map<String, QuestionType> TYPE_WORDS = Map.of(
            "单选", QuestionType.SINGLE,
            "多选", QuestionType.MULTIPLE,
            "判断", QuestionType.JUDGE,
            "简答", QuestionType.SHORT_ANSWER,
            "填空", QuestionType.FILL_BLANK);

    /** 难度词 → 列值（1易 2中 3难）。 */
    private static final Map<String, Integer> DIFFICULTY_WORDS = Map.of(
            "易", 1,
            "中", 2,
            "难", 3);

    /** 导入题目的默认分值（模板无分值列；对齐 schema questions.score 默认 5）。 */
    private static final BigDecimal DEFAULT_SCORE = new BigDecimal("5");

    /** 图片URL 契约（同 add-question-image 建题口径，外链记行错误）。 */
    private static final String IMAGE_URL_PATTERN = "^/images/.*$";

    private static final int IMAGE_URL_MAX = 512;
    private static final int CONTENT_MAX = 5000;
    /** 答案长度上限：对齐 schema questions.correct_answer VARCHAR(512) 与单题建题 @Size 文案。 */
    private static final int ANSWER_MAX = 512;

    private final QuestionMapper questionMapper;
    private final QuestionTagMapper questionTagMapper;
    private final TagMapper tagMapper;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final int maxRows;

    public QuestionImportExportService(QuestionMapper questionMapper,
                                       QuestionTagMapper questionTagMapper,
                                       TagMapper tagMapper,
                                       ObjectMapper objectMapper,
                                       PlatformTransactionManager transactionManager,
                                       @Value("${exam.question-import.max-rows:1000}") int maxRows) {
        this.questionMapper = questionMapper;
        this.questionTagMapper = questionTagMapper;
        this.tagMapper = tagMapper;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.maxRows = maxRows;
    }

    // ==================== 导入 ====================

    /**
     * 逐行容错导入。先做文件级前置校验（扩展名/可解析/表头/数据行数上限），
     * 再逐行校验 → 正确行独立事务入库，错误行跳过记报告。
     */
    public QuestionImportResult importQuestions(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "请选择 xlsx 文件");
        }
        if (!"xlsx".equalsIgnoreCase(extensionOf(file.getOriginalFilename()))) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "仅支持 .xlsx 格式的题库文件");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "文件读取失败");
        }

        Sheet sheet = parseSheet(bytes);
        verifyHeader(sheet);
        int dataRowCount = countDataRows(sheet);
        if (dataRowCount > maxRows) {
            throw new BusinessException(ResponseCode.BAD_REQUEST,
                    "数据行数 " + dataRowCount + " 超过单次导入上限 " + maxRows + "，请分批导入");
        }

        LoginUser operator = requireLogin();
        Long createdBy = operator.getId();

        QuestionImportResult result = new QuestionImportResult();
        DataFormatter formatter = new DataFormatter();
        // Excel 数据行从第 2 行（0 起 index 1）开始
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row excelRow = sheet.getRow(i);
            if (excelRow == null || isEmptyRow(excelRow, formatter)) {
                continue;
            }
            int excelLine = i + 1; // 人类可读行号（表头第 1 行）
            ParsedRow parsed;
            try {
                parsed = parseRow(excelRow, formatter);
            } catch (BusinessException e) {
                result.getErrors().add(new QuestionImportResult.ErrorRow(excelLine, e.getMessage()));
                result.setFailed(result.getFailed() + 1);
                continue;
            }
            if (!parsed.errors.isEmpty()) {
                result.getErrors().add(new QuestionImportResult.ErrorRow(excelLine,
                        String.join("；", parsed.errors)));
                result.setFailed(result.getFailed() + 1);
                continue;
            }
            try {
                insertRowInTransaction(parsed, createdBy);
                result.setImported(result.getImported() + 1);
            } catch (RuntimeException e) {
                log.warn("导入单行入库失败 line={} err={}", excelLine, e.getMessage());
                result.getErrors().add(new QuestionImportResult.ErrorRow(excelLine, "入库失败，请检查数据"));
                result.setFailed(result.getFailed() + 1);
            }
        }
        log.info("教师 {} 导入题库：imported={} failed={}", createdBy, result.getImported(), result.getFailed());
        return result;
    }

    /** 解析工作簿；不可解析（非 xlsx / 损坏）整批 400。 */
    private Sheet parseSheet(byte[] bytes) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            return workbook.getSheetAt(0);
        } catch (IOException e) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "文件无法解析，请确认是有效的 xlsx 文件");
        }
    }

    /** 表头逐字对齐列定义（列定义共用防漂移的核心落地）。 */
    private void verifyHeader(Sheet sheet) {
        Row header = sheet.getRow(0);
        if (header == null) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "模板缺少表头行");
        }
        String[] expected = QuestionImportColumn.headers();
        for (int c = 0; c < expected.length; c++) {
            Cell cell = header.getCell(c);
            String actual = cell == null ? "" : cell.getStringCellValue().trim();
            if (!actual.equals(expected[c])) {
                throw new BusinessException(ResponseCode.BAD_REQUEST,
                        "模板表头与当前模板不一致，请下载最新模板（第 " + (c + 1) + " 列应为「" + expected[c] + "」）");
            }
        }
    }

    /** 数据行数统计（跳过整行为空的行）。 */
    private int countDataRows(Sheet sheet) {
        DataFormatter formatter = new DataFormatter();
        int count = 0;
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row != null && !isEmptyRow(row, formatter)) {
                count++;
            }
        }
        return count;
    }

    private boolean isEmptyRow(Row row, DataFormatter formatter) {
        return questionColumnIndexes().stream().allMatch(c -> {
            Cell cell = row.getCell(c);
            return cell == null || !StringUtils.hasText(formatter.formatCellValue(cell));
        });
    }

    private static List<Integer> questionColumnIndexes() {
        return Arrays.stream(QuestionImportColumn.values()).map(QuestionImportColumn::cellIndex).toList();
    }

    /** 单行解析：累积校验错误到 {@link ParsedRow#errors}，任一错误则该行整体跳过。 */
    private ParsedRow parseRow(Row row, DataFormatter formatter) {
        ParsedRow parsed = new ParsedRow();
        String typeWord = cellText(row, QuestionImportColumn.TYPE, formatter).trim();
        QuestionType type = TYPE_WORDS.get(typeWord);
        if (type == null) {
            parsed.errors.add("题型必须为：单选/多选/判断/简答/填空");
            return parsed;
        }

        String content = cellText(row, QuestionImportColumn.CONTENT, formatter).trim();
        if (!StringUtils.hasText(content)) {
            parsed.errors.add("题干不能为空");
        } else if (content.length() > CONTENT_MAX) {
            parsed.errors.add("题干最长 " + CONTENT_MAX + " 字");
        }

        // 选项 A-F（非空按列序收集）
        List<String> choices = new ArrayList<>();
        for (char ch = 'A'; ch <= 'F'; ch++) {
            String text = cellText(row, QuestionImportColumn.valueOf("CHOICE_" + ch), formatter);
            if (StringUtils.hasText(text)) {
                choices.add(text.trim());
            }
        }
        if (type == QuestionType.SINGLE || type == QuestionType.MULTIPLE) {
            if (choices.size() < 2) {
                parsed.errors.add("单选/多选至少需要两个非空选项");
            }
        } else if (!choices.isEmpty()) {
            parsed.errors.add(type.getLabel() + "题无需填写选项，请留空");
        }

        String rawAnswer = cellText(row, QuestionImportColumn.ANSWER, formatter).trim();
        try {
            parsed.answer = AnswerNormalizer.normalize(type, rawAnswer, choices.size());
        } catch (BusinessException e) {
            parsed.errors.add(e.getMessage());
        }
        // 答案长度：应用层前置拦截，避免落到 DB 字段溢出被记成「入库失败，请检查数据」
        if (parsed.answer != null && parsed.answer.length() > ANSWER_MAX) {
            parsed.errors.add("答案最长 " + ANSWER_MAX + " 字");
        }

        // 标签：逗号/顿号分隔（仅接受已存在标签名，未知记行错误，不随手新建）
        String tagCell = cellText(row, QuestionImportColumn.TAGS, formatter).trim();
        if (StringUtils.hasText(tagCell)) {
            List<String> names = Arrays.stream(tagCell.split("[,，、;；]+"))
                    .map(String::trim)
                    .filter(StringUtils::hasText)
                    .distinct()
                    .toList();
            for (String name : names) {
                List<Tag> tags = tagMapper.selectList(Wrappers.<Tag>lambdaQuery()
                        .eq(Tag::getName, name));
                if (tags.isEmpty()) {
                    parsed.errors.add("标签不存在: " + name);
                } else {
                    tags.forEach(t -> parsed.tagIdsRequest.add(t.getId()));
                }
            }
        }

        // 难度：易/中/难；空则对齐既有缺省口径（schema 默认 1 易）
        String diffWord = cellText(row, QuestionImportColumn.DIFFICULTY, formatter).trim();
        if (StringUtils.hasText(diffWord)) {
            Integer diff = DIFFICULTY_WORDS.get(diffWord);
            if (diff == null) {
                parsed.errors.add("难度必须为：易/中/难");
            } else {
                parsed.difficulty = diff;
            }
        } else {
            parsed.difficulty = 1;
        }

        // 图片URL：空按无图；否则须 /images/ 前缀 + ≤512（契约同 add-question-image）
        String imageUrl = cellText(row, QuestionImportColumn.IMAGE_URL, formatter).trim();
        if (StringUtils.hasText(imageUrl)) {
            if (!imageUrl.matches(IMAGE_URL_PATTERN)) {
                parsed.errors.add("图片URL 必须以 /images/ 开头");
            } else if (imageUrl.length() > IMAGE_URL_MAX) {
                parsed.errors.add("图片地址最长 512 字");
            } else {
                parsed.imageUrl = imageUrl;
            }
        }

        parsed.type = type;
        parsed.content = content;
        parsed.choices = choices;
        return parsed;
    }

    private static String cellText(Row row, QuestionImportColumn column, DataFormatter formatter) {
        Cell cell = row.getCell(column.cellIndex());
        if (cell == null) {
            return "";
        }
        return formatter.formatCellValue(cell);
    }

    /** 单行入库（独立事务）：插题 + 标签关联。 */
    private void insertRowInTransaction(ParsedRow parsed, Long createdBy) {
        transactionTemplate.executeWithoutResult(status -> {
            Question question = new Question();
            question.setType(parsed.type.getCode());
            question.setContent(parsed.content);
            question.setImageUrl(parsed.imageUrl);
            question.setChoices(toJson(parsed.choices));
            question.setCorrectAnswer(parsed.answer);
            question.setScore(DEFAULT_SCORE);
            question.setDifficulty(parsed.difficulty);
            question.setAnalysis(null);
            question.setCreatedBy(createdBy);
            questionMapper.insert(question);

            Set<Long> distinctTagIds = new LinkedHashSet<>(parsed.tagIdsRequest);
            for (Long tagId : distinctTagIds) {
                QuestionTag link = new QuestionTag();
                link.setQuestionId(question.getId());
                link.setTagId(tagId);
                questionTagMapper.insert(link);
            }
        });
    }

    private String toJson(List<String> choices) {
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(choices);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ResponseCode.INTERNAL_ERROR, "选项序列化失败");
        }
    }

    // ==================== 导出 ====================

    /**
     * 全量导出未软删题目为 xlsx（列口径与模板逐字一致，round-trip 可用）。
     * 权限与列表查询同口径：非 ADMIN 仅导本人（个人题库），ADMIN 全量。
     */
    public byte[] exportQuestions() {
        LoginUser operator = requireLogin();
        Map<Long, Question> questions = questionMapper.selectList(
                        Wrappers.<Question>lambdaQuery()
                                .eq(operator.getRoleLevel() < RoleHierarchy.levelOf(RoleHierarchy.ADMIN),
                                        Question::getCreatedBy, operator.getId())
                                .orderByAsc(Question::getId))
                .stream().collect(Collectors.toMap(Question::getId, Function.identity()));
        Map<Long, List<Tag>> tagMap = loadTags(questions.keySet());

        return ExcelSheetWriter.withStreamingWorkbook(workbook -> {
            SXSSFSheet sheet = ExcelSheetWriter.createSheet(workbook, "题库");
            ExcelSheetWriter.writeHeader(sheet, (Object[]) QuestionImportColumn.headers());
            int idx = 1;
            for (Question q : questions.values()) {
                Row row = sheet.createRow(idx++);
                row.createCell(QuestionImportColumn.TYPE.cellIndex())
                        .setCellValue(QuestionType.of(q.getType()).getLabel());
                row.createCell(QuestionImportColumn.CONTENT.cellIndex()).setCellValue(q.getContent());
                List<String> choices = parseChoices(q.getChoices());
                for (int c = 0; c < choices.size() && c < 6; c++) {
                    row.createCell(QuestionImportColumn.valueOf("CHOICE_" + (char) ('A' + c)).cellIndex())
                            .setCellValue(choices.get(c));
                }
                row.createCell(QuestionImportColumn.ANSWER.cellIndex())
                        .setCellValue(q.getCorrectAnswer() == null ? "" : q.getCorrectAnswer());
                String tagNames = tagMap.getOrDefault(q.getId(), List.of()).stream()
                        .map(Tag::getName).collect(Collectors.joining(","));
                row.createCell(QuestionImportColumn.TAGS.cellIndex()).setCellValue(tagNames);
                row.createCell(QuestionImportColumn.DIFFICULTY.cellIndex())
                        .setCellValue(difficultyWordOf(q.getDifficulty()));
                row.createCell(QuestionImportColumn.IMAGE_URL.cellIndex())
                        .setCellValue(q.getImageUrl() == null ? "" : q.getImageUrl());
            }
            ExcelSheetWriter.autoSize(sheet, QuestionImportColumn.columnCount());
        });
    }

    /** 模板下载：表头 + 五题型示例行 + 填写说明。 */
    public byte[] buildTemplate() {
        return ExcelSheetWriter.withStreamingWorkbook(workbook -> {
            SXSSFSheet sheet = ExcelSheetWriter.createSheet(workbook, "题库导入模板");
            ExcelSheetWriter.writeHeader(sheet, (Object[]) QuestionImportColumn.headers());

            // 五题型示例行（可整行沿用再编辑）
            String[][] examples = {
                    {"单选", "以下哪种颜色属于三原色？", "红", "绿", "蓝", "", "", "", "A", "", "易", ""},
                    {"多选", "以下哪些是水果？", "苹果", "白菜", "香蕉", "", "", "", "A,C", "", "中", ""},
                    {"判断", "地球是圆的。", "", "", "", "", "", "", "正确", "", "易", ""},
                    {"简答", "简述 HTTP 与 HTTPS 的区别。", "", "", "", "", "", "", "HTTPS 加密传输", "", "中", ""},
                    {"填空", "中国的首都是____。", "", "", "", "", "", "", "北京|北京", "", "难", ""},
            };
            for (int i = 0; i < examples.length; i++) {
                Row row = sheet.createRow(i + 1);
                String[] cells = examples[i];
                for (int c = 0; c < cells.length; c++) {
                    row.createCell(QuestionImportColumn.values()[c].cellIndex()).setCellValue(cells[c]);
                }
            }

            // 填写说明行（标记「填写说明」，说明惯例；不入库）
            Row guide = sheet.createRow(examples.length + 1);
            guide.createCell(QuestionImportColumn.TYPE.cellIndex()).setCellValue("填写说明");
            guide.createCell(QuestionImportColumn.CONTENT.cellIndex()).setCellValue(
                    "题型：单选/多选/判断/简答/填空；多选答案为升序字母（如 AC）；判断填 正确/错误 或 T/F；"
                            + "填空多个可接受答案用 | 分隔；标签填已存在标签名（逗号分隔）；难度填 易/中/难（留空默认易）；"
                            + "图片URL 须以 /images/ 开头或留空；题干必填、答案必填。");
            ExcelSheetWriter.autoSize(sheet, QuestionImportColumn.columnCount());
        });
    }

    // ==================== 工具 ====================

    private Map<Long, List<Tag>> loadTags(Set<Long> questionIds) {
        if (questionIds.isEmpty()) {
            return Map.of();
        }
        List<QuestionTag> links = questionTagMapper.selectList(Wrappers.<QuestionTag>lambdaQuery()
                .in(QuestionTag::getQuestionId, questionIds));
        if (links.isEmpty()) {
            return Map.of();
        }
        Set<Long> tagIds = links.stream().map(QuestionTag::getTagId).collect(Collectors.toSet());
        Map<Long, Tag> tagMap = tagMapper.selectBatchIds(tagIds).stream()
                .collect(Collectors.toMap(Tag::getId, Function.identity()));
        Map<Long, List<Tag>> result = new java.util.HashMap<>();
        for (QuestionTag link : links) {
            Tag tag = tagMap.get(link.getTagId());
            if (tag != null) {
                result.computeIfAbsent(link.getQuestionId(), k -> new ArrayList<>()).add(tag);
            }
        }
        return result;
    }

    private List<String> parseChoices(String choicesJson) {
        if (choicesJson == null || choicesJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(choicesJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private String difficultyWordOf(Integer difficulty) {
        for (Map.Entry<String, Integer> e : DIFFICULTY_WORDS.entrySet()) {
            if (e.getValue().equals(difficulty)) {
                return e.getKey();
            }
        }
        return "易";
    }

    private static String extensionOf(String originalFilename) {
        if (originalFilename == null) {
            return "";
        }
        int dot = originalFilename.lastIndexOf('.');
        return dot < 0 || dot == originalFilename.length() - 1
                ? "" : originalFilename.substring(dot + 1).toLowerCase();
    }

    private LoginUser requireLogin() {
        LoginUser operator = SecurityUtil.getCurrentUser();
        if (operator == null) {
            throw new BusinessException(ResponseCode.TOKEN_INVALID);
        }
        return operator;
    }

    /** 单行解析中间态。 */
    private static class ParsedRow {
        QuestionType type;
        String content;
        List<String> choices = new ArrayList<>();
        String answer;
        List<Long> tagIdsRequest = new ArrayList<>();
        Integer difficulty;
        String imageUrl;
        final List<String> errors = new ArrayList<>();
    }
}
package com.exam.score;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.auth.service.JwtUtil;
import com.exam.exam.mapper.ExamMapper;
import com.exam.exam.service.ExamStateMachineService;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.grading.mapper.SubjectiveGradeMapper;
import com.exam.question.entity.Tag;
import com.exam.question.mapper.TagMapper;
import com.exam.submission.dto.SubmitMessage;
import com.exam.submission.mq.ExamSubmitConsumer;
import com.exam.submission.mapper.ExamSubmissionMapper;
import com.exam.support.IntegrationTestBase;
import com.exam.support.RabbitTemplateInvokeStubs;
import com.exam.user.entity.User;
import com.exam.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 考试数据分析报告 Excel 导出集成测试（add-exam-analysis-export）：
 * H2 + MockMvc + MQ Mock，覆盖 spec-delta 两个 Scenario——
 * ① 多 Sheet 导出：四个工作表（班级概览与分数段/逐题指标/知识点薄弱/学生关注名单），
 *    指标数值与 JSON 报告聚合口径同源（同一 buildReport 输出直写单元格）；
 * ② 公式防注入：关注名单学生姓名/知识点名称等用户可控文本经 sanitizeFormula 清洗；
 * ③ 越权与未汇总拦截：非归属教师 403 / 考试不存在 404 / 未汇总 400。
 */
class ExamAnalysisReportExportIntegrationTest extends IntegrationTestBase {

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private ExamStateMachineService stateMachineService;
    @Autowired
    private ExamSubmissionMapper submissionMapper;
    @Autowired
    private GradingSubmissionMapper gradingSubmissionMapper;
    @Autowired
    private SubjectiveGradeMapper subjectiveGradeMapper;
    @Autowired
    private ExamMapper examMapper;
    @Autowired
    private ExamSubmitConsumer submitConsumer;
    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private TagMapper tagMapper;

    @BeforeEach
    void runRabbitInvokeCallbacks() {
        RabbitTemplateInvokeStubs.runInvokeCallbacks(rabbitTemplate);
    }

    // ==================== 造数（与 ExamAnalysisReportIntegrationTest 同一套链路） ====================

    private long addPaperQuestion(String token, long paperId, long questionId, double score) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("questionId", questionId);
        body.put("score", score);
        perform(jsonPost("/api/papers/" + paperId + "/questions", token,
                objectMapper.writeValueAsString(body)), 200);
        return questionId;
    }

    private long preparePaper(String teacher, Long presetTagId, double scale) throws Exception {
        // 卷面题分 = 基准(4,6,4,6) × scale，试卷申报总分 = 20 × scale，保证发布总分一致校验通过
        long qSingle = createQuestion(teacher, 1, "单选：1+1=?", "A",
                List.of("A", "B", "C", "D"), presetTagId == null ? null : List.of(presetTagId));
        long qMultiple = createQuestion(teacher, 2, "多选：下列哪些是质数", "A,C",
                List.of("2", "4", "3", "9"), null);
        long qJudge = createQuestion(teacher, 3, "判断：水沸腾", "T", null, null);
        long qShort = createQuestion(teacher, 4, "简答：什么是HTTP", "HTTP,协议,无状态", null, null);
        long paperId = createPaper(teacher, unique("试卷"), 20 * scale);
        addPaperQuestion(teacher, paperId, qSingle, 4 * scale);
        addPaperQuestion(teacher, paperId, qMultiple, 6 * scale);
        addPaperQuestion(teacher, paperId, qJudge, 4 * scale);
        addPaperQuestion(teacher, paperId, qShort, 6 * scale);
        return paperId;
    }

    private long createExam(String teacher, long paperId, Long classId) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("title", unique("考试"));
        body.put("paperId", paperId);
        if (classId != null) {
            body.put("classId", classId);
        }
        body.put("startTime", LocalDateTime.now().minusMinutes(1).toString());
        body.put("endTime", LocalDateTime.now().plusHours(2).toString());
        body.put("durationMinutes", 30);
        JsonNode data = perform(jsonPost("/api/exams", teacher,
                objectMapper.writeValueAsString(body)), 200).get("data");
        return data.get("id").asLong();
    }

    private long preparedInProgressExam(String teacher, Long presetTagId, double scale) throws Exception {
        long paperId = preparePaper(teacher, presetTagId, scale);
        long classId = createClassForExam(teacher);
        long examId = createExam(teacher, paperId, classId);
        perform(jsonPost("/api/exams/" + examId + "/publish", teacher, null), 200);
        stateMachineService.autoAdvance();
        return examId;
    }

    private Map<Integer, Long> enterAndMapQuestions(String student, long examId) throws Exception {
        ensureExamClassMembership(student, examId);
        JsonNode data = perform(jsonPost("/api/exam-taking/exams/" + examId + "/enter", student, null), 200)
                .get("data");
        Map<Integer, Long> byType = new HashMap<>();
        data.get("questions").forEach(q -> byType.put(q.get("type").asInt(), q.get("questionId").asLong()));
        return byType;
    }

    private void submit(String student, long examId, Map<Integer, Long> questions,
                        String single, String multiple, String judge, String shortAnswer) throws Exception {
        ObjectNode answers = objectMapper.createObjectNode();
        if (single != null) { answers.put(String.valueOf(questions.get(1)), single); }
        if (multiple != null) { answers.put(String.valueOf(questions.get(2)), multiple); }
        if (judge != null) { answers.put(String.valueOf(questions.get(3)), judge); }
        if (shortAnswer != null) { answers.put(String.valueOf(questions.get(4)), shortAnswer); }
        ObjectNode request = objectMapper.createObjectNode();
        request.set("answers", answers);
        perform(jsonPost("/api/exam-taking/exams/" + examId + "/submit", student,
                objectMapper.writeValueAsString(request)), 200);
        consumeCapturedMessages(examId);
    }

    private void consumeCapturedMessages(long examId) throws Exception {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate, atLeastOnce()).convertAndSend(anyString(), anyString(), captor.capture());
        for (Object raw : new ArrayList<>(captor.getAllValues())) {
            SubmitMessage message = (SubmitMessage) raw;
            if (!message.getExamId().equals(examId)) { continue; }
            Message amqp = new Message(objectMapper.writeValueAsBytes(message), new MessageProperties());
            amqp.getMessageProperties().setDeliveryTag(1L);
            submitConsumer.onBatch(List.of(amqp), mock(com.rabbitmq.client.Channel.class));
        }
    }

    private long studentIdOf(String token) throws Exception {
        return jwtUtil.parseAccessToken(token).getId();
    }

    private void saveSubjective(String teacher, long examId, long studentId, long questionId,
                                String score, int expectedVersion, String comment) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("submissionId", submissionIdOf(examId, studentId));
        body.put("questionId", questionId);
        body.put("score", new BigDecimal(score));
        body.put("expectedVersion", expectedVersion);
        body.put("comment", comment);
        perform(jsonPost("/api/exams/" + examId + "/grading/subjective/save", teacher,
                objectMapper.writeValueAsString(body)), 200);
    }

    private long submissionIdOf(long examId, long studentId) {
        return gradingSubmissionMapper.selectOne(Wrappers.<GradingSubmission>lambdaQuery()
                .eq(GradingSubmission::getExamId, examId)
                .eq(GradingSubmission::getStudentId, studentId)).getId();
    }

    /** 造一场已汇总（GRADED）的 20 分卷考试：3 名学生总分 20/6/10（简答批改 6/3/2），及格线派生 12。 */
    private long preparedGradedExam(String teacher, Long presetTagId) throws Exception {
        String s1 = registerStudent();
        String s2 = registerStudent();
        String s3 = registerStudent();
        long examId = preparedInProgressExam(teacher, presetTagId, 1);
        Map<Integer, Long> q1 = enterAndMapQuestions(s1, examId);
        submit(s1, examId, q1, "A", "A,C", "T", "HTTP 是无状态的协议");
        Map<Integer, Long> q2 = enterAndMapQuestions(s2, examId);
        submit(s2, examId, q2, "B", "A", "F", "HTTP");
        Map<Integer, Long> q3 = enterAndMapQuestions(s3, examId);
        submit(s3, examId, q3, "A", "A,D", "T", "");
        perform(jsonPost("/api/exams/" + examId + "/force-end", teacher, null), 200);
        perform(jsonPost("/api/exams/" + examId + "/grading/run", teacher, null), 200);
        long shortQ = q1.get(4);
        saveSubjective(teacher, examId, studentIdOf(s1), shortQ, "6", 0, "表述完整");
        saveSubjective(teacher, examId, studentIdOf(s2), shortQ, "3", 0, "关键词少");
        saveSubjective(teacher, examId, studentIdOf(s3), shortQ, "2", 0, "未作答");
        perform(jsonPost("/api/exams/" + examId + "/scores/summarize", teacher, null), 200);
        return examId;
    }

    private byte[] exportBytes(String token, long examId) throws Exception {
        var result = mockMvc.perform(jsonGet("/api/exams/" + examId + "/scores/analysis-report/export", token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment; filename*=UTF-8''")))
                .andReturn();
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                result.getResponse().getContentType(),
                "导出响应媒体类型必须是 xlsx");
        return result.getResponse().getContentAsByteArray();
    }

    private static String text(Cell cell) {
        if (cell == null) {
            return "";
        }
        return cell.getCellType() == CellType.NUMERIC
                ? String.valueOf(cell.getNumericCellValue())
                : cell.getStringCellValue();
    }

    private static double number(Cell cell) {
        assertNotNull(cell, "数值单元格不得缺失");
        return cell.getNumericCellValue();
    }

    // ==================== Scenario 1：多 Sheet 导出 ====================

    @Test
    void exportFourSheetsWithSameSourceAggregation() throws Exception {
        String teacher = registerTeacher();
        long examId = preparedGradedExam(teacher, null);

        byte[] xlsxBytes = exportBytes(teacher, examId);
        assertNotNull(xlsxBytes);
        assertTrue(xlsxBytes.length > 0);

        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsxBytes))) {
            assertEquals(4, workbook.getNumberOfSheets(), "工作簿必须恰好四个工作表");
            assertEquals("班级概览与分数段", workbook.getSheetName(0));
            assertEquals("逐题指标", workbook.getSheetName(1));
            assertEquals("知识点薄弱", workbook.getSheetName(2));
            assertEquals("学生关注名单", workbook.getSheetName(3));

            // ---- Sheet 1：班级概览与分数段（口径同 buildReport：3 应考/3 实考/0 缺席，及格线 12） ----
            Sheet overview = workbook.getSheet("班级概览与分数段");
            assertEquals("指标", overview.getRow(0).getCell(0).getStringCellValue());
            Map<String, String> metrics = new HashMap<>();
            for (int r = 1; r <= 7; r++) {
                Row row = overview.getRow(r);
                metrics.put(row.getCell(0).getStringCellValue(), text(row.getCell(1)));
            }
            assertEquals("3.0", metrics.get("应考人数"), "应考=班级 3 名学生");
            assertEquals("3.0", metrics.get("实考人数"), "实考=3 份已汇总答卷");
            assertEquals("0.0", metrics.get("缺席人数"));
            assertEquals("12.0", metrics.get("及格线"), "20 分卷及格线派生 12（×60% HALF_UP）");
            assertEquals("18.0", metrics.get("优秀线"), "20 分卷优秀线派生 18（×90% HALF_UP）");
            assertEquals("12.0", metrics.get("平均分"), "总分 20/6/10 均值 12.0");
            assertEquals("33.3%", metrics.get("及格率"), "及格 1/3 → 33.3%");

            // 分数段小节：空行后第 9 行起为五段分布表（切点 12/14/16/18）
            assertEquals("分数段", overview.getRow(9).getCell(0).getStringCellValue());
            assertEquals("人数", overview.getRow(9).getCell(1).getStringCellValue());
            assertEquals("占比", overview.getRow(9).getCell(2).getStringCellValue());
            assertEquals("0-11", overview.getRow(10).getCell(0).getStringCellValue());
            assertEquals(2.0, number(overview.getRow(10).getCell(1)), 1e-9, "总分 6/10 落入首段");
            assertEquals("66.7%", text(overview.getRow(10).getCell(2)));
            assertEquals("12-13", overview.getRow(11).getCell(0).getStringCellValue());
            assertEquals(0.0, number(overview.getRow(11).getCell(1)), 1e-9);
            assertEquals("18-20", overview.getRow(14).getCell(0).getStringCellValue());
            assertEquals(1.0, number(overview.getRow(14).getCell(1)), 1e-9, "总分 20 落入末段");
            assertEquals("33.3%", text(overview.getRow(14).getCell(2)));

            // ---- Sheet 2：逐题指标（4 题，作答人数恒 3，单选选项分布 A:2 B:1） ----
            Sheet questions = workbook.getSheet("逐题指标");
            assertEquals("题号", questions.getRow(0).getCell(0).getStringCellValue());
            assertEquals("题型", questions.getRow(0).getCell(1).getStringCellValue());
            assertEquals("满分", questions.getRow(0).getCell(2).getStringCellValue());
            assertEquals("平均分", questions.getRow(0).getCell(3).getStringCellValue());
            assertEquals("得分率", questions.getRow(0).getCell(4).getStringCellValue());
            assertEquals("答对率", questions.getRow(0).getCell(5).getStringCellValue());
            assertEquals("区分度", questions.getRow(0).getCell(6).getStringCellValue());
            assertEquals("作答人数", questions.getRow(0).getCell(7).getStringCellValue());
            assertEquals("选项分布", questions.getRow(0).getCell(8).getStringCellValue());
            assertEquals(4, questions.getLastRowNum(), "表头 + 4 题");
            assertEquals("单选", questions.getRow(1).getCell(1).getStringCellValue());
            assertEquals(3.0, number(questions.getRow(1).getCell(7)), 1e-9, "单选作答 3 人");
            assertEquals("A:2(67%) B:1(33%) C:0(0%) D:0(0%)",
                    questions.getRow(1).getCell(8).getStringCellValue(), "选项分布与题目统计导出同格式");
            // 区分度样本不足（3 人 < 4）→ 文本「样本不足」
            assertEquals("样本不足", questions.getRow(1).getCell(6).getStringCellValue());
            // 单选平均分 (4+0+4)/3 → 2.7（buildReport 已按 1 位小数舍入）
            assertEquals(2.7, number(questions.getRow(1).getCell(3)), 1e-9);

            // ---- Sheet 3：知识点薄弱（无标签 → 单行提示，优雅降级非错误） ----
            Sheet tags = workbook.getSheet("知识点薄弱");
            assertEquals("题库未打知识点标签", tags.getRow(1).getCell(0).getStringCellValue());

            // ---- Sheet 4：学生关注名单（低于及格线 12 的 6/10 两名，按总分升序） ----
            Sheet focus = workbook.getSheet("学生关注名单");
            assertEquals("学号", focus.getRow(0).getCell(0).getStringCellValue());
            assertEquals("姓名", focus.getRow(0).getCell(1).getStringCellValue());
            assertEquals("总分", focus.getRow(0).getCell(2).getStringCellValue());
            assertEquals(2, focus.getLastRowNum(), "表头 + 2 名低于及格线学生");
            assertEquals(6.0, number(focus.getRow(1).getCell(2)), 1e-9, "首行总分 6（升序）");
            assertEquals(10.0, number(focus.getRow(2).getCell(2)), 1e-9, "次行总分 10（升序）");
        }
    }

    @Test
    void exportTagSheetWhenTagDimensionPresent() throws Exception {
        String teacher = registerTeacher();
        long tagId = createTag(teacher, unique("知识点"), "CUSTOM");
        long examId = preparedGradedExam(teacher, tagId);

        byte[] xlsxBytes = exportBytes(teacher, examId);
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsxBytes))) {
            Sheet tags = workbook.getSheet("知识点薄弱");
            assertEquals("知识点名称", tags.getRow(0).getCell(0).getStringCellValue());
            assertEquals("得分率", tags.getRow(0).getCell(1).getStringCellValue());
            String tagName = tags.getRow(1).getCell(0).getStringCellValue();
            assertTrue(tagName.contains("知识点"), "标签名应透传到导出");
            assertTrue(tags.getLastRowNum() >= 1, "有标签维度时至少一行知识点");
        }
    }

    // ==================== Scenario 2：公式防注入 ====================

    @Test
    void exportSanitizesUserControlledText() throws Exception {
        String teacher = registerTeacher();
        long tagId = createTag(teacher, unique("知识点"), "CUSTOM");
        long examId = preparedGradedExam(teacher, tagId);

        // 把一名上榜学生的姓名与知识点标签名改成公式头字符开头的恶意文本：
        // 关注名单/知识点薄弱 Sheet 的单元格必须经 sanitizeFormula 前置单引号（CWE-1236）
        String injectedName = "=HYPERLINK(\"http://evil\",\"x\")";
        User victim = userMapper.selectById(studentIdOfTeacherExamFocus(examId));
        victim.setName(injectedName);
        userMapper.updateById(victim);
        Tag tag = tagMapper.selectById(tagId);
        tag.setName("=SUM(A1)");
        tagMapper.updateById(tag);

        byte[] xlsxBytes = exportBytes(teacher, examId);
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsxBytes))) {
            // 关注名单：注入姓名必须以 ' 前缀存储（清洗格式=前置单引号），不得以 = 原样落格
            Sheet focus = workbook.getSheet("学生关注名单");
            boolean nameSanitized = false;
            for (int r = 1; r <= focus.getLastRowNum(); r++) {
                String name = focus.getRow(r).getCell(1).getStringCellValue();
                if (name.startsWith("=")) {
                    fail("姓名未经 sanitizeFormula 清洗即落格：" + name);
                }
                if (("'" + injectedName).equals(name)) {
                    nameSanitized = true;
                }
            }
            assertTrue(nameSanitized, "注入文本必须原样落格（清洗后带 ' 前缀）");
            // 知识点薄弱：标签名同样清洗
            Sheet tags = workbook.getSheet("知识点薄弱");
            boolean tagSanitized = false;
            for (int r = 1; r <= tags.getLastRowNum(); r++) {
                String tagName = tags.getRow(r).getCell(0).getStringCellValue();
                if (tagName.startsWith("=")) {
                    fail("知识点名称未经 sanitizeFormula 清洗即落格：" + tagName);
                }
                if ("'=SUM(A1)".equals(tagName)) {
                    tagSanitized = true;
                }
            }
            assertTrue(tagSanitized, "知识点名称必须经 sanitizeFormula 清洗");
        }
    }

    /** 取关注名单里任一学生 id（低于 10 分者恰为总分 6 那名，直接查已汇总答卷）。 */
    private long studentIdOfTeacherExamFocus(long examId) {
        return gradingSubmissionMapper.selectList(Wrappers.<GradingSubmission>lambdaQuery()
                        .eq(GradingSubmission::getExamId, examId)
                        .isNotNull(GradingSubmission::getTotalScore)).stream()
                .filter(g -> g.getTotalScore().compareTo(BigDecimal.TEN) < 0)
                .findFirst()
                .orElseThrow()
                .getStudentId();
    }

    // ==================== Scenario 3：越权与未汇总拦截 ====================

    @Test
    void exportGuardsOwnershipExistenceAndGradedState() throws Exception {
        String teacher = registerTeacher();
        String otherTeacher = registerTeacher();
        long examId = preparedGradedExam(teacher, null);

        // 非归属教师 → 403
        JsonNode forbidden = perform(jsonGet("/api/exams/" + examId
                + "/scores/analysis-report/export", otherTeacher), 403);
        assertEquals(403, forbidden.get("code").asInt());

        // 考试不存在 → 404
        JsonNode notFound = perform(jsonGet("/api/exams/" + 999999L
                + "/scores/analysis-report/export", teacher), 404);
        assertEquals(404, notFound.get("code").asInt(), "考试不存在返回 404");

        // 未汇总（进行中）→ 400
        String student = registerStudent();
        long inProgress = preparedInProgressExam(teacher, null, 1);
        Map<Integer, Long> q = enterAndMapQuestions(student, inProgress);
        submit(student, inProgress, q, "A", "A,C", "T", "HTTP 是无状态的协议");
        JsonNode bad = perform(jsonGet("/api/exams/" + inProgress
                + "/scores/analysis-report/export", teacher), 400);
        assertTrue(bad.get("message").asText().contains("汇总"), "未汇总须明确报错");
    }
}

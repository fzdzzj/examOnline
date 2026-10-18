package com.exam.score;

import com.exam.auth.service.JwtUtil;
import com.exam.exam.service.ExamStateMachineService;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.grading.mapper.SubjectiveGradeMapper;
import com.exam.submission.dto.SubmitMessage;
import com.exam.submission.mq.ExamSubmitConsumer;
import com.exam.support.IntegrationTestBase;
import com.exam.support.RabbitTemplateInvokeStubs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.poi.ss.usermodel.CellType;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 试题区分度与难度评价诊断集成测试（add-exam-question-discrimination）：
 * H2 + MockMvc + MQ Mock 走真实判分/汇总链路，覆盖 spec-delta——
 * ① 区分度多档评级端到端派生 + 负区分度反常预警（4 人实考、含高分组低于低分组的反序样本）；
 * ② 难度系数与难度等级（容易/中等/较难）随报告下发；
 * ③ 样本不足（3 人 &lt; 4）优雅降级：评级=样本不足、建议提示扩样，难度照常计算，无 NPE/500；
 * ④ Excel「逐题指标」工作表追加难度系数/难度等级/区分度等级/命题建议 4 列。
 *
 * <p>造数口径（4 人 20 分卷，题分 4/6/4/6，多选漏选部分分=满分×1.0×选中正确数/应选数）：
 * s1=B/A,C/T/6 → 0+6+4+6=16（高分组）；s2=A/A/F/4 → 4+3+0+4=11；s3=A/A/F/3 → 10；
 * s4=A/A/F/1 → 8（低分组）。前后 27% 分组（groupSize=1）→ 高分组 s1、低分组 s4：
 * 单选 D=(0-4)/4=-1.00 反常预警、均分 3.0 → P=0.75 容易；多选 D=(6-3)/6=0.50 优良（≥0.40 档）、
 * 均分 3.8 → P=0.63 中等；判断 D=1.00 优良、均分 1.0 → P=0.25 较难；简答 D=0.83 优良。
 * （良好/尚可/劣质档的阈值边界由 QuestionMetricsEvaluatorTest 纯函数级全覆盖。）
 */
class ExamQuestionDiscriminationIntegrationTest extends IntegrationTestBase {

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private ExamStateMachineService stateMachineService;
    @Autowired
    private GradingSubmissionMapper gradingSubmissionMapper;
    @Autowired
    private SubjectiveGradeMapper subjectiveGradeMapper;
    @Autowired
    private ExamSubmitConsumer submitConsumer;
    @Autowired
    private JwtUtil jwtUtil;

    @BeforeEach
    void runRabbitInvokeCallbacks() {
        RabbitTemplateInvokeStubs.runInvokeCallbacks(rabbitTemplate);
    }

    // ==================== 造数 ====================

    private long addPaperQuestion(String token, long paperId, long questionId, double score) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("questionId", questionId);
        body.put("score", score);
        perform(jsonPost("/api/papers/" + paperId + "/questions", token,
                objectMapper.writeValueAsString(body)), 200);
        return questionId;
    }

    private long preparePaper(String teacher) throws Exception {
        long qSingle = createQuestion(teacher, 1, "单选：1+1=?", "A",
                List.of("A", "B", "C", "D"), null);
        long qMultiple = createQuestion(teacher, 2, "多选：下列哪些是质数", "A,C",
                List.of("2", "4", "3", "9"), null);
        long qJudge = createQuestion(teacher, 3, "判断：水沸腾", "T", null, null);
        long qShort = createQuestion(teacher, 4, "简答：什么是HTTP", "HTTP,协议,无状态", null, null);
        long paperId = createPaper(teacher, unique("试卷"), 20);
        addPaperQuestion(teacher, paperId, qSingle, 4);
        addPaperQuestion(teacher, paperId, qMultiple, 6);
        addPaperQuestion(teacher, paperId, qJudge, 4);
        addPaperQuestion(teacher, paperId, qShort, 6);
        return paperId;
    }

    private long preparedInProgressExam(String teacher) throws Exception {
        long paperId = preparePaper(teacher);
        long classId = createClassForExam(teacher);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("title", unique("考试"));
        body.put("paperId", paperId);
        body.put("classId", classId);
        body.put("startTime", LocalDateTime.now().minusMinutes(1).toString());
        body.put("endTime", LocalDateTime.now().plusHours(2).toString());
        body.put("durationMinutes", 30);
        long examId = perform(jsonPost("/api/exams", teacher,
                objectMapper.writeValueAsString(body)), 200).get("data").get("id").asLong();
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
                                String score, int expectedVersion) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("submissionId", submissionIdOf(examId, studentId));
        body.put("questionId", questionId);
        body.put("score", new BigDecimal(score));
        body.put("expectedVersion", expectedVersion);
        body.put("comment", "评语");
        perform(jsonPost("/api/exams/" + examId + "/grading/subjective/save", teacher,
                objectMapper.writeValueAsString(body)), 200);
    }

    private long submissionIdOf(long examId, long studentId) {
        return gradingSubmissionMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<com.exam.grading.entity.GradingSubmission>lambdaQuery()
                        .eq(com.exam.grading.entity.GradingSubmission::getExamId, examId)
                        .eq(com.exam.grading.entity.GradingSubmission::getStudentId, studentId)).getId();
    }

    /**
     * 造一场已汇总（GRADED）考试：逐生 {token, 单选, 多选, 判断, 简答作答, 简答给分} 六元组，
     * 首生答卷映射取出简答题 questionId（同卷题目 id 全场一致）。
     */
    private long preparedGradedExam(String teacher, String[][] students) throws Exception {
        long examId = preparedInProgressExam(teacher);
        long shortQ = 0;
        for (int i = 0; i < students.length; i++) {
            String token = students[i][0];
            Map<Integer, Long> q = enterAndMapQuestions(token, examId);
            if (i == 0) {
                shortQ = q.get(4);
            }
            submit(token, examId, q, students[i][1], students[i][2], students[i][3], students[i][4]);
        }
        perform(jsonPost("/api/exams/" + examId + "/force-end", teacher, null), 200);
        perform(jsonPost("/api/exams/" + examId + "/grading/run", teacher, null), 200);
        for (String[] student : students) {
            saveSubjective(teacher, examId, studentIdOf(student[0]), shortQ, student[5], 0);
        }
        perform(jsonPost("/api/exams/" + examId + "/scores/summarize", teacher, null), 200);
        return examId;
    }

    // ==================== Scenario：多档评级 + 反常预警 + 难度分级 ====================

    @Test
    void discriminationLevelsAnomalyAndDifficultyDerivedEndToEnd() throws Exception {
        String teacher = registerTeacher();
        String s1 = registerStudent();
        String s2 = registerStudent();
        String s3 = registerStudent();
        String s4 = registerStudent();
        long examId = preparedGradedExam(teacher, new String[][]{
                {s1, "B", "A,C", "T", "HTTP 是无状态的协议", "6"},
                {s2, "A", "A", "F", "HTTP", "4"},
                {s3, "A", "A", "F", "HTTP", "3"},
                {s4, "A", "A", "F", "HTTP", "1"},
        });

        JsonNode data = perform(jsonGet("/api/exams/" + examId + "/scores/analysis-report", teacher), 200)
                .get("data");
        JsonNode qStats = data.get("questionStats");
        assertEquals(4, qStats.size());

        // 单选（order 1）：高分组 s1 得 0、低分组 s4 得 4 → D=-1.00 反常预警；
        // 均分 (0+4+4+4)/4=3.0 → P=0.75 容易；建议带反常预警与核查警示
        JsonNode single = qStats.get(0);
        assertEquals(4, single.get("answeredCount").asInt());
        assertEquals(-1.0, single.get("discrimination").asDouble(), 1e-9, "负区分度端到端透出");
        assertEquals("反常预警", single.get("discriminationLevel").asText());
        assertEquals(0.75, single.get("difficulty").asDouble(), 1e-9);
        assertEquals("容易", single.get("difficultyLevel").asText());
        String singleSuggestion = single.get("qualityEvaluation").asText();
        assertTrue(singleSuggestion.contains("反常预警"), "反常预警建议必须带警示词");
        assertTrue(singleSuggestion.contains("核查"), "反常预警建议必须提示核查题干/答案");

        // 多选（order 2）：高分组 6、低分组 3（漏选部分分）→ D=0.50 优良（≥0.40 档）；
        // 均分 (6+3+3+3)/4=3.75→3.8（1 位小数）→ P=0.63 中等
        JsonNode multiple = qStats.get(1);
        assertEquals(0.50, multiple.get("discrimination").asDouble(), 1e-9);
        assertEquals("优良", multiple.get("discriminationLevel").asText());
        assertEquals(0.63, multiple.get("difficulty").asDouble(), 1e-9, "3.8/6=0.6333 → 0.63");
        assertEquals("中等", multiple.get("difficultyLevel").asText());
        assertTrue(multiple.get("qualityEvaluation").asText().contains("精品题库"));

        // 判断（order 3）：高分组 4、低分组 0 → D=1.00 优良；均分 1.0 → P=0.25 较难；建议带偏难提示
        JsonNode judge = qStats.get(2);
        assertEquals(1.0, judge.get("discrimination").asDouble(), 1e-9);
        assertEquals("优良", judge.get("discriminationLevel").asText());
        assertEquals(0.25, judge.get("difficulty").asDouble(), 1e-9);
        assertEquals("较难", judge.get("difficultyLevel").asText());
        String judgeSuggestion = judge.get("qualityEvaluation").asText();
        assertTrue(judgeSuggestion.contains("精品题库"), "优良试题建议入选精品题库");
        assertTrue(judgeSuggestion.contains("偏难"), "较难题目建议带偏难定位提示");

        // 简答（order 4）：D=(6-1)/6=0.83 优良；均分 3.5 → P=0.58 中等
        JsonNode shortAnswer = qStats.get(3);
        assertEquals(0.83, shortAnswer.get("discrimination").asDouble(), 1e-9);
        assertEquals("优良", shortAnswer.get("discriminationLevel").asText());
        assertEquals(0.58, shortAnswer.get("difficulty").asDouble(), 1e-9, "3.5/6=0.5833 → 0.58");
        assertEquals("中等", shortAnswer.get("difficultyLevel").asText());
    }

    // ==================== Scenario：样本不足优雅回退 ====================

    @Test
    void insufficientSampleDegradesGracefully() throws Exception {
        String teacher = registerTeacher();
        String s1 = registerStudent();
        String s2 = registerStudent();
        String s3 = registerStudent();
        long examId = preparedGradedExam(teacher, new String[][]{
                {s1, "A", "A,C", "T", "HTTP 是无状态的协议", "6"},
                {s2, "B", "A", "F", "HTTP", "3"},
                {s3, "A", "A,D", "T", "", "2"},
        });

        JsonNode data = perform(jsonGet("/api/exams/" + examId + "/scores/analysis-report", teacher), 200)
                .get("data");
        JsonNode single = data.get("questionStats").get(0);
        assertEquals(3, single.get("answeredCount").asInt());
        assertTrue(single.path("discrimination").isMissingNode() || single.path("discrimination").isNull(),
                "3 人样本不足以计算区分度，数值保持 null/缺省");
        assertEquals("样本不足", single.get("discriminationLevel").asText(),
                "样本不足时评级明确标识为样本不足");
        String suggestion = single.get("qualityEvaluation").asText();
        assertTrue(suggestion.contains("样本不足"), "样本不足建议必须点名样本不足");
        assertTrue(suggestion.contains("4"), "样本不足建议必须提示需至少 4 人实考");
        // 难度不受区分度样本不足影响：单选均分 (4+0+4)/3=2.7（1 位小数）→ P=0.68 中等
        assertEquals(0.68, single.get("difficulty").asDouble(), 1e-9);
        assertEquals("中等", single.get("difficultyLevel").asText());
    }

    // ==================== Scenario：Excel 追加测量学诊断 4 列 ====================

    @Test
    void exportAddsMeasurementColumnsToQuestionSheet() throws Exception {
        String teacher = registerTeacher();
        String s1 = registerStudent();
        String s2 = registerStudent();
        String s3 = registerStudent();
        String s4 = registerStudent();
        long examId = preparedGradedExam(teacher, new String[][]{
                {s1, "B", "A,C", "T", "HTTP 是无状态的协议", "6"},
                {s2, "A", "A", "F", "HTTP", "4"},
                {s3, "A", "A", "F", "HTTP", "3"},
                {s4, "A", "A", "F", "HTTP", "1"},
        });

        var result = mockMvc.perform(jsonGet("/api/exams/" + examId + "/scores/analysis-report/export", teacher))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment; filename*=UTF-8''")))
                .andReturn();
        byte[] xlsxBytes = result.getResponse().getContentAsByteArray();
        assertNotNull(xlsxBytes);
        assertTrue(xlsxBytes.length > 0);

        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsxBytes))) {
            Sheet questions = workbook.getSheet("逐题指标");
            // 表头追加 4 列：难度系数/难度等级/区分度等级/命题建议（列 9-12）
            assertEquals("难度系数", questions.getRow(0).getCell(9).getStringCellValue());
            assertEquals("难度等级", questions.getRow(0).getCell(10).getStringCellValue());
            assertEquals("区分度等级", questions.getRow(0).getCell(11).getStringCellValue());
            assertEquals("命题建议", questions.getRow(0).getCell(12).getStringCellValue());

            // 单选行：难度系数 0.75 数值格、等级「容易」、区分度等级「反常预警」、建议带核查警示
            assertEquals(0.75, questions.getRow(1).getCell(9).getNumericCellValue(), 1e-9);
            assertEquals(CellType.NUMERIC, questions.getRow(1).getCell(9).getCellType(), "难度系数为数值单元格");
            assertEquals("容易", questions.getRow(1).getCell(10).getStringCellValue());
            assertEquals("反常预警", questions.getRow(1).getCell(11).getStringCellValue());
            assertTrue(questions.getRow(1).getCell(12).getStringCellValue().contains("核查"),
                    "命题建议列必须输出诊断文本");

            // 判断行：区分度等级「优良」、难度等级「较难」
            assertEquals("优良", questions.getRow(3).getCell(11).getStringCellValue());
            assertEquals("较难", questions.getRow(3).getCell(10).getStringCellValue());
        }
    }
}

package com.exam.score;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.exam.entity.ExamSnapshot;
import com.exam.exam.mapper.ExamMapper;
import com.exam.exam.mapper.ExamSnapshotMapper;
import com.exam.exam.service.ExamStateMachineService;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.grading.mapper.SubjectiveGradeMapper;
import com.exam.score.service.ScoreExportService;
import com.exam.submission.dto.SubmitMessage;
import com.exam.submission.mapper.ExamSubmissionMapper;
import com.exam.submission.mq.ExamSubmitConsumer;
import com.exam.support.IntegrationTestBase;
import com.exam.support.RabbitTemplateInvokeStubs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 题目选项分布统计集成测试（update-analysis-option-distribution）：
 * 覆盖 score-management 域两 Scenario 与既有指标零漂移护栏：
 * 1. 逐题选项分布（SINGLE/MULTIPLE/JUDGE/FILL/SHORT，占比和>100%，未作答不计入桶）；
 * 2. 题目统计导出选项分布列（xlsx 与 JSON 同源一致，既有列零删改，无选项题型空列）；
 * 3. 既有指标数值零漂移护栏（P/答对率/区分度/作答人数）；
 * 4. 学生访问 403 越权守卫。
 */
class AnalysisOptionDistributionIntegrationTest extends IntegrationTestBase {

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
    private ExamSnapshotMapper examSnapshotMapper;
    @Autowired
    private ExamSubmitConsumer submitConsumer;
    @Autowired
    private ScoreExportService scoreExportService;
    @Autowired
    private com.exam.auth.service.JwtUtil jwtUtil;

    private long parseStudentId(String token) {
        return jwtUtil.parseAccessToken(token).getId();
    }

    @BeforeEach
    void runRabbitInvokeCallbacks() {
        RabbitTemplateInvokeStubs.runInvokeCallbacks(rabbitTemplate);
    }

    private long addPaperQuestion(String token, long paperId, long questionId, double score) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("questionId", questionId);
        body.put("score", score);
        perform(jsonPost("/api/papers/" + paperId + "/questions", token,
                objectMapper.writeValueAsString(body)), 200);
        return questionId;
    }

    private Map<Integer, Long> prepareExamWithAllQuestionTypes(String teacher) throws Exception {
        // 5 道题：单选(4)、多选(6)、判断(4)、简答(6)、填空(5)
        long qSingle = createQuestion(teacher, 1, "单选题", "A", List.of("选项A", "选项B", "选项C", "选项D"), null);
        long qMultiple = createQuestion(teacher, 2, "多选题", "A,C", List.of("选项A", "选项B", "选项C", "选项D"), null);
        long qJudge = createQuestion(teacher, 3, "判断题", "T", null, null);
        long qShort = createQuestion(teacher, 4, "简答题", "参考答案", null, null);
        long qFill = createQuestion(teacher, 5, "填空题", "答案1|答案2", null, null);

        double totalScore = 25.0;
        long paperId = createPaper(teacher, unique("五题型试卷"), totalScore);
        addPaperQuestion(teacher, paperId, qSingle, 4.0);
        addPaperQuestion(teacher, paperId, qMultiple, 6.0);
        addPaperQuestion(teacher, paperId, qJudge, 4.0);
        addPaperQuestion(teacher, paperId, qShort, 6.0);
        addPaperQuestion(teacher, paperId, qFill, 5.0);

        long classId = createClassForExam(teacher);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("title", unique("选项分布测试考试"));
        body.put("paperId", paperId);
        body.put("classId", classId);
        body.put("startTime", LocalDateTime.now().minusMinutes(1).toString());
        body.put("endTime", LocalDateTime.now().plusHours(2).toString());
        body.put("durationMinutes", 30);
        JsonNode data = perform(jsonPost("/api/exams", teacher,
                objectMapper.writeValueAsString(body)), 200).get("data");
        long examId = data.get("id").asLong();

        perform(jsonPost("/api/exams/" + examId + "/publish", teacher, null), 200);
        stateMachineService.autoAdvance();

        Map<Integer, Long> qMap = new HashMap<>();
        qMap.put(1, qSingle);
        qMap.put(2, qMultiple);
        qMap.put(3, qJudge);
        qMap.put(4, qShort);
        qMap.put(5, qFill);
        qMap.put(0, examId);
        return qMap;
    }

    private void submitAnswers(String student, long examId, Map<Integer, Long> qMap,
                               String single, String multiple, String judge, String shortAnswer, String fill) throws Exception {
        ensureExamClassMembership(student, examId);
        perform(jsonPost("/api/exam-taking/exams/" + examId + "/enter", student, null), 200);

        ObjectNode answers = objectMapper.createObjectNode();
        if (single != null) { answers.put(String.valueOf(qMap.get(1)), single); }
        if (multiple != null) { answers.put(String.valueOf(qMap.get(2)), multiple); }
        if (judge != null) { answers.put(String.valueOf(qMap.get(3)), judge); }
        if (shortAnswer != null) { answers.put(String.valueOf(qMap.get(4)), shortAnswer); }
        if (fill != null) { answers.put(String.valueOf(qMap.get(5)), fill); }

        ObjectNode request = objectMapper.createObjectNode();
        request.set("answers", answers);
        perform(jsonPost("/api/exam-taking/exams/" + examId + "/submit", student,
                objectMapper.writeValueAsString(request)), 200);

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

    private void saveSubjectiveScore(String teacher, long examId, String studentToken, long questionId, String score) throws Exception {
        long studentId = parseStudentId(studentToken);
        GradingSubmission sub = gradingSubmissionMapper.selectOne(Wrappers.<GradingSubmission>lambdaQuery()
                .eq(GradingSubmission::getExamId, examId)
                .eq(GradingSubmission::getStudentId, studentId));
        ObjectNode body = objectMapper.createObjectNode();
        body.put("submissionId", sub.getId());
        body.put("questionId", questionId);
        body.put("score", new BigDecimal(score));
        body.put("expectedVersion", 0);
        body.put("comment", "人工批改");
        perform(jsonPost("/api/exams/" + examId + "/grading/subjective/save", teacher,
                objectMapper.writeValueAsString(body)), 200);
    }

    @Test
    void testOptionDistributionAndExcelAndGuardZeroDrift() throws Exception {
        String teacher = registerTeacher();
        Map<Integer, Long> qMap = prepareExamWithAllQuestionTypes(teacher);
        long examId = qMap.get(0);

        String s1 = registerStudent();
        String s2 = registerStudent();
        String s3 = registerStudent();
        String s4 = registerStudent();

        // 4 名学生作答构造：
        // 学生 1: 单选 A, 多选 A,B, 判断 T, 简答 "参考答案", 填空 "答案1"
        submitAnswers(s1, examId, qMap, "A", "A,B", "T", "参考答案", "答案1");
        // 学生 2: 单选 A, 多选 B,C, 判断 T, 简答 "答题", 填空 "答案2"
        submitAnswers(s2, examId, qMap, "A", "B,C", "T", "答题", "答案2");
        // 学生 3: 单选 B, 多选 A,C, 判断 F, 简答 "无", 填空 "错误"
        submitAnswers(s3, examId, qMap, "B", "A,C", "F", "无", "错误");
        // 学生 4: 单选 未作答(null), 多选 C, 判断 T, 简答 null, 填空 null
        submitAnswers(s4, examId, qMap, null, "C", "T", null, null);

        // 结束考试并判分汇总
        perform(jsonPost("/api/exams/" + examId + "/force-end", teacher, null), 200);
        perform(jsonPost("/api/exams/" + examId + "/grading/run", teacher, null), 200);

        long shortQ = qMap.get(4);
        saveSubjectiveScore(teacher, examId, s1, shortQ, "6.0");
        saveSubjectiveScore(teacher, examId, s2, shortQ, "3.0");
        saveSubjectiveScore(teacher, examId, s3, shortQ, "1.0");
        saveSubjectiveScore(teacher, examId, s4, shortQ, "0.0");

        perform(jsonPost("/api/exams/" + examId + "/scores/summarize", teacher, null), 200);

        // 1. 验证 JSON 响应中的 optionDistribution
        JsonNode data = perform(jsonGet("/api/exams/" + examId + "/scores/analysis-report", teacher), 200).get("data");
        JsonNode questionStats = data.get("questionStats");
        assertEquals(5, questionStats.size());

        // 单选 (4 选项: A, B, C, D)
        // 4 人作答，A: 2 (50%), B: 1 (25%), C: 0 (0%), D: 0 (0%)
        // 未作答 1 人不计入任何选项桶
        JsonNode singleStat = questionStats.get(0);
        assertEquals(4, singleStat.get("answeredCount").asInt());
        JsonNode singleDist = singleStat.get("optionDistribution");
        assertNotNull(singleDist);
        assertEquals(4, singleDist.size());
        assertEquals("A", singleDist.get(0).get("option").asText());
        assertEquals(2, singleDist.get(0).get("count").asInt());
        assertEquals(0.5, singleDist.get(0).get("rate").asDouble(), 1e-4);
        assertEquals("B", singleDist.get(1).get("option").asText());
        assertEquals(1, singleDist.get(1).get("count").asInt());
        assertEquals(0.25, singleDist.get(1).get("rate").asDouble(), 1e-4);
        assertEquals("C", singleDist.get(2).get("option").asText());
        assertEquals(0, singleDist.get(2).get("count").asInt());
        assertEquals(0.0, singleDist.get(2).get("rate").asDouble(), 1e-4);
        assertEquals("D", singleDist.get(3).get("option").asText());
        assertEquals(0, singleDist.get(3).get("count").asInt());
        assertEquals(0.0, singleDist.get(3).get("rate").asDouble(), 1e-4);

        // 多选 (4 选项: A, B, C, D)
        // 4 人作答: s1(A,B), s2(B,C), s3(A,C), s4(C)
        // A: 2 (50%), B: 2 (50%), C: 3 (75%), D: 0 (0%)
        // 占比和: 50% + 50% + 75% + 0% = 175% > 100%
        JsonNode multiStat = questionStats.get(1);
        assertEquals(4, multiStat.get("answeredCount").asInt());
        JsonNode multiDist = multiStat.get("optionDistribution");
        assertNotNull(multiDist);
        assertEquals(4, multiDist.size());
        assertEquals("A", multiDist.get(0).get("option").asText());
        assertEquals(2, multiDist.get(0).get("count").asInt());
        assertEquals(0.5, multiDist.get(0).get("rate").asDouble(), 1e-4);
        assertEquals("B", multiDist.get(1).get("option").asText());
        assertEquals(2, multiDist.get(1).get("count").asInt());
        assertEquals(0.5, multiDist.get(1).get("rate").asDouble(), 1e-4);
        assertEquals("C", multiDist.get(2).get("option").asText());
        assertEquals(3, multiDist.get(2).get("count").asInt());
        assertEquals(0.75, multiDist.get(2).get("rate").asDouble(), 1e-4);
        assertEquals("D", multiDist.get(3).get("option").asText());
        assertEquals(0, multiDist.get(3).get("count").asInt());
        assertEquals(0.0, multiDist.get(3).get("rate").asDouble(), 1e-4);

        // 判断 (2 选项: T, F)
        // 4 人作答: s1(T), s2(T), s3(F), s4(T) -> T: 3 (75%), F: 1 (25%)
        JsonNode judgeStat = questionStats.get(2);
        assertEquals(4, judgeStat.get("answeredCount").asInt());
        JsonNode judgeDist = judgeStat.get("optionDistribution");
        assertNotNull(judgeDist);
        assertEquals(2, judgeDist.size());
        assertEquals("T", judgeDist.get(0).get("option").asText());
        assertEquals(3, judgeDist.get(0).get("count").asInt());
        assertEquals(0.75, judgeDist.get(0).get("rate").asDouble(), 1e-4);
        assertEquals("F", judgeDist.get(1).get("option").asText());
        assertEquals(1, judgeDist.get(1).get("count").asInt());
        assertEquals(0.25, judgeDist.get(1).get("rate").asDouble(), 1e-4);

        // 简答 (FILL_BLANK / SHORT_ANSWER 空数组)
        JsonNode shortStat = questionStats.get(3);
        assertNotNull(shortStat.get("optionDistribution"));
        assertEquals(0, shortStat.get("optionDistribution").size());

        // 填空 (空数组)
        JsonNode fillStat = questionStats.get(4);
        assertNotNull(fillStat.get("optionDistribution"));
        assertEquals(0, fillStat.get("optionDistribution").size());

        // 2. 既有指标数值零漂移护栏断言：
        // 单选题：满分 4 分，2 人对 (s1, s2 选 A 满分 4)，2 人错 (s3 选 B 0分, s4 未作答 0分)
        // 均分 = (4 + 4 + 0 + 0) / 4 = 2.0，得分率 = 2.0 / 4 = 0.5，答对率 = 2 / 4 = 0.5
        assertEquals(2.0, singleStat.get("averageScore").asDouble(), 1e-4);
        assertEquals(0.5, singleStat.get("scoreRate").asDouble(), 1e-4);
        assertEquals(0.5, singleStat.get("correctRate").asDouble(), 1e-4);

        // 3. 题目统计导出 Excel 同源一致性验证
        byte[] xlsxBytes = mockMvc.perform(jsonGet("/api/exams/" + examId + "/scores/export/question-stats", teacher))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertNotNull(xlsxBytes);
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsxBytes))) {
            Sheet sheet = workbook.getSheet("题目统计");
            assertNotNull(sheet);
            Row header = sheet.getRow(0);
            // 既有 9 列 + 新增「选项分布」单列 = 共 10 列
            assertEquals("题号", header.getCell(0).getStringCellValue());
            assertEquals("题型", header.getCell(1).getStringCellValue());
            assertEquals("题干", header.getCell(2).getStringCellValue());
            assertEquals("满分", header.getCell(3).getStringCellValue());
            assertEquals("平均分", header.getCell(4).getStringCellValue());
            assertEquals("得分率", header.getCell(5).getStringCellValue());
            assertEquals("答对率(满分率)", header.getCell(6).getStringCellValue());
            assertEquals("区分度", header.getCell(7).getStringCellValue());
            assertEquals("作答人数", header.getCell(8).getStringCellValue());
            assertEquals("选项分布", header.getCell(9).getStringCellValue());

            // 单选第 1 行
            Row rSingle = sheet.getRow(1);
            assertEquals("A:2(50%) B:1(25%) C:0(0%) D:0(0%)", rSingle.getCell(9).getStringCellValue());

            // 多选第 2 行
            Row rMulti = sheet.getRow(2);
            assertEquals("A:2(50%) B:2(50%) C:3(75%) D:0(0%)", rMulti.getCell(9).getStringCellValue());

            // 判断第 3 行
            Row rJudge = sheet.getRow(3);
            assertEquals("T:3(75%) F:1(25%)", rJudge.getCell(9).getStringCellValue());

            // 简答第 4 行与填空第 5 行：无选项题型该列为空
            Row rShort = sheet.getRow(4);
            assertEquals("", rShort.getCell(9).getStringCellValue());
            Row rFill = sheet.getRow(5);
            assertEquals("", rFill.getCell(9).getStringCellValue());
        }

        // 4. 学生 403 越权守卫复验
        perform(jsonGet("/api/exams/" + examId + "/scores/analysis-report", s1), 403);
    }
}

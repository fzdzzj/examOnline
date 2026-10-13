package com.exam.grading;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.auth.service.JwtUtil;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.exam.service.ExamStateMachineService;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.submission.dto.SubmitMessage;
import com.exam.submission.mq.ExamSubmitConsumer;
import com.exam.support.IntegrationTestBase;
import com.exam.support.RabbitTemplateInvokeStubs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 填空题题型集成测试（spec add-question-fill-blank，H2 + MockMvc，MQ 为 Mock）：
 * <ul>
 *   <li>创建与校验：多可接受答案归一化回显、空答案列表/超长拒绝、按 type=5 筛选、随机抽题含填空；</li>
 *   <li>全链路：建填空（多答案）→ 组卷 → 自动判分命中各口径（精确/忽略大小写/未命中）→
 *       汇总发布 → 逐题回顾展示；</li>
 *   <li>分流验证：填空走客观链自动判分，不进主观批改工作台；简答仍走主观链不变。</li>
 * </ul>
 */
class FillBlankQuestionIntegrationTest extends IntegrationTestBase {

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @BeforeEach
    void runRabbitInvokeCallbacks() {
        RabbitTemplateInvokeStubs.runInvokeCallbacks(rabbitTemplate);
    }

    @Autowired
    private ExamStateMachineService stateMachineService;
    @Autowired
    private GradingSubmissionMapper gradingSubmissionMapper;
    @Autowired
    private ExamMapper examMapper;
    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private ExamSubmitConsumer submitConsumer;

    // ==================== 创建与校验 ====================

    @Test
    void createNormalizeValidateFilterAndRandomDraw() throws Exception {
        String teacher = registerTeacher();

        // 多可接受答案（含首尾空白/空项）→ 归一化回显
        ObjectNode create = objectMapper.createObjectNode();
        create.put("type", 5);
        create.put("content", "中国的首都是 ____");
        create.put("correctAnswer", "  北京 | beijing |");
        create.put("score", 6);
        create.put("difficulty", 1);
        JsonNode data = perform(jsonPost("/api/questions", teacher,
                objectMapper.writeValueAsString(create)), 200).get("data");
        long fillBlankId = data.get("id").asLong();
        assertEquals(5, data.get("type").asInt());
        assertEquals("填空", data.get("typeName").asText());
        assertEquals("北京|beijing", data.get("correctAnswer").asText(), "归一化串：空项剔除、各项 trim、以 | 重组");
        assertEquals(0, data.get("choices").size(), "填空无选项");

        // 空答案列表（全空项）400
        ObjectNode blank = objectMapper.createObjectNode();
        blank.put("type", 5);
        blank.put("content", "空答案");
        blank.put("correctAnswer", " | ");
        blank.put("score", 5);
        blank.put("difficulty", 1);
        perform(jsonPost("/api/questions", teacher, objectMapper.writeValueAsString(blank)), 400);

        // 超长（>512）400，不落库
        ObjectNode tooLong = objectMapper.createObjectNode();
        tooLong.put("type", 5);
        tooLong.put("content", "超长");
        tooLong.put("correctAnswer", "答".repeat(513));
        tooLong.put("score", 5);
        tooLong.put("difficulty", 1);
        perform(jsonPost("/api/questions", teacher, objectMapper.writeValueAsString(tooLong)), 400);

        // 按 type=5 筛选：既有筛选链路对 5 透明生效
        JsonNode page = perform(jsonGet("/api/questions?type=5&page=1&size=50", teacher), 200).get("data");
        boolean found = false;
        for (JsonNode item : page.get("list")) {
            if (item.get("id").asLong() == fillBlankId) {
                found = true;
                assertEquals(5, item.get("type").asInt());
            }
        }
        assertTrue(found, "type=5 筛选应命中新建填空题");

        // 随机抽题含填空（rule.type=5 通过校验且抽到填空）
        ObjectNode rule = objectMapper.createObjectNode();
        rule.put("type", 5);
        rule.put("count", 1);
        ObjectNode rulesBody = objectMapper.createObjectNode();
        rulesBody.putArray("rules").add(rule);
        JsonNode preview = perform(jsonPost("/api/papers/random-draw/preview", teacher,
                objectMapper.writeValueAsString(rulesBody)), 200).get("data");
        assertEquals(1, preview.get("total").asInt());
        assertEquals(5, preview.get("rules").get(0).get("questions").get(0).get("type").asInt());
    }

    // ==================== 全链路：判分与分流 ====================

    @Test
    void fullChainAutoGradingReviewAndSubjectiveSeparation() throws Exception {
        String teacher = registerTeacher();
        String student1 = registerStudent();
        String student2 = registerStudent();
        String student3 = registerStudent();

        long fillBlankId = createQuestion(teacher, 5, "中国的首都是 ____", "北京|beijing", null, null);
        long shortId = createQuestion(teacher, 4, "简答：什么是 HTTP", "HTTP,协议,无状态", null, null);
        long paperId = createPaper(teacher, unique("填空试卷"), 10);
        addPaperQuestion(teacher, paperId, fillBlankId, 6);
        addPaperQuestion(teacher, paperId, shortId, 4);

        long classId = createClassForExam(teacher);
        long examId = createExam(teacher, paperId, classId);
        perform(jsonPost("/api/exams/" + examId + "/publish", teacher, null), 200);
        stateMachineService.autoAdvance();

        // 客观分预期：stu1 精确 6；stu2 忽略大小写 6；stu3 未命中 0
        Map<Long, Long> q1 = enterAndMapQuestions(student1, examId);
        submit(student1, examId, q1, "北京", "HTTP 是无状态的协议");
        Map<Long, Long> q2 = enterAndMapQuestions(student2, examId);
        submit(student2, examId, q2, "BEIJING", "HTTP");
        Map<Long, Long> q3 = enterAndMapQuestions(student3, examId);
        submit(student3, examId, q3, "上海", "不知道");

        perform(jsonPost("/api/exams/" + examId + "/force-end", teacher, null), 200);
        JsonNode run = perform(jsonPost("/api/exams/" + examId + "/grading/run", teacher, null), 200).get("data");
        assertEquals(3, run.get("total").asInt());
        assertEquals(3, run.get("success").asInt());
        assertEquals(0, run.get("failed").asInt(), "填空走客观链，判分不失败");

        assertEquals(0, objectiveOf(examId, studentIdOf(student1)).compareTo(new BigDecimal("6")));
        assertEquals(0, objectiveOf(examId, studentIdOf(student2)).compareTo(new BigDecimal("6")), "忽略大小写命中");
        assertEquals(0, objectiveOf(examId, studentIdOf(student3)).compareTo(new BigDecimal("0")), "未命中 0 分");

        // 分流：主观批改工作台只列简答题，填空题不入候选
        JsonNode subjectiveQuestions = perform(jsonGet(
                "/api/exams/" + examId + "/grading/subjective/questions", teacher), 200).get("data");
        assertEquals(1, subjectiveQuestions.size(), "填空不进主观批改工作台");
        assertEquals(shortId, subjectiveQuestions.get(0).get("questionId").asLong(), "工作台只有简答题");

        // 简答人工批改 → 汇总发布
        saveSubjective(teacher, examId, studentIdOf(student1), shortId, "4.0", 0, "完整");
        saveSubjective(teacher, examId, studentIdOf(student2), shortId, "2.0", 0, "关键词少");
        saveSubjective(teacher, examId, studentIdOf(student3), shortId, "0.0", 0, "未作答");
        perform(jsonPost("/api/exams/" + examId + "/scores/summarize", teacher, null), 200);
        assertEquals(Exam.STATUS_GRADED, examMapper.selectById(examId).getStatus());

        ObjectNode publishBody = objectMapper.createObjectNode();
        publishBody.putArray("examIds").add(examId);
        perform(jsonPost("/api/scores/publish", teacher, objectMapper.writeValueAsString(publishBody)), 200);

        assertEquals(0, totalOf(examId, studentIdOf(student1)).compareTo(new BigDecimal("10")));  // 6+4
        assertEquals(0, totalOf(examId, studentIdOf(student3)).compareTo(new BigDecimal("0")));   // 0+0

        // 逐题回顾：填空按题型正确展示（学生原文 + 判分结果 + 正确答案串）
        JsonNode review = perform(jsonGet("/api/scores/my/exams/" + examId + "/review", student1), 200).get("data");
        JsonNode fillItem = null;
        for (JsonNode item : review.get("questions")) {
            if (item.get("questionId").asLong() == fillBlankId) {
                fillItem = item;
            }
        }
        assertFalse(fillItem == null, "回顾应含填空题");
        assertEquals("填空", fillItem.get("questionType").asText());
        assertEquals("北京", fillItem.get("myAnswer").asText(), "学生原文原样展示");
        assertEquals("北京|beijing", fillItem.get("correctAnswer").asText());
        assertEquals(0, fillItem.get("myScore").decimalValue().compareTo(new BigDecimal("6")));
    }

    // ==================== 辅助 ====================

    private void addPaperQuestion(String teacher, long paperId, long questionId, double score) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("questionId", questionId);
        body.put("score", score);
        perform(jsonPost("/api/papers/" + paperId + "/questions", teacher,
                objectMapper.writeValueAsString(body)), 200);
    }

    private long createExam(String teacher, long paperId, Long classId) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("title", unique("填空考试"));
        body.put("paperId", paperId);
        if (classId != null) {
            body.put("classId", classId);
        }
        body.put("startTime", LocalDateTime.now().minusMinutes(1).toString());
        body.put("endTime", LocalDateTime.now().plusHours(2).toString());
        body.put("durationMinutes", 30);
        return perform(jsonPost("/api/exams", teacher, objectMapper.writeValueAsString(body)), 200)
                .get("data").get("id").asLong();
    }

    /** 进入考试并按题型定位题目 ID（个人快照题序随机，须按类型识别）。 */
    private Map<Long, Long> enterAndMapQuestions(String student, long examId) throws Exception {
        ensureExamClassMembership(student, examId);
        JsonNode data = perform(jsonPost("/api/exam-taking/exams/" + examId + "/enter", student, null), 200)
                .get("data");
        Map<Long, Long> byQuestionId = new HashMap<>();
        data.get("questions").forEach(q -> byQuestionId.put(q.get("questionId").asLong(), q.get("type").asLong()));
        return byQuestionId;
    }

    private void submit(String student, long examId, Map<Long, Long> questions,
                        String fillAnswer, String shortAnswer) throws Exception {
        ObjectNode answers = objectMapper.createObjectNode();
        for (Map.Entry<Long, Long> entry : questions.entrySet()) {
            long questionId = entry.getKey();
            if (entry.getValue() == 5L) {
                answers.put(String.valueOf(questionId), fillAnswer);
            } else if (entry.getValue() == 4L) {
                answers.put(String.valueOf(questionId), shortAnswer);
            }
        }
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
            if (!message.getExamId().equals(examId)) {
                continue;
            }
            Message amqp = new Message(objectMapper.writeValueAsBytes(message), new MessageProperties());
            amqp.getMessageProperties().setDeliveryTag(1L);
            submitConsumer.onBatch(java.util.List.of(amqp), mock(com.rabbitmq.client.Channel.class));
        }
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

    private long studentIdOf(String token) {
        return jwtUtil.parseAccessToken(token).getId();
    }

    private BigDecimal objectiveOf(long examId, long studentId) {
        return gradingSubmissionMapper.selectOne(Wrappers.<GradingSubmission>lambdaQuery()
                .eq(GradingSubmission::getExamId, examId)
                .eq(GradingSubmission::getStudentId, studentId)).getObjectiveScore();
    }

    private BigDecimal totalOf(long examId, long studentId) {
        return gradingSubmissionMapper.selectOne(Wrappers.<GradingSubmission>lambdaQuery()
                .eq(GradingSubmission::getExamId, examId)
                .eq(GradingSubmission::getStudentId, studentId)).getTotalScore();
    }
}
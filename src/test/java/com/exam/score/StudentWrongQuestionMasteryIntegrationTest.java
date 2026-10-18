package com.exam.score;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.exam.service.ExamStateMachineService;
import com.exam.score.entity.PracticeAttempt;
import com.exam.score.mapper.PracticeAttemptMapper;
import com.exam.submission.dto.SubmitMessage;
import com.exam.submission.mq.ExamSubmitConsumer;
import com.exam.support.IntegrationTestBase;
import com.exam.support.RabbitTemplateInvokeStubs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * 错题掌握度三态与强化自测组卷交卷集成测试（spec add-student-wrong-question-mastery）：
 * 走真实链路（建题 → 组卷发布 → 进入作答 → 判分发布 → 错题本/组卷/交卷）：
 * <ul>
 *   <li>错题本与重练池条目显式携带 masteryStatus/masteryStatusName 三态，
 *       单题反馈与列表三态随连对递增流转 UNTOUCHED→PRACTICING；</li>
 *   <li>generate-quiz：UUID 试卷、题量截断、PRACTICING_FIRST 优先攻克中、作答前不泄露答案与解析；</li>
 *   <li>submit-quiz：真实客观判分同源、逐题落 practice_attempt（零 DDL）、
 *       正确率与新消灭数、三态晋级流转；</li>
 *   <li>越权与参数防御：非本人错题整单 400 零写入、题量/策略校验、空作答列表校验失败。</li>
 * </ul>
 */
class StudentWrongQuestionMasteryIntegrationTest extends IntegrationTestBase {

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private ExamStateMachineService stateMachineService;

    @Autowired
    private ExamSubmitConsumer submitConsumer;

    @Autowired
    private PracticeAttemptMapper practiceAttemptMapper;

    @BeforeEach
    void setupStubs() {
        RabbitTemplateInvokeStubs.runInvokeCallbacks(rabbitTemplate);
    }

    // ==================== Scenario 用例 ====================

    @Test
    @DisplayName("错题本与重练池条目携带三态标识；连对递增三态流转 UNTOUCHED→PRACTICING")
    void listAndPoolItemsCarryMasteryThreeStates() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long classId = createClassForExam(teacher, student);

        long q1 = createQuestion(teacher, 1, "三态流转单选题", "A", List.of("A", "B"), 5.0, "三态解析");
        long paper = createPaper(teacher, unique("三态卷"), 5.0);
        addPaperQuestion(teacher, paper, q1, 5.0);
        long examId = createAndPublishExam(teacher, paper, classId);

        enterExam(student, examId);
        submitAnswers(student, examId, Map.of(q1, "B"));
        summarizeAndPublishScores(teacher, examId);

        // 错题本：连对 0 → UNTOUCHED
        JsonNode listData = perform(jsonGet(
                "/api/scores/my/wrong-questions?page=1&size=10", student), 200).get("data");
        JsonNode item = listData.get("groups").get(0).get("wrongQuestions").get(0);
        assertEquals(q1, item.get("questionId").asLong());
        assertTrue(item.has("masteryStatus") && !item.get("masteryStatus").isNull(),
                "错题本条目应携带 masteryStatus 三态字段");
        assertEquals(0, item.get("masteryStatus").asInt(), "连对 0 三态应为 UNTOUCHED");
        assertEquals("UNTOUCHED", item.get("masteryStatusName").asText());
        assertEquals(0, item.get("consecutiveCorrect").asInt(), "既有连对字段向下兼容");

        // 重练池：同样携带三态
        JsonNode pool = perform(jsonGet("/api/scores/my/wrong-questions/practice/pool", student), 200).get("data");
        assertEquals(1, pool.size());
        assertTrue(pool.get(0).has("masteryStatus") && !pool.get(0).get("masteryStatus").isNull(),
                "重练池条目应携带 masteryStatus 三态字段");
        assertEquals(0, pool.get(0).get("masteryStatus").asInt());
        assertEquals("UNTOUCHED", pool.get(0).get("masteryStatusName").asText());

        // 单题重练答对一次 → 连对 1 → PRACTICING
        ObjectNode sub = objectMapper.createObjectNode();
        sub.put("questionId", q1);
        sub.put("answer", "A");
        JsonNode feedback = perform(jsonPost(
                "/api/scores/my/wrong-questions/practice/submit", student,
                objectMapper.writeValueAsString(sub)), 200).get("data");
        assertEquals(1, feedback.get("consecutiveCorrect").asInt());
        assertTrue(feedback.has("masteryStatus") && !feedback.get("masteryStatus").isNull(),
                "单题反馈应携带 masteryStatus 三态字段");
        assertEquals(1, feedback.get("masteryStatus").asInt(), "连对 1 三态应为 PRACTICING");
        assertEquals("PRACTICING", feedback.get("masteryStatusName").asText());

        // 错题本列表三态随连对递增流转
        JsonNode listAfter = perform(jsonGet(
                "/api/scores/my/wrong-questions?page=1&size=10", student), 200).get("data");
        JsonNode itemAfter = listAfter.get("groups").get(0).get("wrongQuestions").get(0);
        assertTrue(itemAfter.has("masteryStatus") && !itemAfter.get("masteryStatus").isNull(),
                "错题本条目应携带 masteryStatus 三态字段");
        assertEquals(1, itemAfter.get("masteryStatus").asInt());
        assertEquals("PRACTICING", itemAfter.get("masteryStatusName").asText());
    }

    @Test
    @DisplayName("generate-quiz：UUID 试卷不泄露答案，PRACTICING_FIRST 优先攻克中，题量与策略校验")
    void generateQuizStatelessUuidStrategyAndValidation() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long classId = createClassForExam(teacher, student);

        long q1 = createQuestion(teacher, 1, "组卷单选1", "A", List.of("A", "B"), 5.0, "组卷解析1");
        long q2 = createQuestion(teacher, 1, "组卷单选2", "B", List.of("A", "B"), 5.0, "组卷解析2");
        long q3 = createQuestion(teacher, 1, "组卷单选3", "A", List.of("A", "B"), 5.0, "组卷解析3");
        long paper = createPaper(teacher, unique("组卷卷"), 15.0);
        addPaperQuestion(teacher, paper, q1, 5.0);
        addPaperQuestion(teacher, paper, q2, 5.0);
        addPaperQuestion(teacher, paper, q3, 5.0);
        long examId = createAndPublishExam(teacher, paper, classId);

        enterExam(student, examId);
        submitAnswers(student, examId, Map.of(q1, "B", q2, "A", q3, "B"));
        summarizeAndPublishScores(teacher, examId);

        // q3 先单题答对一次 → PRACTICING；q1/q2 保持 UNTOUCHED
        ObjectNode warm = objectMapper.createObjectNode();
        warm.put("questionId", q3);
        warm.put("answer", "A");
        perform(jsonPost("/api/scores/my/wrong-questions/practice/submit", student,
                objectMapper.writeValueAsString(warm)), 200);

        // RANDOM limit=2：返回 2 题、无答案泄露
        ObjectNode randomReq = objectMapper.createObjectNode();
        randomReq.put("limit", 2);
        randomReq.put("strategy", "RANDOM");
        JsonNode randomQuiz = perform(jsonPost(
                "/api/scores/my/wrong-questions/practice/generate-quiz", student,
                objectMapper.writeValueAsString(randomReq)), 200).get("data");
        assertTrue(randomQuiz.has("total") && !randomQuiz.get("total").isNull(),
                "组卷响应应携带 total 字段");
        assertEquals(2, randomQuiz.get("total").asInt(), "池 3 题抽 2 题应截断至 limit");
        assertTrue(randomQuiz.has("quizId") && !randomQuiz.get("quizId").isNull(),
                "组卷响应应携带 quizId 字段");
        String quizId = randomQuiz.get("quizId").asText();
        assertTrue(quizId != null && quizId.length() == 36, "quizId 应为 UUID 形态: " + quizId);
        JsonNode items = randomQuiz.get("items");
        assertEquals(2, items.size());
        for (JsonNode item : items) {
            assertTrue(item.get("correctAnswer") == null || item.get("correctAnswer").isNull(),
                    "组卷条目不得携带 correctAnswer");
            assertTrue(item.get("analysis") == null || item.get("analysis").isNull(),
                    "组卷条目不得携带 analysis");
            assertTrue(item.has("masteryStatus") && !item.get("masteryStatus").isNull(),
                    "组卷条目应携带三态标识");
        }

        // PRACTICING_FIRST limit=1：必须选中攻克中的 q3
        ObjectNode pfReq = objectMapper.createObjectNode();
        pfReq.put("limit", 1);
        pfReq.put("strategy", "PRACTICING_FIRST");
        JsonNode pfQuiz = perform(jsonPost(
                "/api/scores/my/wrong-questions/practice/generate-quiz", student,
                objectMapper.writeValueAsString(pfReq)), 200).get("data");
        assertEquals(1, pfQuiz.get("total").asInt());
        assertEquals(q3, pfQuiz.get("items").get(0).get("questionId").asLong(),
                "攻克中优先策略必须选中唯一 PRACTICING 题目");
        assertEquals(1, pfQuiz.get("items").get(0).get("masteryStatus").asInt());

        // 参数校验：题量越界与非法策略 → 400
        ObjectNode badLimit = objectMapper.createObjectNode();
        badLimit.put("limit", 21);
        perform(jsonPost("/api/scores/my/wrong-questions/practice/generate-quiz", student,
                objectMapper.writeValueAsString(badLimit)), 400);
        ObjectNode badStrategy = objectMapper.createObjectNode();
        badStrategy.put("limit", 3);
        badStrategy.put("strategy", "LATEST");
        perform(jsonPost("/api/scores/my/wrong-questions/practice/generate-quiz", student,
                objectMapper.writeValueAsString(badStrategy)), 400);
    }

    @Test
    @DisplayName("submit-quiz：同源判分、逐题落 practice_attempt、正确率与新消灭数、越权整单拒绝")
    void submitQuizGradesPersistsAndGuards() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long classId = createClassForExam(teacher, student);

        long q1 = createQuestion(teacher, 1, "交卷单选1", "A", List.of("A", "B"), 5.0, "交卷解析1");
        long q2 = createQuestion(teacher, 1, "交卷单选2", "B", List.of("A", "B"), 5.0, "交卷解析2");
        long paper = createPaper(teacher, unique("交卷卷"), 10.0);
        addPaperQuestion(teacher, paper, q1, 5.0);
        addPaperQuestion(teacher, paper, q2, 5.0);
        long examId = createAndPublishExam(teacher, paper, classId);

        enterExam(student, examId);
        submitAnswers(student, examId, Map.of(q1, "B", q2, "A"));
        summarizeAndPublishScores(teacher, examId);

        // q1 预热连对 2（两次单题答对），自测交卷再答对即晋级 MASTERED
        ObjectNode warm = objectMapper.createObjectNode();
        warm.put("questionId", q1);
        warm.put("answer", "A");
        perform(jsonPost("/api/scores/my/wrong-questions/practice/submit", student,
                objectMapper.writeValueAsString(warm)), 200);
        perform(jsonPost("/api/scores/my/wrong-questions/practice/submit", student,
                objectMapper.writeValueAsString(warm)), 200);

        // 组卷 limit=2 后批量交卷：q1 答对（晋级）、q2 答对（连对 1）
        ObjectNode genReq = objectMapper.createObjectNode();
        genReq.put("limit", 2);
        genReq.put("strategy", "RANDOM");
        JsonNode quiz = perform(jsonPost(
                "/api/scores/my/wrong-questions/practice/generate-quiz", student,
                objectMapper.writeValueAsString(genReq)), 200).get("data");
        assertTrue(quiz.has("quizId") && !quiz.get("quizId").isNull(),
                "组卷响应应携带 quizId 字段");
        String quizId = quiz.get("quizId").asText();

        ObjectNode submitReq = objectMapper.createObjectNode();
        submitReq.put("quizId", quizId);
        ArrayNode answers = submitReq.putArray("answers");
        ObjectNode a1 = answers.addObject();
        a1.put("questionId", q1);
        a1.put("answer", "A");
        ObjectNode a2 = answers.addObject();
        a2.put("questionId", q2);
        a2.put("answer", "B");
        JsonNode result = perform(jsonPost(
                "/api/scores/my/wrong-questions/practice/submit-quiz", student,
                objectMapper.writeValueAsString(submitReq)), 200).get("data");

        assertEquals(2, result.get("total").asInt());
        assertEquals(2, result.get("correctCount").asInt(), "q1、q2 均按标准答案判对");
        assertEquals(0, new BigDecimal("100.00").compareTo(new BigDecimal(result.get("scoreRate").asText())),
                "全对正确率 100");
        assertEquals(1, result.get("newlyMasteredCount").asInt(), "仅 q1 连对 2→3 晋级计入新消灭");

        JsonNode results = result.get("results");
        assertEquals(2, results.size());
        for (JsonNode one : results) {
            if (one.get("questionId").asLong() == q1) {
                assertTrue(one.get("correct").asBoolean());
                assertEquals(3, one.get("consecutiveCorrect").asInt());
                assertTrue(one.get("mastered").asBoolean());
                assertEquals(2, one.get("masteryStatus").asInt());
                assertEquals("MASTERED", one.get("masteryStatusName").asText());
                assertEquals("A", one.get("correctAnswer").asText());
                assertEquals("交卷解析1", one.get("analysis").asText());
            } else {
                assertTrue(one.get("correct").asBoolean());
                assertEquals(1, one.get("consecutiveCorrect").asInt(), "q2 首次答对连对 1");
                assertFalse(one.get("mastered").asBoolean());
                assertEquals(1, one.get("masteryStatus").asInt());
                assertEquals("PRACTICING", one.get("masteryStatusName").asText());
            }
        }

        // 零 DDL：明细全部落既有 practice_attempt（q1 预热 2 次 + 自测 1 次 = 3）
        Long q1Attempts = practiceAttemptMapper.selectCount(
                Wrappers.<PracticeAttempt>lambdaQuery()
                        .eq(PracticeAttempt::getStudentId, currentUserId(student))
                        .eq(PracticeAttempt::getQuestionId, q1));
        assertEquals(3L, q1Attempts, "自测明细必须复用既有 practice_attempt 表");

        // 越权：未参加考试的其他学生提交本人错题 → 整单 400 且零写入
        String outsider = registerStudent();
        Long outsiderBefore = attemptCountOf(outsider);
        ObjectNode foreignReq = objectMapper.createObjectNode();
        foreignReq.put("quizId", quizId);
        ArrayNode foreignAnswers = foreignReq.putArray("answers");
        ObjectNode fa = foreignAnswers.addObject();
        fa.put("questionId", q1);
        fa.put("answer", "A");
        JsonNode err = perform(jsonPost("/api/scores/my/wrong-questions/practice/submit-quiz", outsider,
                objectMapper.writeValueAsString(foreignReq)), 400);
        assertTrue(err.path("message").asText().contains("错题重练池"),
                "越权提交应返回错题池归属拒绝文案");
        assertEquals(outsiderBefore, attemptCountOf(outsider), "越权提交严禁写入练习明细");

        // 空作答列表 → 400（参数校验失败）
        ObjectNode emptyReq = objectMapper.createObjectNode();
        emptyReq.put("quizId", quizId);
        emptyReq.putArray("answers");
        perform(jsonPost("/api/scores/my/wrong-questions/practice/submit-quiz", student,
                objectMapper.writeValueAsString(emptyReq)), 400);
    }

    // ==================== 造数工具（与既有错题集成测试同型） ====================

    private long currentUserId(String token) throws Exception {
        JsonNode me = perform(jsonGet("/api/auth/me", token), 200).get("data");
        return me.get("id").asLong();
    }

    private long attemptCountOf(String token) throws Exception {
        return practiceAttemptMapper.selectCount(
                Wrappers.<PracticeAttempt>lambdaQuery()
                        .eq(PracticeAttempt::getStudentId, currentUserId(token)));
    }

    private long createQuestion(String teacherToken, int type, String content, String answer,
                                List<String> choices, double score, String analysis) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("type", type);
        body.put("content", content);
        body.put("correctAnswer", answer);
        body.put("score", score);
        body.put("difficulty", 1);
        body.put("analysis", analysis);
        if (choices != null) {
            ArrayNode arr = body.putArray("choices");
            choices.forEach(arr::add);
        }
        JsonNode res = perform(jsonPost("/api/questions", teacherToken, objectMapper.writeValueAsString(body)), 200)
                .get("data");
        return res.get("id").asLong();
    }

    private long addPaperQuestion(String teacherToken, long paperId, long questionId, double score) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("questionId", questionId);
        body.put("score", score);
        perform(jsonPost("/api/papers/" + paperId + "/questions", teacherToken, objectMapper.writeValueAsString(body)), 200);
        return questionId;
    }

    private long createAndPublishExam(String teacherToken, long paperId, long classId) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("title", unique("掌握度三态测试考试"));
        body.put("paperId", paperId);
        body.put("classId", classId);
        body.put("startTime", LocalDateTime.now().minusMinutes(10).toString());
        body.put("endTime", LocalDateTime.now().plusHours(1).toString());
        body.put("durationMinutes", 30);
        JsonNode data = perform(jsonPost("/api/exams", teacherToken, objectMapper.writeValueAsString(body)), 200).get("data");
        long examId = data.get("id").asLong();
        perform(jsonPost("/api/exams/" + examId + "/publish", teacherToken, null), 200);
        stateMachineService.autoAdvance();
        return examId;
    }

    private void enterExam(String studentToken, long examId) throws Exception {
        ensureExamClassMembership(studentToken, examId);
        perform(jsonPost("/api/exam-taking/exams/" + examId + "/enter", studentToken, null), 200);
    }

    private void submitAnswers(String studentToken, long examId, Map<Long, String> answersMap) throws Exception {
        ObjectNode answers = objectMapper.createObjectNode();
        answersMap.forEach((qid, ans) -> answers.put(String.valueOf(qid), ans));
        ObjectNode request = objectMapper.createObjectNode();
        request.set("answers", answers);
        perform(jsonPost("/api/exam-taking/exams/" + examId + "/submit", studentToken, objectMapper.writeValueAsString(request)), 200);
        consumeCapturedMessages(examId);
    }

    private void consumeCapturedMessages(long examId) throws Exception {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate, atLeastOnce()).convertAndSend(anyString(), anyString(), captor.capture());
        for (Object raw : new ArrayList<>(captor.getAllValues())) {
            SubmitMessage msg = (SubmitMessage) raw;
            if (!msg.getExamId().equals(examId)) {
                continue;
            }
            Message amqp = new Message(objectMapper.writeValueAsBytes(msg), new MessageProperties());
            amqp.getMessageProperties().setDeliveryTag(1L);
            submitConsumer.onBatch(List.of(amqp), org.mockito.Mockito.mock(com.rabbitmq.client.Channel.class));
        }
    }

    private void summarizeAndPublishScores(String teacherToken, long examId) throws Exception {
        perform(jsonPost("/api/exams/" + examId + "/force-end", teacherToken, null), 200);
        perform(jsonPost("/api/exams/" + examId + "/grading/run", teacherToken, null), 200);
        perform(jsonPost("/api/exams/" + examId + "/scores/summarize", teacherToken, null), 200);
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode arr = body.putArray("examIds");
        arr.add(examId);
        perform(jsonPost("/api/scores/publish", teacherToken, objectMapper.writeValueAsString(body)), 200);
    }
}

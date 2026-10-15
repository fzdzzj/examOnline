package com.exam.score;

import com.exam.exam.service.ExamStateMachineService;
import com.exam.question.mapper.QuestionMapper;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * 学情分析与靶向练习推荐集成测试（spec add-student-learning-analytics）。
 * 覆盖 score-management 全部 Scenario：
 * <ul>
 *   <li>学生跨考试学情画像：总览/趋势/知识点掌握度/薄弱点；</li>
 *   <li>零答卷空态：返回 200 参测 0 空态对象，不 500/404；</li>
 *   <li>靶向练习推荐：最薄弱知识点下机判客观题 + 推荐原因 + limit 上限；</li>
 *   <li>推荐排除已消灭题目：错题重练连对 3 次后不再推荐；</li>
 *   <li>教师班级学情：归属教师在法定权限内查询，非归属教师越权 403；</li>
 *   <li>仅本人数据：他人无法读到学情。</li>
 * </ul>
 */
class StudentLearningAnalyticsIntegrationTest extends IntegrationTestBase {

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private ExamStateMachineService stateMachineService;

    @Autowired
    private ExamSubmitConsumer submitConsumer;

    @Autowired
    private QuestionMapper questionMapper;

    @Autowired
    private PracticeAttemptMapper practiceAttemptMapper;

    @BeforeEach
    void setupStubs() {
        RabbitTemplateInvokeStubs.runInvokeCallbacks(rabbitTemplate);
    }

    // ==================== 辅助方法 ====================

    private long createQuestion(String teacherToken, int type, String content, String answer,
                               List<String> choices, double score, List<Long> tagIds) throws Exception {
        return perform(jsonPost("/api/questions", teacherToken,
                questionJson(type, content, answer, choices, score, 1, tagIds)), 200)
                .get("data").get("id").asLong();
    }

    private long addPaperQuestion(String teacherToken, long paperId, long questionId, double score) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("questionId", questionId);
        body.put("score", score);
        perform(jsonPost("/api/papers/" + paperId + "/questions", teacherToken,
                objectMapper.writeValueAsString(body)), 200);
        return questionId;
    }

    private long createAndPublishExam(String teacherToken, long paperId, long classId) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("title", unique("学情测试考试"));
        body.put("paperId", paperId);
        body.put("classId", classId);
        body.put("startTime", LocalDateTime.now().minusMinutes(10).toString());
        body.put("endTime", LocalDateTime.now().plusHours(1).toString());
        body.put("durationMinutes", 30);
        JsonNode data = perform(jsonPost("/api/exams", teacherToken,
                objectMapper.writeValueAsString(body)), 200).get("data");
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
        perform(jsonPost("/api/exam-taking/exams/" + examId + "/submit", studentToken,
                objectMapper.writeValueAsString(request)), 200);
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

    /** 学生把指定题目连对 3 次（用于制造「已消灭」）。 */
    private void masterQuestion(String studentToken, long questionId) throws Exception {
        ObjectNode sub = objectMapper.createObjectNode();
        sub.put("questionId", questionId);
        sub.put("answer", "A");
        for (int i = 0; i < 3; i++) {
            perform(jsonPost("/api/scores/my/wrong-questions/practice/submit", studentToken,
                    objectMapper.writeValueAsString(sub)), 200);
        }
    }

    // ==================== Scenario 测试用例 ====================

    @Test
    @DisplayName("学生跨考试学情画像：总览/趋势/知识点掌握度与薄弱点标记")
    void studentAnalytics_masteryAndWeakTags() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long classId = createClassForExam(teacher, student);

        // 知识点 A（薄弱）：学生考得差；知识点 B（掌握）：学生考得好
        long tagA = createTag(teacher, unique("物理"), "SUBJECT");
        long tagB = createTag(teacher, unique("数学"), "SUBJECT");
        long qA = createQuestion(teacher, 1, "物理选择题", "A", List.of("A", "B"), 5.0, List.of(tagA));
        long qB = createQuestion(teacher, 1, "数学选择题", "A", List.of("A", "B"), 5.0, List.of(tagB));

        // 考试 1：qA（答错，物理薄弱）
        long paper1 = createPaper(teacher, unique("卷物理"), 5.0);
        addPaperQuestion(teacher, paper1, qA, 5.0);
        long exam1 = createAndPublishExam(teacher, paper1, classId);
        enterExam(student, exam1);
        submitAnswers(student, exam1, Map.of(qA, "B"));
        summarizeAndPublishScores(teacher, exam1);

        // 考试 2：qB（答对，数学掌握）
        long paper2 = createPaper(teacher, unique("卷数学"), 5.0);
        addPaperQuestion(teacher, paper2, qB, 5.0);
        long exam2 = createAndPublishExam(teacher, paper2, classId);
        enterExam(student, exam2);
        submitAnswers(student, exam2, Map.of(qB, "A"));
        summarizeAndPublishScores(teacher, exam2);

        JsonNode data = perform(jsonGet("/api/scores/my/analytics", student), 200).get("data");
        // 总览
        assertEquals(2, data.get("overview").get("totalExams").asInt());
        // 两场总分 5 分制：0 分 + 满分 5 → 平均 2.5
        assertEquals(2.5, data.get("overview").get("averageScore").asDouble());
        assertEquals(0.5, data.get("overview").get("passRate").asDouble(), "一场及格一场不及格");
        // 趋势两场，按时间升序
        assertEquals(2, data.get("trend").size());

        // 知识点掌握度：物理薄弱、数学掌握
        JsonNode tagMastery = data.get("tagMastery");
        assertEquals(2, tagMastery.size());
        JsonNode physics = findTag(tagMastery, tagA);
        assertNotNull(physics);
        assertEquals(0.0, physics.get("masteryRate").asDouble());
        assertTrue(physics.get("weak").asBoolean(), "物理掌握度 0 应为薄弱点");

        JsonNode math = findTag(tagMastery, tagB);
        assertNotNull(math);
        assertEquals(1.0, math.get("masteryRate").asDouble());
        assertFalse(math.get("weak").asBoolean(), "数学掌握度 100 不应标记薄弱");

        // 薄弱点只含物理
        JsonNode weakTags = data.get("weakTags");
        assertEquals(1, weakTags.size());
        assertEquals(tagA, weakTags.get(0).get("tagId").asLong());
    }

    @Test
    @DisplayName("学生零答卷空态：返回 200 参测 0 空态对象，不 500/404")
    void studentAnalytics_emptyState() throws Exception {
        String student = registerStudent();
        JsonNode data = perform(jsonGet("/api/scores/my/analytics", student), 200).get("data");
        assertNotNull(data);
        assertEquals(0, data.get("overview").get("totalExams").asInt());
        // 无答卷 → 平均/最高/最低缺席（Jackson 非空序列化省略 null 字段）
        assertTrue(!data.get("overview").has("averageScore")
                || data.get("overview").path("averageScore").isNull());
        assertTrue(data.get("trend").isEmpty());
        assertTrue(data.get("tagMastery").isEmpty());
        assertTrue(data.get("weakTags").isEmpty());
    }

    @Test
    @DisplayName("靶向练习推荐：最薄弱知识点下的机判客观题 + 推荐原因 + limit 上限，排除已消灭")
    void recommendations_weakTagTargeting() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long classId = createClassForExam(teacher, student);

        // 弱知识点 tagW：学生错（薄弱）；另两个与 tagW 相关的候选推荐题
        long tagW = createTag(teacher, unique("化学"), "SUBJECT");
        long examQ = createQuestion(teacher, 1, "化学考试题", "A", List.of("A", "B"), 5.0, List.of(tagW));
        long recQ1 = createQuestion(teacher, 1, "化学推荐一", "A", List.of("A", "B"), 5.0, List.of(tagW));
        long recQ2 = createQuestion(teacher, 2, "化学推荐二多选", "A,B", List.of("A", "B", "C"), 6.0, List.of(tagW));

        // 考试只含 examQ，学生答错 → tagW 掌握度 0，为最薄弱知识点
        long paper = createPaper(teacher, unique("卷化学"), 5.0);
        addPaperQuestion(teacher, paper, examQ, 5.0);
        long examId = createAndPublishExam(teacher, paper, classId);
        enterExam(student, examId);
        submitAnswers(student, examId, Map.of(examQ, "B"));
        summarizeAndPublishScores(teacher, examId);

        // limit=1 → 只返回 1 条
        JsonNode rec1 = perform(jsonGet("/api/scores/my/recommendations?limit=1", student), 200).get("data");
        assertEquals(1, rec1.size());
        JsonNode item = rec1.get(0);
        assertTrue(item.has("questionId"));
        assertEquals(tagW, item.get("tagId").asLong());
        assertNotNull(item.get("stem"));
        assertNotNull(item.get("recommendReason"));
        assertTrue(item.get("recommendReason").asText().contains("化学"),
                "推荐原因应包含知识点名称");
        assertTrue(item.get("recommendReason").asText().contains("0.0%"),
                "推荐原因应包含当前薄弱掌握度");

        // 不设 limit（默认 5）→ 返回候选（recQ1/recQ2，且不含考试题 examQ——考试题也属题库可推荐，
        // 但为验证 limit 全量，这里用 limit=10 看全部候选）
        JsonNode recAll = perform(jsonGet("/api/scores/my/recommendations?limit=10", student), 200).get("data");
        // 候选为 tagW 下全部启用客观题：examQ + recQ1 + recQ2（各 diff 自增 id 升序）
        assertTrue(recAll.size() >= 2, "应能推荐 tagW 下的多条题目");

        // limit 超过上限 10 → 被钳制为 10（不报错）
        JsonNode recCapped = perform(jsonGet("/api/scores/my/recommendations?limit=99", student), 200).get("data");
        assertTrue(recCapped.size() <= 10);
    }

    @Test
    @DisplayName("推荐排除已消灭题目：错题重练连对 3 次后不再进入推荐")
    void recommendations_excludeMastered() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long classId = createClassForExam(teacher, student);

        long tagW = createTag(teacher, unique("生物"), "SUBJECT");
        long examQ = createQuestion(teacher, 1, "生物考试题", "A", List.of("A", "B"), 5.0, List.of(tagW));
        long recQ = createQuestion(teacher, 1, "生物可推荐题", "A", List.of("A", "B"), 5.0, List.of(tagW));

        // 考试含 examQ 与 recQ，学生都答错 → 两题都进入本人错题重练池，tagW 变薄弱
        long paper = createPaper(teacher, unique("卷生物"), 10.0);
        addPaperQuestion(teacher, paper, examQ, 5.0);
        addPaperQuestion(teacher, paper, recQ, 5.0);
        long examId = createAndPublishExam(teacher, paper, classId);
        enterExam(student, examId);
        submitAnswers(student, examId, Map.of(examQ, "B", recQ, "B"));
        summarizeAndPublishScores(teacher, examId);

        // 先把 recQ 练到消灭（3 连对），再查推荐应排除它
        masterQuestion(student, recQ);

        JsonNode rec = perform(jsonGet("/api/scores/my/recommendations?limit=10", student), 200).get("data");
        List<Long> recommendedIds = new ArrayList<>();
        rec.forEach(r -> recommendedIds.add(r.get("questionId").asLong()));
        assertFalse(recommendedIds.contains(recQ), "已消灭题目不得再被推荐");
        // 未消灭的 examQ 仍应可推荐（排除只针对已消灭，不误伤其他候选）
        assertTrue(recommendedIds.contains(examQ));
    }

    @Test
    @DisplayName("教师班级学情：归属教师可查，非归属教师越权 403")
    void classAnalytics_ownershipGuard() throws Exception {
        String teacherA = registerTeacher();
        String teacherB = registerTeacher();
        String student = registerStudent();
        long classId = createClassForExam(teacherA, student);

        long tagA = createTag(teacherA, unique("语文"), "SUBJECT");
        long qA = createQuestion(teacherA, 1, "语文选择题", "A", List.of("A", "B"), 5.0, List.of(tagA));
        long paper = createPaper(teacherA, unique("卷语文"), 5.0);
        addPaperQuestion(teacherA, paper, qA, 5.0);
        long examId = createAndPublishExam(teacherA, paper, classId);
        enterExam(student, examId);
        submitAnswers(student, examId, Map.of(qA, "A"));
        summarizeAndPublishScores(teacherA, examId);

        // 归属教师 A 可查班级学情
        JsonNode data = perform(jsonGet("/api/scores/classes/" + classId + "/analytics", teacherA), 200).get("data");
        assertEquals(classId, data.get("classId").asLong());
        assertEquals(1, data.get("studentCount").asInt());
        assertEquals(1, data.get("examCount").asInt());
        assertEquals(1, data.get("trend").size());
        assertNotNull(data.get("tagMastery"));
        // 语文掌握度 100%（A 答对），非薄弱 → weakTags 空
        assertTrue(data.get("weakTags").isEmpty());

        // 非归属教师 B 越权 → 403
        perform(jsonGet("/api/scores/classes/" + classId + "/analytics", teacherB), 403);
    }

    @Test
    @DisplayName("仅本人数据：学生 B 查不到学生 A 的学情（空态），未登录 401")
    void analytics_onlySelfData() throws Exception {
        String teacher = registerTeacher();
        String studentA = registerStudent();
        String studentB = registerStudent();
        long classId = createClassForExam(teacher, studentA);

        long tagA = createTag(teacher, unique("英语"), "SUBJECT");
        long qA = createQuestion(teacher, 1, "英语选择题", "A", List.of("A", "B"), 5.0, List.of(tagA));
        long paper = createPaper(teacher, unique("卷英语"), 5.0);
        addPaperQuestion(teacher, paper, qA, 5.0);
        long examId = createAndPublishExam(teacher, paper, classId);
        enterExam(studentA, examId);
        submitAnswers(studentA, examId, Map.of(qA, "A"));
        summarizeAndPublishScores(teacher, examId);

        // 学生 A 有 1 场
        assertEquals(1, perform(jsonGet("/api/scores/my/analytics", studentA), 200)
                .get("data").get("overview").get("totalExams").asInt());
        // 学生 B 无本人数据 → 空态（不泄漏 A 的数据）
        assertEquals(0, perform(jsonGet("/api/scores/my/analytics", studentB), 200)
                .get("data").get("overview").get("totalExams").asInt());

        // 未登录访问三端点 → 401（学生端点）
        perform(jsonGet("/api/scores/my/analytics", null), 401);
        perform(jsonGet("/api/scores/my/recommendations", null), 401);
    }

    // ==================== 工具 ====================

    private JsonNode findTag(JsonNode arr, long tagId) {
        for (JsonNode node : arr) {
            if (node.get("tagId").asLong() == tagId) {
                return node;
            }
        }
        return null;
    }
}
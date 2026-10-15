package com.exam.score;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.exam.service.ExamStateMachineService;
import com.exam.question.entity.Question;
import com.exam.question.mapper.QuestionMapper;
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
 * 错题本重练与消灭计划集成测试（spec add-wrong-question-practice）：
 * 覆盖 score-management 全部 Scenario：
 * <ul>
 *   <li>池构建去重与题型过滤：跨考试重复错题去重、简答不入池、已消灭不入池；</li>
 *   <li>逐题提交即时判分反馈：对错、正确答案、解析、连对进度；</li>
 *   <li>连续三次答对自动消灭：第 3 次答对 mastered=true，从池与默认错题列表消失；</li>
 *   <li>答错重置连对：答错归零，再答对重新累计；</li>
 *   <li>多选部分对不判答对：漏选部分分 correct=false，连对归零；判分口径同源；</li>
 *   <li>仅限本人数据：他人无法访问池或提交；</li>
 *   <li>列表默认隐藏已消灭与 includeMastered 翻回；</li>
 *   <li>既有口径零漂移护栏。</li>
 * </ul>
 */
class StudentWrongQuestionPracticeIntegrationTest extends IntegrationTestBase {

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
        long qid = res.get("id").asLong();
        if (analysis != null) {
            Question q = questionMapper.selectById(qid);
            if (q != null && (q.getAnalysis() == null || !q.getAnalysis().equals(analysis))) {
                q.setAnalysis(analysis);
                questionMapper.updateById(q);
            }
        }
        return qid;
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
        body.put("title", unique("错题重练测试考试"));
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

    private Map<Integer, Long> enterExam(String studentToken, long examId) throws Exception {
        ensureExamClassMembership(studentToken, examId);
        JsonNode data = perform(jsonPost("/api/exam-taking/exams/" + examId + "/enter", studentToken, null), 200).get("data");
        Map<Integer, Long> byType = new HashMap<>();
        data.get("questions").forEach(q -> byType.put(q.get("type").asInt(), q.get("questionId").asLong()));
        return byType;
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

    // ==================== Scenario 测试用例 ====================

    @Test
    @DisplayName("重练池构建与题型过滤：去重、简答不入池、已消灭不入池")
    void practicePool_deduplicationAndTypeFilter() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long classId = createClassForExam(teacher, student);

        // 题1：单选题（将在两场考试中重复出现并答错）
        long q1 = createQuestion(teacher, 1, "单选重复题", "A", List.of("A", "B"), 5.0, "单选解析1");
        // 题2：简答题（错题，可见于列表但不得入重练池）
        long q2 = createQuestion(teacher, 4, "简答错题", "标准答案文本", null, 10.0, "简答解析");
        // 题3：填空题
        long q3 = createQuestion(teacher, 5, "填空题：北京是中国的____", "首都", null, 5.0, "填空解析");

        // 考试 1：含 q1, q2
        long paper1 = createPaper(teacher, unique("卷1"), 15.0);
        addPaperQuestion(teacher, paper1, q1, 5.0);
        addPaperQuestion(teacher, paper1, q2, 10.0);
        long exam1 = createAndPublishExam(teacher, paper1, classId);

        enterExam(student, exam1);
        submitAnswers(student, exam1, Map.of(q1, "B", q2, "错误简答回答"));
        summarizeAndPublishScores(teacher, exam1);

        // 考试 2：含 q1, q3
        long paper2 = createPaper(teacher, unique("卷2"), 10.0);
        addPaperQuestion(teacher, paper2, q1, 5.0);
        addPaperQuestion(teacher, paper2, q3, 5.0);
        long exam2 = createAndPublishExam(teacher, paper2, classId);

        enterExam(student, exam2);
        submitAnswers(student, exam2, Map.of(q1, "B", q3, "错误填空"));
        summarizeAndPublishScores(teacher, exam2);

        // 请求重练池：GET /api/scores/my/wrong-questions/practice/pool
        JsonNode poolResp = perform(jsonGet("/api/scores/my/wrong-questions/practice/pool", student), 200).get("data");
        assertNotNull(poolResp);
        assertTrue(poolResp.isArray());

        // 断言：
        // 1. q1 重复出错，但池中仅出现 1 次（去重）
        // 2. q2 简答题不入池
        // 3. q3 填空题入池
        // 因此池中只有 q1 和 q3 两道题
        assertEquals(2, poolResp.size());
        List<Long> poolQids = new ArrayList<>();
        poolResp.forEach(item -> poolQids.add(item.get("questionId").asLong()));
        assertTrue(poolQids.contains(q1));
        assertTrue(poolQids.contains(q3));
        assertFalse(poolQids.contains(q2), "简答题不得入重练池");

        // 验证题目不泄漏答案和解析
        JsonNode firstItem = poolResp.get(0);
        assertNull(firstItem.get("correctAnswer"));
        assertNull(firstItem.get("analysis"));
        assertNotNull(firstItem.get("questionType"));
        assertNotNull(firstItem.get("questionContent"));
        assertEquals(0, firstItem.get("consecutiveCorrect").asInt());
    }

    @Test
    @DisplayName("逐题提交即时判分反馈、连三消灭与答错重置")
    void practiceSubmit_feedbackAndConsecutiveRules() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long classId = createClassForExam(teacher, student);

        long q1 = createQuestion(teacher, 1, "单选测试题", "A", List.of("A", "B"), 5.0, "单选解析A");
        long paper = createPaper(teacher, unique("卷"), 5.0);
        addPaperQuestion(teacher, paper, q1, 5.0);
        long examId = createAndPublishExam(teacher, paper, classId);

        enterExam(student, examId);
        submitAnswers(student, examId, Map.of(q1, "B"));
        summarizeAndPublishScores(teacher, examId);

        // 第 1 次重练提交：正确答案
        ObjectNode sub1 = objectMapper.createObjectNode();
        sub1.put("questionId", q1);
        sub1.put("answer", "A");
        JsonNode res1 = perform(jsonPost("/api/scores/my/wrong-questions/practice/submit", student, objectMapper.writeValueAsString(sub1)), 200).get("data");
        assertTrue(res1.get("correct").asBoolean());
        assertEquals("A", res1.get("correctAnswer").asText());
        assertEquals("单选解析A", res1.get("analysis").asText());
        assertEquals(1, res1.get("consecutiveCorrect").asInt());
        assertFalse(res1.get("mastered").asBoolean());

        // 第 2 次重练提交：正确答案
        JsonNode res2 = perform(jsonPost("/api/scores/my/wrong-questions/practice/submit", student, objectMapper.writeValueAsString(sub1)), 200).get("data");
        assertTrue(res2.get("correct").asBoolean());
        assertEquals(2, res2.get("consecutiveCorrect").asInt());
        assertFalse(res2.get("mastered").asBoolean());

        // 提交错误答案：答错重置连对为 0
        ObjectNode subWrong = objectMapper.createObjectNode();
        subWrong.put("questionId", q1);
        subWrong.put("answer", "B");
        JsonNode resWrong = perform(jsonPost("/api/scores/my/wrong-questions/practice/submit", student, objectMapper.writeValueAsString(subWrong)), 200).get("data");
        assertFalse(resWrong.get("correct").asBoolean());
        assertEquals(0, resWrong.get("consecutiveCorrect").asInt());
        assertFalse(resWrong.get("mastered").asBoolean());

        // 重新累计：第 1 次答对 -> consecutive=1
        JsonNode resRetry1 = perform(jsonPost("/api/scores/my/wrong-questions/practice/submit", student, objectMapper.writeValueAsString(sub1)), 200).get("data");
        assertEquals(1, resRetry1.get("consecutiveCorrect").asInt());

        // 第 2 次答对 -> consecutive=2
        JsonNode resRetry2 = perform(jsonPost("/api/scores/my/wrong-questions/practice/submit", student, objectMapper.writeValueAsString(sub1)), 200).get("data");
        assertEquals(2, resRetry2.get("consecutiveCorrect").asInt());

        // 第 3 次答对 -> consecutive=3, mastered=true（连续三次答对自动消灭！）
        JsonNode resRetry3 = perform(jsonPost("/api/scores/my/wrong-questions/practice/submit", student, objectMapper.writeValueAsString(sub1)), 200).get("data");
        assertEquals(3, resRetry3.get("consecutiveCorrect").asInt());
        assertTrue(resRetry3.get("mastered").asBoolean());

        // 校验已消灭后：
        // 1. 池中不再包含该题
        JsonNode poolAfter = perform(jsonGet("/api/scores/my/wrong-questions/practice/pool", student), 200).get("data");
        assertEquals(0, poolAfter.size());

        // 2. 错题本列表默认隐藏已消灭错题
        JsonNode listDefault = perform(jsonGet("/api/scores/my/wrong-questions?page=1&size=10", student), 200).get("data");
        JsonNode groups = listDefault.get("groups");
        if (groups.size() > 0) {
            JsonNode wqs = groups.get(0).get("wrongQuestions");
            assertEquals(0, wqs.size(), "默认列表应当隐藏已消灭错题");
        }

        // 3. 错题本列表使用 includeMastered=true 翻回可见且带 mastered=true
        JsonNode listInclude = perform(jsonGet("/api/scores/my/wrong-questions?page=1&size=10&includeMastered=true", student), 200).get("data");
        assertEquals(1, listInclude.get("groups").size());
        JsonNode item = listInclude.get("groups").get(0).get("wrongQuestions").get(0);
        assertEquals(q1, item.get("questionId").asLong());
        assertTrue(item.get("mastered").asBoolean());
        assertEquals(3, item.get("consecutiveCorrect").asInt());
    }

    @Test
    @DisplayName("多选部分对不判答对：漏选按比例得分但 correct 恒为 false，连对归零")
    void practiceSubmit_multiplePartialNotCorrect() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long classId = createClassForExam(teacher, student);

        // 正确答案 A,B,C
        long qMultiple = createQuestion(teacher, 2, "多选题", "A,B,C", List.of("A", "B", "C", "D"), 6.0, "多选解析");
        long paper = createPaper(teacher, unique("卷"), 6.0);
        addPaperQuestion(teacher, paper, qMultiple, 6.0);
        long examId = createAndPublishExam(teacher, paper, classId);

        enterExam(student, examId);
        submitAnswers(student, examId, Map.of(qMultiple, "D"));
        summarizeAndPublishScores(teacher, examId);

        // 先提交一次全对 A,B,C -> consecutive=1
        ObjectNode subCorrect = objectMapper.createObjectNode();
        subCorrect.put("questionId", qMultiple);
        subCorrect.put("answer", "A,B,C");
        JsonNode res1 = perform(jsonPost("/api/scores/my/wrong-questions/practice/submit", student, objectMapper.writeValueAsString(subCorrect)), 200).get("data");
        assertTrue(res1.get("correct").asBoolean());
        assertEquals(1, res1.get("consecutiveCorrect").asInt());

        // 提交漏选答案 "A,B"（在考试判分中可得部分分）
        ObjectNode subPartial = objectMapper.createObjectNode();
        subPartial.put("questionId", qMultiple);
        subPartial.put("answer", "A,B");
        JsonNode resPartial = perform(jsonPost("/api/scores/my/wrong-questions/practice/submit", student, objectMapper.writeValueAsString(subPartial)), 200).get("data");

        // 核心断言：多选部分对在重练中判定为答错（correct=false），连对归零
        assertFalse(resPartial.get("correct").asBoolean(), "多选部分对不能判为答对");
        assertEquals(0, resPartial.get("consecutiveCorrect").asInt(), "部分对导致连对归零");
        assertFalse(resPartial.get("mastered").asBoolean());
    }

    @Test
    @DisplayName("原题软删除后重练提交从考试快照平滑回退判分")
    void practiceSubmitFallsBackToExamSnapshotWhenOriginalQuestionSoftDeleted() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long classId = createClassForExam(teacher, student);

        long q1 = createQuestion(teacher, 1, "快照回退单选", "A", List.of("A", "B"), 5.0, "快照解析A");
        long paper = createPaper(teacher, unique("卷"), 5.0);
        addPaperQuestion(teacher, paper, q1, 5.0);
        long examId = createAndPublishExam(teacher, paper, classId);

        // 学生做错该题（q1 作答 B 判错）
        enterExam(student, examId);
        submitAnswers(student, examId, Map.of(q1, "B"));
        summarizeAndPublishScores(teacher, examId);

        // 教师在题库中软删除原题（快照不受影响）
        questionMapper.deleteById(q1);
        assertNull(questionMapper.selectById(q1), "原题应已逻辑删除");

        // 学生仍能提交重练：回退到考试快照判分，正确答案 A 来自快照，连对进度正常累计
        ObjectNode sub = objectMapper.createObjectNode();
        sub.put("questionId", q1);
        sub.put("answer", "A");
        JsonNode res = perform(jsonPost("/api/scores/my/wrong-questions/practice/submit", student,
                objectMapper.writeValueAsString(sub)), 200).get("data");
        assertTrue(res.get("correct").asBoolean(), "快照回退判分应判为答对");
        assertEquals("A", res.get("correctAnswer").asText(), "标准答案应取自快照");
        assertEquals(1, res.get("consecutiveCorrect").asInt(), "连对进度应正常累计");
        assertFalse(res.get("mastered").asBoolean());

        // 再次答对连续两次后连对=3 消灭，链路不因题库变动中断
        perform(jsonPost("/api/scores/my/wrong-questions/practice/submit", student,
                objectMapper.writeValueAsString(sub)), 200);
        JsonNode third = perform(jsonPost("/api/scores/my/wrong-questions/practice/submit", student,
                objectMapper.writeValueAsString(sub)), 200).get("data");
        assertTrue(third.get("mastered").asBoolean(), "快照回退下连续三次答对应自动消灭");
    }

    @Test
    @DisplayName("仅本人数据：学生 B 无法访问或提交学生 A 的错题池")
    void practice_onlySelfData() throws Exception {
        String teacher = registerTeacher();
        String studentA = registerStudent();
        String studentB = registerStudent();
        long classId = createClassForExam(teacher, studentA, studentB);

        long qA = createQuestion(teacher, 1, "学生A错题", "A", List.of("A", "B"), 5.0, "解析A");
        long paper = createPaper(teacher, unique("卷"), 5.0);
        addPaperQuestion(teacher, paper, qA, 5.0);
        long examId = createAndPublishExam(teacher, paper, classId);

        // 只有 A 参加并做错
        enterExam(studentA, examId);
        submitAnswers(studentA, examId, Map.of(qA, "B"));
        summarizeAndPublishScores(teacher, examId);

        // 学生 B 查重练池 -> 0 道错题（不泄漏 A 的错题）
        JsonNode poolB = perform(jsonGet("/api/scores/my/wrong-questions/practice/pool", studentB), 200).get("data");
        assertEquals(0, poolB.size());

        // 学生 B 提交 A 的题目 -> 400 拒绝（不属于本人重练错题池）
        ObjectNode subB = objectMapper.createObjectNode();
        subB.put("questionId", qA);
        subB.put("answer", "A");
        perform(jsonPost("/api/scores/my/wrong-questions/practice/submit", studentB, objectMapper.writeValueAsString(subB)), 400);

        // 未登录用户访问两端点 -> 401 拦截
        perform(jsonGet("/api/scores/my/wrong-questions/practice/pool", null), 401);
        perform(jsonPost("/api/scores/my/wrong-questions/practice/submit", null, objectMapper.writeValueAsString(subB)), 401);
    }
}


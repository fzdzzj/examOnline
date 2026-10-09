package com.exam.score;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.auth.service.JwtUtil;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.exam.service.ExamStateMachineService;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.question.entity.Question;
import com.exam.question.mapper.QuestionMapper;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * 学生错题本与单场逐题回顾集成测试（spec add-student-wrong-questions）：
 * <ul>
 *   <li>考试粒度分页：仅对页内已发布考试重算，页外不重算；</li>
 *   <li>错题判定：得分严格小于满分（含 0 分及部分得分）；满分题不计入；</li>
 *   <li>语义隔离：analysis 严格取自题目表（questions.analysis），非判分依据；</li>
 *   <li>发布门控：未发布考试严格隐藏，单场回顾未发布统一返回 400；</li>
 *   <li>权限与无答卷：无已批改成绩返回 404。</li>
 * </ul>
 */
class StudentWrongQuestionIntegrationTest extends IntegrationTestBase {

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private ExamStateMachineService stateMachineService;

    @Autowired
    private ExamSubmitConsumer submitConsumer;

    @Autowired
    private QuestionMapper questionMapper;

    @Autowired
    private ExamMapper examMapper;

    @Autowired
    private GradingSubmissionMapper gradingSubmissionMapper;

    @Autowired
    private JwtUtil jwtUtil;

    @BeforeEach
    void setupStubs() {
        RabbitTemplateInvokeStubs.runInvokeCallbacks(rabbitTemplate);
    }

    // ==================== 辅助方法 ====================

    private long createQuestionWithAnalysis(String teacherToken, int type, String content, String answer,
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
            // 双保险：题库实体若未由接口写入 analysis 则直接更新
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
        body.put("title", unique("错题测试考试"));
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
        // 结束考试
        perform(jsonPost("/api/exams/" + examId + "/force-end", teacherToken, null), 200);
        // 执行客观判分
        perform(jsonPost("/api/exams/" + examId + "/grading/run", teacherToken, null), 200);
        // 汇总成绩
        perform(jsonPost("/api/exams/" + examId + "/scores/summarize", teacherToken, null), 200);
        // 发布成绩
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode arr = body.putArray("examIds");
        arr.add(examId);
        perform(jsonPost("/api/scores/publish", teacherToken, objectMapper.writeValueAsString(body)), 200);
    }

    // ==================== 集成测试用例 ====================

    @Test
    @DisplayName("错题本：错题判定含部分对与零分，满分题排除，且 analysis 正确取自题目")
    void wrongQuestions_identifiesWrongQuestionsAndAnalysis() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long classId = createClassForExam(teacher, student);

        // 1. 题库造题：
        // q1: 单选 4分，正确答案 A，解析「1加1等于2」
        // q2: 多选 6分，正确答案 A,C，解析「2和3是质数」
        // q3: 判断 4分，正确答案 T，解析「水沸腾规律」
        long q1 = createQuestionWithAnalysis(teacher, 1, "单选：1+1=?", "A", List.of("2", "3", "4", "5"), 4.0, "单选解析：1加1等于2");
        long q2 = createQuestionWithAnalysis(teacher, 2, "多选：下列哪些是质数", "A,C", List.of("2", "4", "3", "9"), 6.0, "多选解析：2和3是质数");
        long q3 = createQuestionWithAnalysis(teacher, 3, "判断：水加热到100度沸腾", "T", null, 4.0, "判断解析：水沸腾规律");

        long paperId = createPaper(teacher, unique("试卷"), 14.0);
        addPaperQuestion(teacher, paperId, q1, 4.0);
        addPaperQuestion(teacher, paperId, q2, 6.0);
        addPaperQuestion(teacher, paperId, q3, 4.0);

        long examId = createAndPublishExam(teacher, paperId, classId);

        // 学生进入并作答：
        // q1: 作答 "B" -> 0 分（错题！）
        // q2: 作答 "A" -> 漏选得 3 分（满分 6，部分对，错题！）
        // q3: 作答 "T" -> 得满分 4 分（正确题，非错题！）
        Map<Integer, Long> qByType = enterExam(student, examId);
        Map<Long, String> answers = new HashMap<>();
        answers.put(qByType.get(1), "B");
        answers.put(qByType.get(2), "A");
        answers.put(qByType.get(3), "T");
        submitAnswers(student, examId, answers);

        // 汇总与发布成绩
        summarizeAndPublishScores(teacher, examId);

        // 学生请求错题本接口：GET /api/scores/my/wrong-questions
        JsonNode resp = perform(jsonGet("/api/scores/my/wrong-questions?page=1&size=10", student), 200).get("data");

        assertNotNull(resp);
        assertEquals(1, resp.get("total").asLong());
        JsonNode groups = resp.get("groups");
        assertEquals(1, groups.size());

        JsonNode group = groups.get(0);
        assertEquals(examId, group.get("examId").asLong());
        JsonNode wrongQuestions = group.get("wrongQuestions");
        // 只有 q1 和 q2 判定为错题，q3（满分）不得出现
        assertEquals(2, wrongQuestions.size());

        // 检查错题 1 (q1)
        JsonNode item1 = wrongQuestions.get(0);
        assertEquals(qByType.get(1).longValue(), item1.get("questionId").asLong());
        assertEquals("B", item1.get("myAnswer").asText());
        assertEquals("A", item1.get("correctAnswer").asText());
        assertEquals(0, new BigDecimal(item1.get("myScore").asText()).compareTo(BigDecimal.ZERO));
        assertEquals(0, new BigDecimal(item1.get("fullScore").asText()).compareTo(BigDecimal.valueOf(4.0)));
        // 核心语义断言：analysis 必须取自题目解析，严禁为判分依据或空串
        assertEquals("单选解析：1加1等于2", item1.get("analysis").asText());

        // 检查错题 2 (q2 部分对)
        JsonNode item2 = wrongQuestions.get(1);
        assertEquals(qByType.get(2).longValue(), item2.get("questionId").asLong());
        assertEquals("A", item2.get("myAnswer").asText());
        assertEquals("A,C", item2.get("correctAnswer").asText());
        assertTrue(new BigDecimal(item2.get("myScore").asText()).compareTo(BigDecimal.ZERO) > 0, "部分对得分应大于0");
        assertTrue(new BigDecimal(item2.get("myScore").asText()).compareTo(new BigDecimal(item2.get("fullScore").asText())) < 0, "部分对得分应严格小于满分");
        assertEquals("多选解析：2和3是质数", item2.get("analysis").asText());
    }

    @Test
    @DisplayName("错题本：分页以考试为粒度，仅对页内考试重算")
    void wrongQuestions_pagedByExam() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long classId = createClassForExam(teacher, student);

        // 创建两场考试
        long q1 = createQuestionWithAnalysis(teacher, 1, "题1", "A", List.of("A", "B"), 5.0, "解析1");
        long paper1 = createPaper(teacher, unique("卷1"), 5.0);
        addPaperQuestion(teacher, paper1, q1, 5.0);
        long exam1 = createAndPublishExam(teacher, paper1, classId);

        long q2 = createQuestionWithAnalysis(teacher, 1, "题2", "A", List.of("A", "B"), 5.0, "解析2");
        long paper2 = createPaper(teacher, unique("卷2"), 5.0);
        addPaperQuestion(teacher, paper2, q2, 5.0);
        long exam2 = createAndPublishExam(teacher, paper2, classId);

        // 学生在两场考试中均答错
        Map<Integer, Long> qByType1 = enterExam(student, exam1);
        submitAnswers(student, exam1, Map.of(qByType1.get(1), "B"));
        summarizeAndPublishScores(teacher, exam1);

        Map<Integer, Long> qByType2 = enterExam(student, exam2);
        submitAnswers(student, exam2, Map.of(qByType2.get(1), "B"));
        summarizeAndPublishScores(teacher, exam2);

        // 分页请求：page=1, size=1 -> 应当只返回 1 场考试组，total=2
        JsonNode page1 = perform(jsonGet("/api/scores/my/wrong-questions?page=1&size=1", student), 200).get("data");
        assertEquals(2, page1.get("total").asLong());
        assertEquals(1, page1.get("groups").size());

        // 请求 page=2, size=1 -> 应当返回第 2 场考试组
        JsonNode page2 = perform(jsonGet("/api/scores/my/wrong-questions?page=2&size=1", student), 200).get("data");
        assertEquals(2, page2.get("total").asLong());
        assertEquals(1, page2.get("groups").size());
        assertNotEquals(page1.get("groups").get(0).get("examId").asLong(), page2.get("groups").get(0).get("examId").asLong());
    }

    @Test
    @DisplayName("错题本与逐题回顾：未发布考试严格隐藏，单场回顾返回 400")
    void wrongQuestionsAndReview_requirePublishedRoot() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long classId = createClassForExam(teacher, student);

        long q1 = createQuestionWithAnalysis(teacher, 1, "未发布题", "A", List.of("A", "B"), 5.0, "解析");
        long paper = createPaper(teacher, unique("卷"), 5.0);
        addPaperQuestion(teacher, paper, q1, 5.0);
        long examId = createAndPublishExam(teacher, paper, classId);

        Map<Integer, Long> qByType = enterExam(student, examId);
        submitAnswers(student, examId, Map.of(qByType.get(1), "B"));
        // 结束、判分并仅汇总，未发布！
        perform(jsonPost("/api/exams/" + examId + "/force-end", teacher, null), 200);
        perform(jsonPost("/api/exams/" + examId + "/grading/run", teacher, null), 200);
        perform(jsonPost("/api/exams/" + examId + "/scores/summarize", teacher, null), 200);

        // 1. 错题本不包含该考试
        JsonNode wrongResp = perform(jsonGet("/api/scores/my/wrong-questions", student), 200).get("data");
        assertEquals(0, wrongResp.get("total").asLong());

        // 2. 逐题回顾返回 400 "成绩待发布"
        perform(jsonGet("/api/scores/my/exams/" + examId + "/review", student), 400);
    }

    @Test
    @DisplayName("单场逐题回顾：已发布考试返回全量题目及解析，无成绩返回 404")
    void examReview_successAndNotFound() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        String otherStudent = registerStudent();
        long classId = createClassForExam(teacher, student, otherStudent);

        long q1 = createQuestionWithAnalysis(teacher, 1, "题目1", "A", List.of("A", "B"), 5.0, "解析1");
        long q2 = createQuestionWithAnalysis(teacher, 3, "题目2", "T", null, 5.0, "解析2");
        long paper = createPaper(teacher, unique("试卷"), 10.0);
        addPaperQuestion(teacher, paper, q1, 5.0);
        addPaperQuestion(teacher, paper, q2, 5.0);
        long examId = createAndPublishExam(teacher, paper, classId);

        // 仅 student 参加
        Map<Integer, Long> qByType = enterExam(student, examId);
        submitAnswers(student, examId, Map.of(qByType.get(1), "A", qByType.get(3), "F"));
        summarizeAndPublishScores(teacher, examId);

        // student 查逐题回顾：成功 200，全量 2 道题都返回（无论对错）
        JsonNode review = perform(jsonGet("/api/scores/my/exams/" + examId + "/review", student), 200).get("data");
        assertNotNull(review);
        assertEquals(examId, review.get("examId").asLong());
        JsonNode questions = review.get("questions");
        assertEquals(2, questions.size());

        // 未参加考试的 otherStudent 查该考试：404 暂无本人成绩记录
        perform(jsonGet("/api/scores/my/exams/" + examId + "/review", otherStudent), 404);
    }
}

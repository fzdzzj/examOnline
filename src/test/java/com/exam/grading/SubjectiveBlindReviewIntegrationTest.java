package com.exam.grading;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.auth.service.JwtUtil;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.exam.service.ExamStateMachineService;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.entity.SubjectiveGrade;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.grading.mapper.SubjectiveGradeMapper;
import com.exam.submission.dto.SubmitMessage;
import com.exam.submission.mq.ExamSubmitConsumer;
import com.exam.support.IntegrationTestBase;
import com.exam.support.RabbitTemplateInvokeStubs;
import com.exam.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.JsonNode;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 简答题双评盲评与分差仲裁集成测试（add-subjective-double-blind-grading 全场景，
 * H2 + MockMvc，MQ 为 Mock）：
 *
 * <ul>
 *   <li>盲评脱敏：二评教师查询接口拿到的行考生身份打码（考生 ***）、一评分数评语不可见；</li>
 *   <li>背靠背双评：一评落库终分保持 NULL，二评自动分流，同教师互斥 400；</li>
 *   <li>分差裁决：分差 ≤ 容差（缺省 2.0）自动取均分回填 subjective_grades.score 并刷新答卷主观总分；
 *       分差超标不回填，流转待仲裁并出现在仲裁清单；</li>
 *   <li>仲裁终审：仲裁员（考试归属人）提交仲裁分回填终分结案，超满分 400，非归属人查仲裁清单 403。</li>
 * </ul>
 */
class SubjectiveBlindReviewIntegrationTest extends IntegrationTestBase {

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
    private SubjectiveGradeMapper subjectiveGradeMapper;
    @Autowired
    private ExamMapper examMapper;
    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private ExamSubmitConsumer submitConsumer;
    @Autowired
    private UserMapper userMapper;

    // ==================== 造数：一场已判分、含一道简答题的考试 ====================

    /** 盲评夹具：教师 A 归属考试，教师 B 为二评人，一名学生已交卷且判分已运行。 */
    private record BlindFixture(String teacherA, String teacherB, long examId,
                                long questionId, long submissionId, long studentId) {
    }

    private BlindFixture prepareBlindFixture() throws Exception {
        String teacherA = registerTeacher();
        String teacherB = registerTeacher();
        String student = registerStudent();

        long qShort = createQuestion(teacherA, 4, "简答：什么是HTTP", "HTTP,协议,无状态", null, null);
        long paperId = createPaper(teacherA, unique("试卷"), 6);
        addPaperQuestion(teacherA, paperId, qShort, 6);
        long classId = createClassForExam(teacherA);
        long examId = createExam(teacherA, paperId, classId);
        perform(jsonPost("/api/exams/" + examId + "/publish", teacherA, null), 200);
        stateMachineService.autoAdvance();

        ensureExamClassMembership(student, examId);
        JsonNode data = perform(jsonPost("/api/exam-taking/exams/" + examId + "/enter", student, null), 200)
                .get("data");
        Map<Integer, Long> byType = new HashMap<>();
        data.get("questions").forEach(q -> byType.put(q.get("type").asInt(), q.get("questionId").asLong()));
        long questionId = byType.get(4);

        ObjectNode answers = objectMapper.createObjectNode();
        answers.put(String.valueOf(questionId), "HTTP 是无状态的协议");
        ObjectNode request = objectMapper.createObjectNode();
        request.set("answers", answers);
        perform(jsonPost("/api/exam-taking/exams/" + examId + "/submit", student,
                objectMapper.writeValueAsString(request)), 200);
        consumeCapturedMessages(examId);

        // 教师提前结束考试 → 判分入口要求考试 ≥ ENDED（spec GIVEN：考试已结束且客观判分已运行）
        perform(jsonPost("/api/exams/" + examId + "/force-end", teacherA, null), 200);
        perform(jsonPost("/api/exams/" + examId + "/grading/run", teacherA, null), 200);

        long studentId = jwtUtil.parseAccessToken(student).getId();
        long submissionId = submissionIdOf(examId, studentId);
        return new BlindFixture(teacherA, teacherB, examId, questionId, submissionId, studentId);
    }

    private void addPaperQuestion(String token, long paperId, long questionId, double score) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("questionId", questionId);
        body.put("score", score);
        perform(jsonPost("/api/papers/" + paperId + "/questions", token,
                objectMapper.writeValueAsString(body)), 200);
    }

    private long createExam(String teacher, long paperId, Long classId) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("title", unique("考试"));
        body.put("paperId", paperId);
        body.put("classId", classId);
        body.put("startTime", LocalDateTime.now().minusMinutes(1).toString());
        body.put("endTime", LocalDateTime.now().plusHours(2).toString());
        body.put("durationMinutes", 30);
        JsonNode data = perform(jsonPost("/api/exams", teacher,
                objectMapper.writeValueAsString(body)), 200).get("data");
        return data.get("id").asLong();
    }

    /** 取 Mock 收到的交卷消息，驱动真实消费者批量落库（消费幂等，重复驱动不重复写）。 */
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
            submitConsumer.onBatch(List.of(amqp), mock(com.rabbitmq.client.Channel.class));
        }
    }

    private long submissionIdOf(long examId, long studentId) {
        return gradingSubmissionMapper.selectOne(Wrappers.<GradingSubmission>lambdaQuery()
                .eq(GradingSubmission::getExamId, examId)
                .eq(GradingSubmission::getStudentId, studentId)).getId();
    }

    /** 盲评提交请求体。 */
    private String blindSubmitBody(long submissionId, long questionId, String score, String comment)
            throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("submissionId", submissionId);
        body.put("questionId", questionId);
        body.put("score", new BigDecimal(score));
        if (comment != null) {
            body.put("comment", comment);
        }
        return objectMapper.writeValueAsString(body);
    }

    /** 仲裁请求体。 */
    private String arbitrateBody(long submissionId, long questionId, String score, String comment)
            throws Exception {
        return blindSubmitBody(submissionId, questionId, score, comment);
    }

    private JsonNode blindSubmit(String token, long examId, String body, int expectedStatus) throws Exception {
        return perform(jsonPost("/api/exams/" + examId + "/grading/subjective/blind-submit", token, body),
                expectedStatus);
    }

    private JsonNode arbitrate(String token, long examId, String body, int expectedStatus) throws Exception {
        return perform(jsonPost("/api/exams/" + examId + "/grading/subjective/arbitrate", token, body),
                expectedStatus);
    }

    private JsonNode arbitrationList(String token, long examId, int expectedStatus) throws Exception {
        return perform(jsonGet("/api/exams/" + examId + "/grading/subjective/arbitration-list", token),
                expectedStatus);
    }

    private JsonNode blindRows(String token, long examId, long questionId) throws Exception {
        return perform(jsonGet("/api/exams/" + examId + "/grading/subjective?questionId=" + questionId
                + "&blind=true", token), 200);
    }

    private SubjectiveGrade gradeRow(long submissionId, long questionId) {
        return subjectiveGradeMapper.selectOne(Wrappers.<SubjectiveGrade>lambdaQuery()
                .eq(SubjectiveGrade::getSubmissionId, submissionId)
                .eq(SubjectiveGrade::getQuestionId, questionId));
    }

    // ==================== 场景 ====================

    @Test
    @DisplayName("盲评脱敏：二评教师查询打码行——考生 ***、无任何教师评分评语，可作答不可见人")
    void blindViewMasksIdentityAndPeerReview() throws Exception {
        BlindFixture fx = prepareBlindFixture();
        // 教师 A 先落一评（含评语），二评教师在提交前查询不得见到任何评阅信息
        blindSubmit(fx.teacherA(), fx.examId(),
                blindSubmitBody(fx.submissionId(), fx.questionId(), "5.0", "要点完整"), 200);

        JsonNode view = blindRows(fx.teacherB(), fx.examId(), fx.questionId());
        JsonNode rows = view.get("data").get("rows");
        assertEquals(1, rows.size(), "判分已运行，简答题应有 1 行");
        JsonNode row = rows.get(0);
        assertEquals("考生 ***", row.get("studentName").asText(), "考生姓名必须打码");
        assertTrue(row.get("studentId") == null || row.get("studentId").isNull(),
                "考生 ID 不得透出");
        assertTrue(row.get("score") == null || row.get("score").isNull(),
                "教师 A 的评分不得向二评教师泄露");
        assertTrue(row.get("comment") == null || row.get("comment").isNull(),
                "教师 A 的评语不得向二评教师泄露");
        assertTrue(row.get("graderId") == null || row.get("graderId").isNull(),
                "批改人不得透出");
        assertTrue(row.get("studentAnswer").asText().contains("HTTP"),
                "作答内容必须可见（盲评对象）");
    }

    @Test
    @DisplayName("一评落库：roundType=FIRST、终分保持 NULL、状态待二评")
    void firstBlindReviewKeepsFinalScorePending() throws Exception {
        BlindFixture fx = prepareBlindFixture();
        JsonNode response = blindSubmit(fx.teacherA(), fx.examId(),
                blindSubmitBody(fx.submissionId(), fx.questionId(), "5.0", "要点完整"), 200)
                .get("data");
        assertEquals("FIRST", response.get("roundType").asText());
        assertEquals("PENDING_SECOND", response.get("status").asText());

        assertNull(gradeRow(fx.submissionId(), fx.questionId()).getScore(),
                "一评后终分必须保持 NULL，待二评裁决");
    }

    @Test
    @DisplayName("同教师互斥：一评教师重复提交被 400 拦截（背靠背防人情分）")
    void sameTeacherCannotSubmitSecondReview() throws Exception {
        BlindFixture fx = prepareBlindFixture();
        blindSubmit(fx.teacherA(), fx.examId(),
                blindSubmitBody(fx.submissionId(), fx.questionId(), "5.0", null), 200);
        blindSubmit(fx.teacherA(), fx.examId(),
                blindSubmitBody(fx.submissionId(), fx.questionId(), "6.0", null), 400);
    }

    @Test
    @DisplayName("分差容差内：二评提交后自动取均分回填终分并刷新答卷主观总分")
    void secondReviewWithinToleranceResolvesAverage() throws Exception {
        BlindFixture fx = prepareBlindFixture();
        blindSubmit(fx.teacherA(), fx.examId(),
                blindSubmitBody(fx.submissionId(), fx.questionId(), "5.0", null), 200);
        JsonNode response = blindSubmit(fx.teacherB(), fx.examId(),
                blindSubmitBody(fx.submissionId(), fx.questionId(), "6.0", "基本正确"), 200)
                .get("data");
        assertEquals("SECOND", response.get("roundType").asText());
        assertEquals("RESOLVED", response.get("status").asText());
        assertEquals(0, new BigDecimal("5.5").compareTo(new BigDecimal(response.get("finalScore").asText())),
                "分差 1.0 ≤ 容差 2.0，终分必须为均分 5.5");

        assertEquals(0, new BigDecimal("5.5").compareTo(gradeRow(fx.submissionId(), fx.questionId()).getScore()),
                "终分必须回填 subjective_grades.score");
        GradingSubmission submission = gradingSubmissionMapper.selectById(fx.submissionId());
        assertEquals(0, new BigDecimal("5.5").compareTo(submission.getSubjectiveScore()),
                "答卷主观总分必须同步刷新");
    }

    @Test
    @DisplayName("分差超标：不回填保持 NULL、流转待仲裁、进入仲裁清单；非归属人查清单 403")
    void secondReviewExceedingToleranceFlowsToArbitration() throws Exception {
        BlindFixture fx = prepareBlindFixture();
        blindSubmit(fx.teacherA(), fx.examId(),
                blindSubmitBody(fx.submissionId(), fx.questionId(), "2.0", "未达要点"), 200);
        JsonNode response = blindSubmit(fx.teacherB(), fx.examId(),
                blindSubmitBody(fx.submissionId(), fx.questionId(), "6.0", "回答完整"), 200)
                .get("data");
        assertEquals("ARBITRATION_PENDING", response.get("status").asText(),
                "分差 4.0 > 容差 2.0 必须流转待仲裁");
        assertNull(gradeRow(fx.submissionId(), fx.questionId()).getScore(),
                "未达成共识的分数绝不能计入终分");

        // 一评未二评时不进仲裁清单
        // （上面已产生一评+二评，此处仅断言超标对出现在清单中）
        JsonNode list = arbitrationList(fx.teacherA(), fx.examId(), 200).get("data");
        assertEquals(1, list.size(), "分差超标题目必须出现在待仲裁清单");
        JsonNode item = list.get(0);
        assertEquals(fx.submissionId(), item.get("submissionId").asLong());
        assertEquals(0, new BigDecimal("2.0").compareTo(new BigDecimal(item.get("firstScore").asText())));
        assertEquals(0, new BigDecimal("6.0").compareTo(new BigDecimal(item.get("secondScore").asText())));
        assertEquals("ARBITRATION_PENDING", item.get("status").asText());

        // 非归属人（二评教师）不得查看仲裁清单
        arbitrationList(fx.teacherB(), fx.examId(), 403);
    }

    @Test
    @DisplayName("仲裁终审：超满分 400 拦截；合法仲裁分回填终分结案并清空清单")
    void arbitrationBackfillsFinalScoreAndClosesCase() throws Exception {
        BlindFixture fx = prepareBlindFixture();
        blindSubmit(fx.teacherA(), fx.examId(),
                blindSubmitBody(fx.submissionId(), fx.questionId(), "2.0", null), 200);
        blindSubmit(fx.teacherB(), fx.examId(),
                blindSubmitBody(fx.submissionId(), fx.questionId(), "6.0", null), 200);

        // 仲裁分不得超过本题满分 6.0
        arbitrate(fx.teacherA(), fx.examId(),
                arbitrateBody(fx.submissionId(), fx.questionId(), "9.0", null), 400);

        JsonNode response = arbitrate(fx.teacherA(), fx.examId(),
                arbitrateBody(fx.submissionId(), fx.questionId(), "4.5", "取中仲裁"), 200).get("data");
        assertEquals("ARBITRATED", response.get("status").asText());
        assertEquals(0, new BigDecimal("4.5").compareTo(new BigDecimal(response.get("finalScore").asText())));

        BigDecimal backfilled = gradeRow(fx.submissionId(), fx.questionId()).getScore();
        assertNotNull(backfilled, "仲裁分必须回填 subjective_grades.score");
        assertEquals(0, new BigDecimal("4.5").compareTo(backfilled),
                "仲裁分必须回填 subjective_grades.score");
        GradingSubmission submission = gradingSubmissionMapper.selectById(fx.submissionId());
        assertNotNull(submission.getSubjectiveScore(), "答卷主观总分必须按仲裁分刷新");
        assertEquals(0, new BigDecimal("4.5").compareTo(submission.getSubjectiveScore()),
                "答卷主观总分必须按仲裁分刷新");

        assertEquals(0, arbitrationList(fx.teacherA(), fx.examId(), 200).get("data").size(),
                "仲裁结案后题目必须离开待仲裁清单");
    }
}

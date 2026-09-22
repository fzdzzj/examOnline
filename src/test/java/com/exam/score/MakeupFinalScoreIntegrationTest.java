package com.exam.score;

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
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
 * 补考最终成绩接口接线集成测试（spec「合并规则有真实调用者」「最终成绩经接口可查」，收口遗留 #5）：
 *
 * <p>让已验收但零调用的 {@code MakeupScoreService.finalScore} 经真实链路被调用并断言：
 * <ul>
 *   <li>主考 + 补考均有成绩的学生，经接口合并按考试配置规则（本用例 takeAverage）得到最终成绩；</li>
 *   <li>历史成绩保留不覆盖（查询前后主考/补考答卷总分不变、两条记录都在）；</li>
 *   <li>教师侧越权：仅归属教师可查、学生无 exam:manage 被拒；</li>
 *   <li>学生侧仅查本人：主考未发布 → "成绩待发布" 400；发布后可查、按补考 ID 查同样解析到主考；</li>
 *   <li>学生侧进行中复核隐藏分数（reviewing=true、finalScore=null）。</li>
 * </ul>
 *
 * <p>全类不自行建表（建表唯一来源 schema.sql）；RabbitTemplate 以 MockitoBean 替身，
 * 交卷消息用 ArgumentCaptor 取出后手工驱动 submitConsumer 真落库；状态机手工 autoAdvance 驱动。
 * 手法与 PostExamClosureIntegrationTest 一致。
 *
 * <p>最终成绩以"教师/学生接口返回值 vs 服务端合并函数用 DB 里两条实际总分"做断言，
 * 不硬编码某题分值——避免依赖客观判分的未答题计分语义。
 */
class MakeupFinalScoreIntegrationTest extends IntegrationTestBase {

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    /**
     * ExamSubmitSender 修复后「发送 + confirm 等待」整体移入 RabbitTemplate.invoke() 作用域；
     * mock 的 invoke 默认不执行 callback，会漏掉 callback 内的 3 参 convertAndSend 调用。
     * 本桩让 invoke 真实执行 callback，既有 verify 断言不变（fix-broker-confirm-and-dlq-roundtrip）。
     */
    @BeforeEach
    void runRabbitInvokeCallbacks() {
        RabbitTemplateInvokeStubs.runInvokeCallbacks(rabbitTemplate);
    }

    @Autowired
    private ExamStateMachineService stateMachineService;
    @Autowired
    private ExamMapper examMapper;
    @Autowired
    private GradingSubmissionMapper gradingMapper;
    @Autowired
    private ExamSubmitConsumer submitConsumer;
    @Autowired
    private JwtUtil jwtUtil;

    // ==================== 主用例：真实链路的合并 + 历史保留 ====================

    /**
     * 一条真实链路：建卷 → 建主考（发布）→ 学生 A 主考交卷 → 提前结束 → 判分 → 汇总 →
     * 建补考（takeAverage，名单[A]）→ 补考交卷 → 判分 → 汇总 →
     * 教师接口查最终成绩应等于 DB 中主考/补考两条已批改总分的平均值，
     * 且主考/补考答卷总分在查询前后均保留不覆盖。
     */
    @Test
    void mergedFinalScoreThroughRealLinkWithHistoryPreserved() throws Exception {
        String teacher = registerTeacher();
        String studentA = registerStudent();
        long aId = studentIdOf(studentA);

        // 1. 建卷（全客观卷，判分/汇总无简答分支）
        long paperId = preparePaper(teacher);

        // 2. 建主考 → 发布 → 进行中
        long mainId = createExam(teacher, paperId, LocalDateTime.now().minusMinutes(1),
                LocalDateTime.now().plusHours(2), 30);
        perform(jsonPost("/api/exams/" + mainId + "/publish", teacher, null), 200);
        stateMachineService.autoAdvance();
        assertEquals(Exam.STATUS_IN_PROGRESS, examMapper.selectById(mainId).getStatus());

        // 3. A 主考交卷（只答第一题）
        List<Long> mainQs = questionIds(enter(studentA, mainId, 200));
        submitAnswers(studentA, mainId, Map.of(mainQs.get(0), "A"));

        // 4. 主考提前结束 → 判分 → 汇总（已批改）
        perform(jsonPost("/api/exams/" + mainId + "/force-end", teacher, null), 200);
        perform(jsonPost("/api/exams/" + mainId + "/grading/run", teacher, null), 200);
        perform(jsonPost("/api/exams/" + mainId + "/scores/summarize", teacher, null), 200);
        assertEquals(Exam.STATUS_GRADED, examMapper.selectById(mainId).getStatus());
        BigDecimal mainTotal = totalScoreOf(mainId, aId);

        // 5. 建补考（takeAverage，名单[A]，独立时间窗）
        long makeupId = createMakeup(teacher, mainId, "takeAverage", aId);
        Exam makeupExam = examMapper.selectById(makeupId);
        assertEquals(mainId, makeupExam.getParentExamId());
        assertEquals("takeAverage", makeupExam.getMakeupScoreRule());

        // 6. 补考发布 → 进行中 → A 补考交卷（全对）
        perform(jsonPost("/api/exams/" + makeupId + "/publish", teacher, null), 200);
        stateMachineService.autoAdvance();
        List<Long> mkQs = questionIds(enter(studentA, makeupId, 200));
        submitAnswers(studentA, makeupId, Map.of(
                mkQs.get(0), "A",
                mkQs.get(1), "A,C",
                mkQs.get(2), "T"));

        // 7. 补考提前结束 → 判分 → 汇总
        perform(jsonPost("/api/exams/" + makeupId + "/force-end", teacher, null), 200);
        perform(jsonPost("/api/exams/" + makeupId + "/grading/run", teacher, null), 200);
        perform(jsonPost("/api/exams/" + makeupId + "/scores/summarize", teacher, null), 200);
        assertEquals(Exam.STATUS_GRADED, examMapper.selectById(makeupId).getStatus());
        BigDecimal makeupTotal = totalScoreOf(makeupId, aId);
        BigDecimal expectedFinal = average(mainTotal, makeupTotal);

        // 8. 教师接口查最终成绩 = (主考 + 补考)平均值，且区别于任一单次成绩（证明合并生效，非返回某次）
        JsonNode teacherView = perform(jsonGet(
                "/api/exams/" + makeupId + "/scores/makeup-final/" + aId, teacher), 200).get("data");
        assertEquals(0, teacherView.get("finalScore").decimalValue().compareTo(expectedFinal),
                "最终成绩应按配置规则合并为主考/补考平均值");
        assertEquals(false, teacherView.get("reviewing").asBoolean());

        // 9. 历史成绩保留不覆盖：查询前后主考/补考两条答卷总分不变、都在
        assertEquals(0, mainTotal.compareTo(totalScoreOf(mainId, aId)), "主考历史成绩须保留");
        assertEquals(0, makeupTotal.compareTo(totalScoreOf(makeupId, aId)), "补考历史成绩须保留");
        assertEquals(2L, familyScoreCount(mainId, makeupId, aId).longValue(), "主考+补考两条历史成绩均应保留");

        // 10. 学生侧：主考未发布 → "成绩待发布" 400
        JsonNode notPublished = perform(jsonGet("/api/scores/makeup-final?examId=" + makeupId, studentA), 400);
        assertTrue(notPublished.get("message").asText().contains("待发布"));

        // 11. 发布主考 → 学生查本人（按主考 ID 与按补考 ID 都解析到同一最终成绩）
        publishExam(teacher, mainId);
        assertEquals(Exam.STATUS_PUBLISHED, examMapper.selectById(mainId).getStatus());
        assertStudentFinal(studentA, makeupId, expectedFinal);

        // 12. 复核中隐藏：学生申请主考复核后，最终成绩隐藏且标记 reviewing
        applyReview(mainId, studentA, "对主考分数有疑问");
        JsonNode reviewing = perform(jsonGet("/api/scores/makeup-final?examId=" + makeupId, studentA), 200).get("data");
        assertTrue(reviewing.get("reviewing").asBoolean(), "进行中复核应隐藏分数");
        assertNull(reviewing.get("finalScore"), "复核中不得返回最终成绩");
    }

    // ==================== 边界：越权与权限 ====================

    /** 教师侧越权：非归属教师 403、学生无 exam:manage 403；归属教师可查（无成绩记录 → finalScore null）。 */
    @Test
    void makeupFinalQueryRespectsOwnershipAndPermission() throws Exception {
        String teacher = registerTeacher();
        String otherTeacher = registerTeacher();
        String student = registerStudent();
        long sId = studentIdOf(student);
        long mainId = inProgressOwnedExam(teacher);

        // 非归属教师：服务层归属校验兜底 → 403
        perform(jsonGet("/api/exams/" + mainId + "/scores/makeup-final/" + sId, otherTeacher), 403);
        // 学生：方法级 exam:manage 只授予 TEACHER/ADMIN → 403
        perform(jsonGet("/api/exams/" + mainId + "/scores/makeup-final/" + sId, student), 403);
        // 归属教师可查：该生暂无任何答卷 → finalScore null、reviewing=false
        JsonNode data = perform(jsonGet(
                "/api/exams/" + mainId + "/scores/makeup-final/" + sId, teacher), 200).get("data");
        assertNull(data.get("finalScore"), "无答卷记录时最终成绩应为 null");
        assertEquals(false, data.get("reviewing").asBoolean());
    }

    // ==================== 造数 ====================

    /** 组一卷三题：单选 5 + 多选 6 + 判断 4 = 15 分（全客观，判分/汇总无简答分支）。 */
    private long preparePaper(String token) throws Exception {
        long q1 = createQuestion(token, 1, "单选：1+1=?", "A", List.of("A", "B", "C", "D"), null);
        long q2 = createQuestion(token, 2, "多选：下列哪些是质数", "A,C", List.of("2", "4", "3", "9"), null);
        long q3 = createQuestion(token, 3, "判断：水加热到100摄氏度会沸腾", "T", null, null);
        long paperId = createPaper(token, unique("试卷"), 15);
        addPaperQuestion(token, paperId, q1, 5);
        addPaperQuestion(token, paperId, q2, 6);
        addPaperQuestion(token, paperId, q3, 4);
        return paperId;
    }

    private void addPaperQuestion(String token, long paperId, long questionId, double score) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("questionId", questionId);
        body.put("score", score);
        perform(jsonPost("/api/papers/" + paperId + "/questions", token,
                objectMapper.writeValueAsString(body)), 200);
    }

    private long createExam(String token, long paperId, LocalDateTime start, LocalDateTime end, int duration)
            throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("title", unique("考试"));
        body.put("paperId", paperId);
        body.put("startTime", start.toString());
        body.put("endTime", end.toString());
        body.put("durationMinutes", duration);
        return perform(jsonPost("/api/exams", token, objectMapper.writeValueAsString(body)), 200)
                .get("data").get("id").asLong();
    }

    /** 造一场归属教师的"进行中"考试（越权边界用例共用）。 */
    private long inProgressOwnedExam(String teacher) throws Exception {
        long paperId = preparePaper(teacher);
        long examId = createExam(teacher, paperId, LocalDateTime.now().minusMinutes(1),
                LocalDateTime.now().plusHours(2), 30);
        perform(jsonPost("/api/exams/" + examId + "/publish", teacher, null), 200);
        stateMachineService.autoAdvance();
        assertEquals(Exam.STATUS_IN_PROGRESS, examMapper.selectById(examId).getStatus());
        return examId;
    }

    private long createMakeup(String teacher, long mainId, String rule, long... studentIds) throws Exception {
        // H2 DATETIME 只存微秒，请求带纳秒会因截断比较失败，先截断到微秒
        LocalDateTime start = LocalDateTime.now().minusMinutes(1).truncatedTo(ChronoUnit.MICROS);
        LocalDateTime end = LocalDateTime.now().plusHours(2).truncatedTo(ChronoUnit.MICROS);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("startTime", start.toString());
        body.put("endTime", end.toString());
        body.put("durationMinutes", 20);
        body.put("makeupScoreRule", rule);
        for (long studentId : studentIds) {
            body.putArray("studentIds").add(studentId);
        }
        return perform(jsonPost("/api/exams/" + mainId + "/makeups", teacher,
                objectMapper.writeValueAsString(body)), 200).get("data").get("examId").asLong();
    }

    private void publishExam(String teacher, long examId) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.putArray("examIds").add(examId);
        perform(jsonPost("/api/scores/publish", teacher, objectMapper.writeValueAsString(body)), 200);
    }

    private void applyReview(long examId, String student, String reason) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("reason", reason);
        perform(jsonPost("/api/exams/" + examId + "/score-reviews", student,
                objectMapper.writeValueAsString(body)), 200);
    }

    // ==================== 断言 ====================

    /** 读该生在该场已批改答卷的总分（null 则测不符），调用侧用于算期望值与验证保留。 */
    private BigDecimal totalScoreOf(long examId, long studentId) {
        GradingSubmission s = gradingMapper.selectOne(Wrappers.<GradingSubmission>lambdaQuery()
                .eq(GradingSubmission::getExamId, examId)
                .eq(GradingSubmission::getStudentId, studentId));
        assertNotNull(s, "应存在答卷：exam=" + examId + " student=" + studentId);
        assertNotNull(s.getTotalScore(), "答卷应已汇总出总分：exam=" + examId);
        return s.getTotalScore();
    }

    private Long familyScoreCount(long mainId, long makeupId, long studentId) {
        return gradingMapper.selectCount(Wrappers.<GradingSubmission>lambdaQuery()
                .in(GradingSubmission::getExamId, List.of(mainId, makeupId))
                .eq(GradingSubmission::getStudentId, studentId));
    }

    /** 与 MakeupScoreService 的 takeAverage 合并口径一致：四舍五入保留 1 位小数。 */
    private BigDecimal average(BigDecimal a, BigDecimal b) {
        return a.add(b).divide(BigDecimal.valueOf(2), 1, RoundingMode.HALF_UP);
    }

    private void assertStudentFinal(String student, long examId, BigDecimal expected) throws Exception {
        JsonNode data = perform(jsonGet("/api/scores/makeup-final?examId=" + examId, student), 200).get("data");
        assertEquals(0, data.get("finalScore").decimalValue().compareTo(expected),
                "学生查本人最终成绩应为平均值");
        assertEquals(false, data.get("reviewing").asBoolean());
    }

    // ==================== 工具 ====================

    private JsonNode enter(String student, long examId, int expectedStatus) throws Exception {
        return perform(jsonPost("/api/exam-taking/exams/" + examId + "/enter", student, null),
                expectedStatus).get("data");
    }

    private List<Long> questionIds(JsonNode enterData) {
        List<Long> ids = new ArrayList<>();
        enterData.get("questions").forEach(q -> ids.add(q.get("questionId").asLong()));
        return ids;
    }

    private void submitAnswers(String student, long examId, Map<Long, String> answers) throws Exception {
        ObjectNode answersNode = objectMapper.createObjectNode();
        answers.forEach((q, a) -> answersNode.put(String.valueOf(q), a));
        ObjectNode body = objectMapper.createObjectNode();
        body.set("answers", answersNode);
        perform(jsonPost("/api/exam-taking/exams/" + examId + "/submit", student,
                objectMapper.writeValueAsString(body)), 200);
        consumeCapturedMessages(examId, studentIdOf(student));
    }

    /** 取出 Mock 收到的本场交卷消息，手工驱动真实消费者批量落库。 */
    private void consumeCapturedMessages(long examId, long studentId) throws Exception {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate, atLeastOnce()).convertAndSend(anyString(), anyString(), captor.capture());
        for (Object raw : new ArrayList<>(captor.getAllValues())) {
            SubmitMessage message = (SubmitMessage) raw;
            if (!message.getExamId().equals(examId) || !message.getStudentId().equals(studentId)) {
                continue;
            }
            Message amqp = new Message(objectMapper.writeValueAsBytes(message), new MessageProperties());
            amqp.getMessageProperties().setDeliveryTag(1L);
            submitConsumer.onBatch(List.of(amqp), mock(com.rabbitmq.client.Channel.class));
        }
    }

    private long studentIdOf(String token) {
        return jwtUtil.parseAccessToken(token).getId();
    }
}
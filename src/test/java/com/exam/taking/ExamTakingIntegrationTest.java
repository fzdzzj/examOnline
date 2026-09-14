package com.exam.taking;

import com.exam.auth.security.LoginUser;
import com.exam.auth.security.SecurityUtil;
import com.exam.auth.service.JwtUtil;
import com.exam.exam.mapper.ExamMapper;
import com.exam.exam.service.ExamStateMachineService;
import com.exam.submission.entity.ExamSubmission;
import com.exam.submission.mapper.ExamBehaviorLogMapper;
import com.exam.submission.mapper.ExamSubmissionMapper;
import com.exam.submission.mapper.ExamSubmitDedupMapper;
import com.exam.submission.dto.SubmitMessage;
import com.exam.submission.mq.ExamSubmitConsumer;
import com.exam.support.IntegrationTestBase;
import com.exam.taking.dto.SubmitRequest;
import com.exam.taking.service.ExamSubmitService;
import com.exam.taking.service.ExamSweepService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 在线考试与交卷集成测试（spec add-exam-taking）：
 * <ul>
 *   <li>进入考试——非进行中禁止进入、个人快照锁定（刷新不换题）、个人倒计时启动；</li>
 *   <li>答题——题目不含答案、自动保存/断线恢复、多端版本冲突、切屏只记录；</li>
 *   <li>交卷——三重幂等、重复交卷返回首次结果、MQ 发送一次、消费者落库与重复投递幂等；</li>
 *   <li>三路竞态——手动/前端归零/后端兜底并发仅提交一次；</li>
 *   <li>超时兜底——后端扫描强制交卷（草稿答案）与已交卷未落库的答案补发对账；</li>
 *   <li>考试列表——待考/进行中/已完成 分组。</li>
 * </ul>
 * RabbitTemplate 以 MockitoBean 替身：断言发送次数与消息内容，消费者用真实 Bean 直接驱动
 * （测试环境监听容器不启动，见 application-test.yml）。
 */
class ExamTakingIntegrationTest extends IntegrationTestBase {

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private ExamStateMachineService stateMachineService;
    @Autowired
    private ExamSubmissionMapper submissionMapper;
    @Autowired
    private ExamSubmitDedupMapper dedupMapper;
    @Autowired
    private ExamBehaviorLogMapper behaviorLogMapper;
    @Autowired
    private ExamSubmitConsumer submitConsumer;
    @Autowired
    private ExamSweepService sweepService;
    @Autowired
    private ExamSubmitService submitService;
    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private ExamMapper examMapper;

    // ==================== 造数 ====================

    private long addPaperQuestion(String token, long paperId, long questionId, double score) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("questionId", questionId);
        body.put("score", score);
        perform(jsonPost("/api/papers/" + paperId + "/questions", token,
                objectMapper.writeValueAsString(body)), 200);
        return questionId;
    }

    /** 组一卷三题（单选 5 + 多选 6 + 判断 4 = 15 分），返回试卷 ID。 */
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

    private long createExam(String token, long paperId, LocalDateTime start,
                            LocalDateTime end, int duration) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("title", unique("考试"));
        body.put("paperId", paperId);
        body.put("startTime", start.toString());
        body.put("endTime", end.toString());
        body.put("durationMinutes", duration);
        JsonNode data = perform(jsonPost("/api/exams", token,
                objectMapper.writeValueAsString(body)), 200).get("data");
        return data.get("id").asLong();
    }

    /** 造一场"进行中"考试：开始时间拨到过去 → 发布 → 手动触发状态机推进。 */
    private long preparedInProgressExam(String teacher) throws Exception {
        long paperId = preparePaper(teacher);
        long examId = createExam(teacher, paperId,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(2), 30);
        perform(jsonPost("/api/exams/" + examId + "/publish", teacher, null), 200);
        stateMachineService.autoAdvance();
        return examId;
    }

    private JsonNode enter(String student, long examId, int expectedStatus) throws Exception {
        return perform(jsonPost("/api/exam-taking/exams/" + examId + "/enter", student, null),
                expectedStatus).get("data");
    }

    private List<Long> questionIds(JsonNode enterData) {
        List<Long> ids = new ArrayList<>();
        enterData.get("questions").forEach(q -> ids.add(q.get("questionId").asLong()));
        return ids;
    }

    private ObjectNode answersJson(long questionId, String answer) {
        ObjectNode answers = objectMapper.createObjectNode();
        answers.put(String.valueOf(questionId), answer);
        return answers;
    }

    // ==================== 进入考试 ====================

    @Test
    void enterGateAndCountdown() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long paperId = preparePaper(teacher);

        // 未开始（已发布但未到 start_time）禁止进入
        long futureExam = createExam(teacher, paperId,
                LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(3), 30);
        perform(jsonPost("/api/exams/" + futureExam + "/publish", teacher, null), 200);
        perform(jsonPost("/api/exam-taking/exams/" + futureExam + "/enter", student, null), 400);

        // 未发布不可见
        long unpublished = createExam(teacher, paperId,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1), 30);
        perform(jsonPost("/api/exam-taking/exams/" + unpublished + "/enter", student, null), 400);

        // 进行中可进入：个人倒计时启动（剩余 <= 个人时长 30 分钟）
        long examId = preparedInProgressExam(teacher);
        JsonNode data = enter(student, examId, 200);
        assertEquals(ExamSubmission.STATUS_IN_PROGRESS, data.get("status").asInt());
        assertEquals(3, data.get("questions").size());
        assertTrue(data.get("remainingSeconds").asLong() > 0
                && data.get("remainingSeconds").asLong() <= 30 * 60);
        // 个人截止不晚于考试结束时间
        assertTrue(data.get("deadlineTime").asText()
                .compareTo(data.get("serverTime").asText()) > 0);

        // 已结束禁止进入（自然结束路径：end_time 拨到过去后状态机推进）
        long endedExam = createExam(teacher, paperId,
                LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1), 30);
        perform(jsonPost("/api/exams/" + endedExam + "/publish", teacher, null), 200);
        stateMachineService.autoAdvance();
        perform(jsonPost("/api/exam-taking/exams/" + endedExam + "/enter", student, null), 400);
    }

    /** spec「刷新不换题」：重复进入返回同一快照的题目与顺序，且不含答案。 */
    @Test
    void reenterReturnsSameSnapshotWithoutAnswers() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long examId = preparedInProgressExam(teacher);

        JsonNode first = enter(student, examId, 200);
        JsonNode second = enter(student, examId, 200);

        assertEquals(first.get("submissionId").asLong(), second.get("submissionId").asLong());
        assertEquals(questionIds(first), questionIds(second), "刷新/重进不得换题换序");
        // 题号 1..n 连续且每题都不带 correctAnswer
        int number = 1;
        for (JsonNode q : first.get("questions")) {
            assertEquals(number++, q.get("number").asInt());
            assertTrue(q.get("correctAnswer") == null || q.get("correctAnswer").isNull(),
                    "学生端题目不得携带答案");
        }
        // 答题数据接口与进入接口一致
        JsonNode paper = perform(jsonGet("/api/exam-taking/exams/" + examId + "/paper", student), 200)
                .get("data");
        assertEquals(questionIds(first), questionIds(paper));
    }

    // ==================== 自动保存与断线恢复 ====================

    @Test
    void draftAutosaveConflictAndRecovery() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long examId = preparedInProgressExam(teacher);
        JsonNode enterData = enter(student, examId, 200);
        long q1 = questionIds(enterData).get(0);
        long q2 = questionIds(enterData).get(1);

        // 30s 自动保存：v1 受理
        ObjectNode save1 = objectMapper.createObjectNode();
        save1.put("version", 1);
        save1.set("answers", answersJson(q1, "B"));
        save1.putArray("marked").add(q2);
        JsonNode ack1 = perform(put0(examId, student, save1), 200).get("data");
        assertTrue(ack1.get("accepted").asBoolean());
        assertEquals(1, ack1.get("version").asInt());

        // v2 受理（最新版本以服务端为准），携带标记题
        ObjectNode save2 = objectMapper.createObjectNode();
        save2.put("version", 2);
        save2.set("answers", answersJson(q1, "C"));
        save2.putArray("marked").add(q2);
        JsonNode ack2 = perform(put0(examId, student, save2), 200).get("data");
        assertTrue(ack2.get("accepted").asBoolean());

        // 旧端 v1 再写：版本冲突被拒，返回服务端当前版本（spec「多端冲突以最新为准」）
        JsonNode stale = perform(put0(examId, student, save1), 200).get("data");
        assertTrue(!stale.get("accepted").asBoolean());
        assertEquals(2, stale.get("version").asInt());

        // 断线恢复：重新拉取答题数据拿到草稿答案 + 标记题 + 版本号
        JsonNode paper = perform(jsonGet("/api/exam-taking/exams/" + examId + "/paper", student), 200)
                .get("data");
        assertEquals("C", paper.get("answers").get(String.valueOf(q1)).asText());
        assertEquals(q2, paper.get("marked").get(0).asLong());
        assertEquals(2, paper.get("draftVersion").asInt());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder put0(
            long examId, String student, ObjectNode body) throws Exception {
        return jsonPut("/api/exam-taking/exams/" + examId + "/draft", student,
                objectMapper.writeValueAsString(body));
    }

    // ==================== 切屏埋点 ====================

    @Test
    void behaviorReportRecordedWithoutForceSubmit() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long examId = preparedInProgressExam(teacher);
        long studentId = jwtUtil.parseAccessToken(student).getId();
        enter(student, examId, 200);

        ObjectNode event = objectMapper.createObjectNode();
        event.put("eventType", "SWITCH_SCREEN");
        event.putObject("eventData").put("awaySeconds", 8);
        perform(jsonPost("/api/exam-taking/exams/" + examId + "/behavior", student,
                objectMapper.writeValueAsString(event)), 200);

        assertEquals(1, behaviorLogMapper.selectCount(Wrappers.<com.exam.submission.entity.ExamBehaviorLog>lambdaQuery()
                .eq(com.exam.submission.entity.ExamBehaviorLog::getExamId, examId)
                .eq(com.exam.submission.entity.ExamBehaviorLog::getStudentId, studentId)
                .eq(com.exam.submission.entity.ExamBehaviorLog::getEventType, "SWITCH_SCREEN")));
        // 只记录不强制交卷
        assertEquals(ExamSubmission.STATUS_IN_PROGRESS,
                submissionMapper.selectByExamStudent(examId, studentId).getStatus());
    }

    // ==================== 交卷幂等 + 消费落库 ====================

    /** 手动交卷 → 状态 CAS 迁移 + MQ 发送一次；重复交卷幂等；消费落库且重复投递不重复写。 */
    @Test
    void submitIdempotentAndConsumerFillsAnswers() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long examId = preparedInProgressExam(teacher);
        long studentId = jwtUtil.parseAccessToken(student).getId();
        JsonNode enterData = enter(student, examId, 200);
        long q1 = questionIds(enterData).get(0);

        ObjectNode request = objectMapper.createObjectNode();
        request.set("answers", answersJson(q1, "A"));
        request.put("submitType", "MANUAL");
        JsonNode ack = perform(jsonPost("/api/exam-taking/exams/" + examId + "/submit", student,
                objectMapper.writeValueAsString(request)), 200).get("data");
        assertEquals(ExamSubmission.STATUS_SUBMITTED, ack.get("status").asInt());
        String firstSubmitTime = ack.get("submitTime").asText();

        // 交卷消息发出一次，答案随消息走，答卷行 answers 仍为 NULL（等待消费者批量落库）
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate).convertAndSend(eq("exam.submit.exchange"), eq("exam.submit"), captor.capture());
        SubmitMessage message = (SubmitMessage) captor.getValue();
        assertEquals(studentId, message.getStudentId());
        assertTrue(message.getAnswers().contains(String.valueOf(q1)));
        ExamSubmission row = submissionMapper.selectByExamStudent(examId, studentId);
        assertEquals(ExamSubmission.STATUS_SUBMITTED, row.getStatus());
        assertEquals(ExamSubmission.SUBMIT_TYPE_MANUAL, row.getSubmitType());
        assertNull(row.getAnswers());
        assertEquals(1, dedupMapper.selectCount(Wrappers.<com.exam.submission.entity.ExamSubmitDedup>lambdaQuery()
                .eq(com.exam.submission.entity.ExamSubmitDedup::getExamId, examId)
                .eq(com.exam.submission.entity.ExamSubmitDedup::getStudentId, studentId)));

        // 重复交卷：幂等返回首次结果（秒级精度比较，DB 回读纳秒被截断），不重复发送
        JsonNode again = perform(jsonPost("/api/exam-taking/exams/" + examId + "/submit", student,
                objectMapper.writeValueAsString(request)), 200).get("data");
        assertEquals(firstSubmitTime.substring(0, 19), again.get("submitTime").asText().substring(0, 19));
        verify(rabbitTemplate, times(1)).convertAndSend(anyString(), anyString(), any(Object.class));

        // 消费者驱动：批量落库成功才 ack；重复投递 0 行幂等跳过
        Message amqp = new Message(objectMapper.writeValueAsBytes(message), new MessageProperties());
        amqp.getMessageProperties().setDeliveryTag(1L);
        com.rabbitmq.client.Channel channel = mock(com.rabbitmq.client.Channel.class);
        submitConsumer.onBatch(List.of(amqp), channel);
        verify(channel).basicAck(eq(1L), eq(false));

        row = submissionMapper.selectByExamStudent(examId, studentId);
        assertEquals(message.getAnswers(), row.getAnswers());
        assertEquals(ExamSubmission.STATUS_SUBMITTED, row.getStatus());

        // 同一消息再次投递：业务仅执行一次（答案不再变化），照常 ack
        submitConsumer.onBatch(List.of(amqp), channel);
        verify(channel, times(2)).basicAck(eq(1L), eq(false));
        assertEquals(message.getAnswers(), submissionMapper.selectByExamStudent(examId, studentId).getAnswers());
    }

    /** spec「三路竞态仅一次」：手动/前端归零/后端兜底并发提交，仅一路 CAS 成功。 */
    @Test
    void threeWayRaceSubmitsOnlyOnce() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long examId = preparedInProgressExam(teacher);
        long studentId = jwtUtil.parseAccessToken(student).getId();
        enter(student, examId, 200);

        LoginUser studentUser = jwtUtil.parseAccessToken(student);
        int threads = 6;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        Callable<Integer> task = () -> {
            SecurityUtil.set(studentUser);
            ready.countDown();
            go.await();
            try {
                // 三路竞态：手动 / 前端归零 / 后端兜底（无登录上下文）混跑
                long idx = Thread.currentThread().getId() % 3;
                if (idx == 0) {
                    SubmitRequest request = new SubmitRequest();
                    request.setAnswers(answersJson(1L, "A"));
                    submitService.submit(examId, request);
                } else if (idx == 1) {
                    SubmitRequest request = new SubmitRequest();
                    request.setSubmitType("COUNTDOWN_ZERO");
                    submitService.submit(examId, request);
                } else {
                    submitService.forceSubmitByBackend(examId, studentId);
                }
                return 200;
            } finally {
                SecurityUtil.clear();
            }
        };

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(task));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            for (Future<Integer> future : futures) {
                assertEquals(200, future.get(15, TimeUnit.SECONDS), "三路竞态全部应成功（幂等返回）");
            }
        } finally {
            pool.shutdownNow();
        }

        // 仅一条答卷、一条防重记录、一次 MQ 发送（只有 CAS 赢家发消息）
        assertEquals(1, submissionMapper.selectCount(Wrappers.<ExamSubmission>lambdaQuery()
                .eq(ExamSubmission::getExamId, examId)
                .eq(ExamSubmission::getStudentId, studentId)));
        assertEquals(1, dedupMapper.selectCount(Wrappers.<com.exam.submission.entity.ExamSubmitDedup>lambdaQuery()
                .eq(com.exam.submission.entity.ExamSubmitDedup::getExamId, examId)
                .eq(com.exam.submission.entity.ExamSubmitDedup::getStudentId, studentId)));
        verify(rabbitTemplate, times(1)).convertAndSend(anyString(), anyString(), any(Object.class));
        ExamSubmission row = submissionMapper.selectByExamStudent(examId, studentId);
        assertEquals(ExamSubmission.STATUS_SUBMITTED, row.getStatus());
    }

    // ==================== 超时兜底与对账补发 ====================

    /** spec「后端兜底」：个人超时未交卷 → 扫描强制交卷，答案取 Redis 草稿。 */
    @Test
    void sweepForceSubmitsOverdueWithDraftAnswers() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long examId = preparedInProgressExam(teacher);
        long studentId = jwtUtil.parseAccessToken(student).getId();
        JsonNode enterData = enter(student, examId, 200);
        long q1 = questionIds(enterData).get(0);

        ObjectNode save = objectMapper.createObjectNode();
        save.put("version", 1);
        save.set("answers", answersJson(q1, "B"));
        perform(put0(examId, student, save), 200);

        // 个人截止拨到过去（模拟超时），触发兜底扫描
        submissionMapper.update(null, Wrappers.<ExamSubmission>lambdaUpdate()
                .eq(ExamSubmission::getId, submissionMapper.selectByExamStudent(examId, studentId).getId())
                .set(ExamSubmission::getDeadlineTime, LocalDateTime.now().minusMinutes(1)));
        sweepService.sweep();

        // 兜底只负责状态迁移 + 发消息（MQ 为 Mock）：驱动真实消费者落库后，答案应取自草稿
        ExamSubmission row = submissionMapper.selectByExamStudent(examId, studentId);
        assertEquals(ExamSubmission.STATUS_SUBMITTED, row.getStatus());
        assertEquals(ExamSubmission.SUBMIT_TYPE_BACKEND, row.getSubmitType());
        consumeCapturedMessages(examId, studentId);
        row = submissionMapper.selectByExamStudent(examId, studentId);
        assertTrue(row.getAnswers().contains(String.valueOf(q1)), "兜底答案应来自草稿");

        // 超时窗口期重进考试：就地兜底收卷并返回已交卷上下文
        // （另造一场考试验证该路径，不污染本场断言）
        long examId2 = preparedInProgressExam(teacher);
        enter(student, examId2, 200);
        submissionMapper.update(null, Wrappers.<ExamSubmission>lambdaUpdate()
                .eq(ExamSubmission::getExamId, examId2)
                .eq(ExamSubmission::getStudentId, studentId)
                .set(ExamSubmission::getDeadlineTime, LocalDateTime.now().minusMinutes(1)));
        JsonNode paper = perform(jsonGet("/api/exam-taking/exams/" + examId2 + "/paper", student), 200)
                .get("data");
        assertEquals(ExamSubmission.STATUS_SUBMITTED, paper.get("status").asInt());
        assertEquals(0, paper.get("questions").size());
        assertEquals(0L, paper.get("remainingSeconds").asLong());
    }

    /** 考试被教师提前结束后，扫描强制交卷剩余进行中答卷（force_end 链路收口）。 */
    @Test
    void sweepForceSubmitsAfterTeacherForceEnd() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long examId = preparedInProgressExam(teacher);
        long studentId = jwtUtil.parseAccessToken(student).getId();
        enter(student, examId, 200);

        perform(jsonPost("/api/exams/" + examId + "/force-end", teacher, null), 200);
        sweepService.sweep();

        assertEquals(ExamSubmission.STATUS_SUBMITTED,
                submissionMapper.selectByExamStudent(examId, studentId).getStatus());
    }

    /** spec「消息可靠落库」自愈：已交卷但答案未落库 → 对账补发重新投递。 */
    @Test
    void sweepRepublishesMissingAnswers() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long examId = preparedInProgressExam(teacher);
        enter(student, examId, 200);

        ObjectNode request = objectMapper.createObjectNode();
        request.set("answers", objectMapper.createObjectNode().put("1", "A"));
        perform(jsonPost("/api/exam-taking/exams/" + examId + "/submit", student,
                objectMapper.writeValueAsString(request)), 200);

        // MQ 消息"丢失"（Mock 未消费，answers 仍为 NULL）→ 扫描补发（跨用例遗留答卷也会被补发，按 examId 过滤）
        org.mockito.Mockito.reset(rabbitTemplate);
        sweepService.sweep();
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate, org.mockito.Mockito.atLeastOnce())
                .convertAndSend(anyString(), anyString(), captor.capture());
        assertTrue(captor.getAllValues().stream()
                        .map(raw -> (SubmitMessage) raw)
                        .anyMatch(m -> m.getExamId().equals(examId)),
                "本答卷的交卷消息应被补发");
    }

    // ==================== 考试列表分组 ====================

    @Test
    void examListGroups() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long paperId = preparePaper(teacher);

        long ongoing = createExam(teacher, paperId,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(2), 30);
        perform(jsonPost("/api/exams/" + ongoing + "/publish", teacher, null), 200);
        long upcoming = createExam(teacher, paperId,
                LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(3), 30);
        perform(jsonPost("/api/exams/" + upcoming + "/publish", teacher, null), 200);
        stateMachineService.autoAdvance();

        // 未进入：进行中的考试归"待考"（可进入）
        JsonNode list = perform(jsonGet("/api/exam-taking/exams", student), 200).get("data");
        assertEquals(groupOf(list, ongoing), "UPCOMING");
        assertTrue(canEnterOf(list, ongoing));

        // 进入后：进行中（可续答，带剩余秒数）
        enter(student, ongoing, 200);
        list = perform(jsonGet("/api/exam-taking/exams", student), 200).get("data");
        assertEquals("ONGOING", groupOf(list, ongoing));
        assertTrue(canEnterOf(list, ongoing));
        assertTrue(remainingOf(list, ongoing) > 0);

        // 交卷后：已完成；未参与且已结束的考试也是已完成
        SubmitRequest request = new SubmitRequest();
        request.setAnswers(objectMapper.createObjectNode());
        // 直接调 Service（绕过 MockMvc），需手工注入登录上下文
        SecurityUtil.set(jwtUtil.parseAccessToken(student));
        try {
            submitService.submit(ongoing, request);
        } finally {
            SecurityUtil.clear();
        }
        long ended = createExam(teacher, paperId,
                LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1), 30);
        perform(jsonPost("/api/exams/" + ended + "/publish", teacher, null), 200);
        stateMachineService.autoAdvance();

        list = perform(jsonGet("/api/exam-taking/exams", student), 200).get("data");
        assertEquals("FINISHED", groupOf(list, ongoing));
        assertEquals("FINISHED", groupOf(list, ended));
        assertEquals("UPCOMING", groupOf(list, upcoming));
    }

    /** 取出 Mock 收到的发送消息中属于 (exam, student) 的部分，驱动真实消费者落库（消费幂等）。 */
    private void consumeCapturedMessages(long examId, long studentId) throws Exception {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate, org.mockito.Mockito.atLeastOnce())
                .convertAndSend(anyString(), anyString(), captor.capture());
        for (Object raw : captor.getAllValues()) {
            SubmitMessage message = (SubmitMessage) raw;
            if (!message.getExamId().equals(examId) || !message.getStudentId().equals(studentId)) {
                continue;   // 其他用例遗留答卷的补发消息与本用例无关
            }
            Message amqp = new Message(objectMapper.writeValueAsBytes(message), new MessageProperties());
            amqp.getMessageProperties().setDeliveryTag(1L);
            submitConsumer.onBatch(List.of(amqp), mock(com.rabbitmq.client.Channel.class));
        }
    }

    private String groupOf(JsonNode list, long examId) {
        for (JsonNode item : list) {
            if (item.get("examId").asLong() == examId) {
                return item.get("group").asText();
            }
        }
        throw new AssertionError("列表缺少考试 " + examId);
    }

    private boolean canEnterOf(JsonNode list, long examId) {
        for (JsonNode item : list) {
            if (item.get("examId").asLong() == examId) {
                return item.get("canEnter").asBoolean();
            }
        }
        throw new AssertionError("列表缺少考试 " + examId);
    }

    private long remainingOf(JsonNode list, long examId) {
        for (JsonNode item : list) {
            if (item.get("examId").asLong() == examId) {
                return item.get("remainingSeconds").asLong();
            }
        }
        throw new AssertionError("列表缺少考试 " + examId);
    }
}

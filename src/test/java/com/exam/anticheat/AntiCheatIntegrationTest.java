package com.exam.anticheat;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.exam.service.ExamStateMachineService;
import com.exam.submission.entity.ExamBehaviorLog;
import com.exam.submission.entity.ExamSubmission;
import com.exam.submission.mapper.ExamBehaviorLogMapper;
import com.exam.submission.mapper.ExamSubmissionMapper;
import com.exam.auth.service.JwtUtil;
import com.exam.support.IntegrationTestBase;
import com.exam.support.RabbitTemplateInvokeStubs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

/**
 * 防作弊集成测试（spec add-anti-cheat 验收，任务 5）：
 * <ul>
 *   <li>事件采集——切屏上报落库+警告响应、阈值升级严重度、未知事件兜底（新增事件不动核心）；</li>
 *   <li>行为日志查询——教师分页/筛选/时间线，STUDENT 403、非归属教师 403；</li>
 *   <li>交卷异常——MQ 发送失败自动采集 SUBMIT_ANOMALY（严重级）；</li>
 *   <li>端到端——切屏→落库→教师时间线查看→监考大屏异常高亮全链路。</li>
 * </ul>
 * RabbitTemplate 以 MockitoBean 替身（测试环境监听容器不启动）。
 */
class AntiCheatIntegrationTest extends IntegrationTestBase {

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
    private ExamBehaviorLogMapper behaviorLogMapper;
    @Autowired
    private ExamSubmissionMapper submissionMapper;
    @Autowired
    private JwtUtil jwtUtil;

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

    /** 组一卷两题（单选 5 + 判断 4 = 9 分）：监考进度断言用整百分比。 */
    private long prepareTwoQuestionPaper(String token) throws Exception {
        long q1 = createQuestion(token, 1, "单选：2+2=?", "B", List.of("A", "B", "C", "D"), null);
        long q2 = createQuestion(token, 3, "判断：SSH 是 22 端口", "T", null, null);
        long paperId = createPaper(token, unique("试卷"), 9);
        addPaperQuestion(token, paperId, q1, 5);
        addPaperQuestion(token, paperId, q2, 4);
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
    private long preparedInProgressExam(String teacher, long paperId) throws Exception {
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

    private long studentIdOf(String studentToken) {
        return jwtUtil.parseAccessToken(studentToken).getId();
    }

    /** 学生上报一次行为事件，返回响应 data 节点。 */
    private JsonNode report(String student, long examId, String eventType) throws Exception {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("eventType", eventType);
        event.putObject("eventData").put("awaySeconds", 5);
        return perform(jsonPost("/api/exam-taking/exams/" + examId + "/behavior", student,
                objectMapper.writeValueAsString(event)), 200).get("data");
    }

    private long behaviorCount(long examId) {
        return behaviorLogMapper.selectCount(Wrappers.<ExamBehaviorLog>lambdaQuery()
                .eq(ExamBehaviorLog::getExamId, examId));
    }

    // ==================== 事件采集与警告 ====================

    @Test
    void switchScreenCollectsWarnsAndNeverForceSubmits() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long examId = preparedInProgressExam(teacher, preparePaper(teacher));
        enter(student, examId, 200);

        JsonNode data = report(student, examId, "SWITCH_SCREEN");

        // 警告响应：第一次切屏即警告，严重度"低"，携带次数与文案
        assertTrue(data.get("warned").asBoolean());
        assertEquals(1, data.get("severity").asInt());
        assertEquals("低", data.get("severityName").asText());
        assertEquals(1, data.get("count").asInt());
        assertTrue(data.get("message").asText().contains("不会强制交卷"));

        // 落库：事件类型/严重度/策略补充明细（count/escalated）
        assertEquals(1, behaviorCount(examId));
        ExamBehaviorLog row = behaviorLogMapper.selectList(Wrappers.<ExamBehaviorLog>lambdaQuery()
                .eq(ExamBehaviorLog::getExamId, examId)).get(0);
        assertEquals("SWITCH_SCREEN", row.getEventType());
        assertEquals(1, row.getSeverity());
        assertTrue(row.getEventData().contains("\"count\":1"));
        assertTrue(row.getEventData().contains("\"escalated\":false"));

        // 只警告不强制交卷（spec「切屏警告不交卷」场景）
        assertEquals(ExamSubmission.STATUS_IN_PROGRESS,
                submissionMapper.selectByExamStudent(examId, studentIdOf(student)).getStatus());
    }

    @Test
    void switchScreenEscalatesSeverityByThreshold() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long examId = preparedInProgressExam(teacher, preparePaper(teacher));
        enter(student, examId, 200);

        // 阈值 3 升中、5 升高：连续上报 6 次，严重度依次 1,1,2,2,3,3
        int[] expected = {1, 1, 2, 2, 3, 3};
        for (int i = 0; i < expected.length; i++) {
            JsonNode data = report(student, examId, "SWITCH_SCREEN");
            assertEquals(expected[i], data.get("severity").asInt(), "第 " + (i + 1) + " 次切屏");
            assertTrue(data.get("warned").asBoolean());
        }

        // 落库口径一致：低 2 条、中 2 条、高 2 条
        assertEquals(6, behaviorCount(examId));
        assertEquals(2, behaviorLogMapper.selectCount(Wrappers.<ExamBehaviorLog>lambdaQuery()
                .eq(ExamBehaviorLog::getExamId, examId).eq(ExamBehaviorLog::getSeverity, 1)));
        assertEquals(2, behaviorLogMapper.selectCount(Wrappers.<ExamBehaviorLog>lambdaQuery()
                .eq(ExamBehaviorLog::getExamId, examId).eq(ExamBehaviorLog::getSeverity, 2)));
        assertEquals(2, behaviorLogMapper.selectCount(Wrappers.<ExamBehaviorLog>lambdaQuery()
                .eq(ExamBehaviorLog::getExamId, examId).eq(ExamBehaviorLog::getSeverity, 3)));
    }

    @Test
    void unknownEventTypeFallsBackToLowestSeverity() throws Exception {
        // 新增事件不动核心：前端先于后端上线新事件类型时，采集不拒绝、按最低级别落库
        String teacher = registerTeacher();
        String student = registerStudent();
        long examId = preparedInProgressExam(teacher, preparePaper(teacher));
        enter(student, examId, 200);

        JsonNode data = report(student, examId, "SOME_FUTURE_EVENT");

        assertTrue(!data.get("warned").asBoolean(), "未知事件不弹警告");
        assertEquals(1, data.get("severity").asInt());
        assertNull(data.get("count"), "兜底策略无次数补充");
        assertEquals(1, behaviorCount(examId));
    }

    // ==================== 行为日志查询与权限 ====================

    @Test
    void teacherQueriesPageFiltersAndTimeline() throws Exception {
        String teacher = registerTeacher();
        String studentA = registerStudent();
        String studentB = registerStudent();
        long examId = preparedInProgressExam(teacher, preparePaper(teacher));
        enter(studentA, examId, 200);
        enter(studentB, examId, 200);

        report(studentA, examId, "SWITCH_SCREEN");           // 低
        report(studentB, examId, "PAGE_REFRESH");            // 中（基础级）
        long aId = studentIdOf(studentA);
        long bId = studentIdOf(studentB);

        // 全量分页：2 条，时间升序（同刻插入按 id 次序）
        JsonNode page1 = perform(jsonGet("/api/exams/" + examId + "/behavior-logs", teacher), 200).get("data");
        assertEquals(2, page1.get("total").asLong());
        assertEquals("SWITCH_SCREEN", page1.get("items").get(0).get("eventType").asText());
        assertEquals("PAGE_REFRESH", page1.get("items").get(1).get("eventType").asText());
        assertEquals("低", page1.get("items").get(0).get("severityName").asText());
        assertEquals("中", page1.get("items").get(1).get("severityName").asText());

        // 按事件类型筛选
        JsonNode byType = perform(jsonGet("/api/exams/" + examId + "/behavior-logs?eventType=SWITCH_SCREEN", teacher),
                200).get("data");
        assertEquals(1, byType.get("total").asLong());
        assertEquals(aId, byType.get("items").get(0).get("studentId").asLong());

        // 按严重度筛选
        JsonNode bySeverity = perform(jsonGet("/api/exams/" + examId + "/behavior-logs?severity=2", teacher),
                200).get("data");
        assertEquals(1, bySeverity.get("total").asLong());
        assertEquals("PAGE_REFRESH", bySeverity.get("items").get(0).get("eventType").asText());

        // 组合筛选：学生 A + 严重度 2 → 无结果
        JsonNode combined = perform(jsonGet(
                "/api/exams/" + examId + "/behavior-logs?studentId=" + aId + "&severity=2", teacher), 200).get("data");
        assertEquals(0, combined.get("total").asLong());

        // 分页：size=1 第二页拿到第二条
        JsonNode pageSlice = perform(jsonGet(
                "/api/exams/" + examId + "/behavior-logs?page=2&size=1", teacher), 200).get("data");
        assertEquals(2, pageSlice.get("total").asLong());
        assertEquals(1, pageSlice.get("items").size());
        assertEquals("PAGE_REFRESH", pageSlice.get("items").get(0).get("eventType").asText());

        // 学生时间线：完整轨迹 + 严重度分布
        JsonNode timeline = perform(jsonGet(
                "/api/exams/" + examId + "/behavior-logs/timeline?studentId=" + bId, teacher), 200).get("data");
        assertEquals(1, timeline.get("total").asLong());
        assertEquals(1, timeline.get("mediumCount").asLong());
        assertEquals(0, timeline.get("lowCount").asLong());
        assertEquals("PAGE_REFRESH", timeline.get("items").get(0).get("eventType").asText());
        assertEquals("中", timeline.get("items").get(0).get("severityName").asText());
    }

    @Test
    void behaviorLogsAndMonitorForbiddenForStudent() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long examId = preparedInProgressExam(teacher, preparePaper(teacher));
        enter(student, examId, 200);

        // STUDENT 无 exam:manage 权限点：行为日志、时间线、监考大屏全部 403（spec「学生不可见」）
        perform(jsonGet("/api/exams/" + examId + "/behavior-logs", student), 403);
        perform(jsonGet("/api/exams/" + examId + "/behavior-logs/timeline?studentId=1", student), 403);
        perform(jsonGet("/api/exams/" + examId + "/monitor/overview", student), 403);
    }

    @Test
    void behaviorLogsForbiddenForOtherTeacher() throws Exception {
        String ownerTeacher = registerTeacher();
        String otherTeacher = registerTeacher();
        long examId = preparedInProgressExam(ownerTeacher, preparePaper(ownerTeacher));

        // 非归属教师（水平越权）：owner 校验 403
        perform(jsonGet("/api/exams/" + examId + "/behavior-logs", otherTeacher), 403);
        perform(jsonGet("/api/exams/" + examId + "/behavior-logs/timeline?studentId=1", otherTeacher), 403);
        perform(jsonGet("/api/exams/" + examId + "/monitor/overview", otherTeacher), 403);

        // 归属教师本人可查（对照）
        perform(jsonGet("/api/exams/" + examId + "/behavior-logs", ownerTeacher), 200);
    }

    // ==================== 交卷异常采集 ====================

    @Test
    void submitAnomalyRecordedWhenMqSendFails() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long examId = preparedInProgressExam(teacher, preparePaper(teacher));
        JsonNode enterData = enter(student, examId, 200);
        long q1 = enterData.get("questions").get(0).get("questionId").asLong();

        // MQ 不可用：convertAndSend 抛异常 → 交卷链路走草稿兜底
        doThrow(new AmqpException("broker down")).when(rabbitTemplate)
                .convertAndSend(anyString(), anyString(), any(Object.class));

        ObjectNode body = objectMapper.createObjectNode();
        body.set("answers", objectMapper.createObjectNode().put(String.valueOf(q1), "A"));
        perform(jsonPost("/api/exam-taking/exams/" + examId + "/submit", student,
                objectMapper.writeValueAsString(body)), 500);

        // 交卷异常事件被服务端自动采集（SUBMIT_ANOMALY，严重级"高"）
        assertEquals(1, behaviorLogMapper.selectCount(Wrappers.<ExamBehaviorLog>lambdaQuery()
                .eq(ExamBehaviorLog::getExamId, examId)
                .eq(ExamBehaviorLog::getEventType, "SUBMIT_ANOMALY")
                .eq(ExamBehaviorLog::getSeverity, 3)));
        // 答卷状态 CAS 已成功（答案走补发自愈），异常只影响落库时效
        assertEquals(ExamSubmission.STATUS_SUBMITTED,
                submissionMapper.selectByExamStudent(examId, studentIdOf(student)).getStatus());
    }

    // ==================== 端到端：切屏→落库→时间线→大屏高亮 ====================

    @Test
    void endToEndProctoringFlow() throws Exception {
        String teacher = registerTeacher();
        String studentA = registerStudent();
        String studentB = registerStudent();
        long examId = preparedInProgressExam(teacher, prepareTwoQuestionPaper(teacher));
        enter(studentA, examId, 200);
        enter(studentB, examId, 200);
        long aId = studentIdOf(studentA);
        long bId = studentIdOf(studentB);

        String overviewUrl = "/api/exams/" + examId + "/monitor/overview";

        // 1) 双学生刚进入：全部在线，无人交卷、无异常
        JsonNode overview = perform(jsonGet(overviewUrl, teacher), 200).get("data");
        assertEquals(2, overview.get("totalStudents").asInt());
        assertEquals(2, overview.get("onlineCount").asInt());
        assertEquals(0, overview.get("offlineCount").asInt());
        assertEquals(0, overview.get("submittedCount").asInt());
        assertEquals(0, overview.get("abnormalCount").asInt());
        assertEquals(2, overview.get("totalQuestions").asInt(), "题目总数取自个人快照");

        // 2) A 连续切屏 3 次（第 3 次升"中"→异常高亮）；B 保存 1 题答案（进度 50%）
        report(studentA, examId, "SWITCH_SCREEN");
        report(studentA, examId, "SWITCH_SCREEN");
        report(studentA, examId, "SWITCH_SCREEN");

        JsonNode enterData = enter(studentB, examId, 200);
        long bQ1 = enterData.get("questions").get(0).get("questionId").asLong();
        ObjectNode save = objectMapper.createObjectNode();
        save.put("version", 1);
        save.set("answers", objectMapper.createObjectNode().put(String.valueOf(bQ1), "B"));
        save.putArray("marked");
        perform(jsonPut("/api/exam-taking/exams/" + examId + "/draft", studentB,
                objectMapper.writeValueAsString(save)), 200);

        overview = perform(jsonGet(overviewUrl, teacher), 200).get("data");
        assertEquals(1, overview.get("abnormalCount").asInt(), "A 达到切屏阈值应被高亮");
        assertEquals(2, overview.get("onlineCount").asInt(), "两个学生均有心跳，全部在线");
        assertEquals(0, overview.get("offlineCount").asInt());

        // 异常学生排前 + 异常字段准确
        JsonNode first = overview.get("students").get(0);
        assertEquals(aId, first.get("studentId").asLong());
        assertTrue(first.get("abnormal").asBoolean());
        assertEquals(1, first.get("abnormalEventCount").asLong(), "仅 severity>=2 的事件计入异常");
        assertEquals(2, first.get("maxSeverity").asInt());
        JsonNode second = overview.get("students").get(1);
        assertEquals(bId, second.get("studentId").asLong());
        assertTrue(!second.get("abnormal").asBoolean());
        assertEquals(50, second.get("progressPercent").asInt(), "B 已答 1/2 题");

        // 3) B 交卷：已交卷 +1、进行中在线只剩 A、B 进度 100%
        ObjectNode submitBody = objectMapper.createObjectNode();
        submitBody.set("answers", objectMapper.createObjectNode()
                .put(String.valueOf(bQ1), "B")
                .put(String.valueOf(enterData.get("questions").get(1).get("questionId").asLong()), "T"));
        perform(jsonPost("/api/exam-taking/exams/" + examId + "/submit", studentB,
                objectMapper.writeValueAsString(submitBody)), 200);

        overview = perform(jsonGet(overviewUrl, teacher), 200).get("data");
        assertEquals(1, overview.get("submittedCount").asInt());
        assertEquals(1, overview.get("onlineCount").asInt());
        assertEquals(0, overview.get("offlineCount").asInt());
        JsonNode bRow = null;
        for (JsonNode s : overview.get("students")) {
            if (s.get("studentId").asLong() == bId) {
                bRow = s;
                break;
            }
        }
        assertEquals("SUBMITTED", bRow.get("status").asText());
        assertEquals(100, bRow.get("progressPercent").asInt());

        // 4) 点击异常学生 → 行为时间线（spec「查看异常详情」场景）
        JsonNode timeline = perform(jsonGet(
                "/api/exams/" + examId + "/behavior-logs/timeline?studentId=" + aId, teacher), 200).get("data");
        assertEquals(3, timeline.get("total").asLong());
        assertEquals(1, timeline.get("mediumCount").asLong());
        assertEquals(2, timeline.get("lowCount").asLong());
        assertEquals("SWITCH_SCREEN", timeline.get("items").get(0).get("eventType").asText());
    }
}

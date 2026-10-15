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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    private long createExam(String token, long paperId, Long classId, LocalDateTime start,
                            LocalDateTime end, int duration) throws Exception {
        return createExam(token, paperId, classId, start, end, duration, null);
    }

    /** 造考试（可带 anti_cheat_config）：配置由 /api/exams 创建接口原样落库（零 DDL，复用既有列）。 */
    private long createExam(String token, long paperId, Long classId, LocalDateTime start,
                            LocalDateTime end, int duration, JsonNode antiCheatConfig) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("title", unique("考试"));
        body.put("paperId", paperId);
        if (classId != null) {
            body.put("classId", classId);
        }
        body.put("startTime", start.toString());
        body.put("endTime", end.toString());
        body.put("durationMinutes", duration);
        if (antiCheatConfig != null) {
            body.set("antiCheatConfig", antiCheatConfig);
        }
        JsonNode data = perform(jsonPost("/api/exams", token,
                objectMapper.writeValueAsString(body)), 200).get("data");
        return data.get("id").asLong();
    }

    /** 造一场"进行中"考试：开始时间拨到过去 → 发布 → 手动触发状态机推进。
     *  绑定测试班级以满足进入考试的班级准入（学生入班由 enter 夹具按需补入）。 */
    private long preparedInProgressExam(String teacher, long paperId) throws Exception {
        return preparedInProgressExam(teacher, paperId, null);
    }

    /** 同上，并附带防作弊配置（如 ipWhitelist / forbidCopy / fullScreen）。 */
    private long preparedInProgressExam(String teacher, long paperId, JsonNode antiCheatConfig)
            throws Exception {
        long classId = createClassForExam(teacher);
        long examId = createExam(teacher, paperId, classId,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(2), 30,
                antiCheatConfig);
        perform(jsonPost("/api/exams/" + examId + "/publish", teacher, null), 200);
        stateMachineService.autoAdvance();
        return examId;
    }

    /** 防作弊配置 JSON 造数（ipWhitelist 与作答安全开关，形态与前端建考表单一致）。 */
    private JsonNode antiCheatConfig(String... ipWhitelist) throws Exception {
        ObjectNode config = objectMapper.createObjectNode();
        if (ipWhitelist.length > 0) {
            var arr = config.putArray("ipWhitelist");
            for (String ip : ipWhitelist) {
                arr.add(ip);
            }
        }
        config.put("forbidCopy", true);
        config.put("fullScreen", true);
        return config;
    }

    private JsonNode enter(String student, long examId, int expectedStatus) throws Exception {
        if (expectedStatus == 200) {
            // 既有用例夹具适配：进入成功路径先确保学生已入班（不改变任何断言语义）
            ensureExamClassMembership(student, examId);
        }
        return perform(jsonPost("/api/exam-taking/exams/" + examId + "/enter", student, null),
                expectedStatus).get("data");
    }

    /**
     * 从指定客户端 IP 进入考试（MockMvc 默认 remoteAddr=127.0.0.1，用 X-Forwarded-For 模拟真实来源）：
     * 返回完整响应体，便于断言 403 文案。
     */
    private JsonNode enterFromIp(String student, long examId, String clientIp, int expectedStatus)
            throws Exception {
        if (expectedStatus == 200) {
            ensureExamClassMembership(student, examId);
        }
        MockHttpServletRequestBuilder builder =
                jsonPost("/api/exam-taking/exams/" + examId + "/enter", student, null);
        if (clientIp != null) {
            builder = builder.header("X-Forwarded-For", clientIp);
        }
        return perform(builder, expectedStatus);
    }

    /**
     * 断言"网段外来源被拒"且拒绝原因<b>确为 IP 白名单层</b>：
     * 先补齐班级归属（否则 403 可能来自班级准入层，测不出 IP 拦截），再比对拒绝文案与"未创建答卷"。
     * 变异验证：抽掉 assertIpAllowed 里的 403，本方法必须变红。
     */
    private void assertIpDenied(String student, long examId, String clientIp) throws Exception {
        ensureExamClassMembership(student, examId);
        JsonNode body = enterFromIp(student, examId, clientIp, 403);
        String message = body.get("message").asText();
        assertTrue(message.contains("不在本次考试允许的网段范围内"),
                "403 须来自 IP 白名单拦截而非其他准入层，实际=" + message);
        assertNull(submissionMapper.selectByExamStudent(examId, studentIdOf(student)),
                "IP 拦截不得创建答卷");
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

    // ==================== IP 白名单准入（add-anti-cheat-restrictions） ====================

    @Test
    void ipWhitelistBlocksForeignIpWithExplicitReasonAndCreatesNoSubmission() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long examId = preparedInProgressExam(teacher, preparePaper(teacher),
                antiCheatConfig("192.168.1.*"));
        ensureExamClassMembership(student, examId);

        // 白名单外 IP：403 拒绝，文案明确给出当前 IP 与网段范围
        JsonNode blocked = enterFromIp(student, examId, "10.20.30.40", 403);
        assertTrue(blocked.get("message").asText().contains("不在本次考试允许的网段范围内"),
                "403 文案须明确原因，实际=" + blocked.get("message").asText());
        assertTrue(blocked.get("message").asText().contains("10.20.30.40"),
                "403 文案须回显当前 IP");
        assertEquals(403, blocked.get("code").asInt());
        // 拦截发生在创建答卷之前：不得留下半进入状态的答卷行
        assertNull(submissionMapper.selectByExamStudent(examId, studentIdOf(student)),
                "IP 拦截不得创建答卷");

        // 白名单内 IP：通配符命中，正常进入并回读答卷
        JsonNode allowed = enterFromIp(student, examId, "192.168.1.77", 200).get("data");
        assertEquals(examId, allowed.get("examId").asLong());
        assertNotNull(submissionMapper.selectByExamStudent(examId, studentIdOf(student)));

        // 已进入后换网络（异地/断线重连）不再拦截：准入只在首次进入判定，
        // 避免把已在考的答卷人因网络抖动挡在门外（个人答卷即准入凭据）
        JsonNode reenter = enterFromIp(student, examId, "10.20.30.40", 200).get("data");
        assertEquals(allowed.get("submissionId").asLong(), reenter.get("submissionId").asLong(),
                "重进返回同一答卷，不换题");
    }

    @Test
    void ipWhitelistSupportsCidrAndSingleIpAndLoopback() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long examId = preparedInProgressExam(teacher, preparePaper(teacher),
                antiCheatConfig("10.10.0.0/16", "203.0.113.7", "0:0:0:0:0:0:0:1"));

        // CIDR 内 / 单 IP / IPv6 回环三种形态均可命中（每次换学生避免答卷复用干扰）
        enterFromIp(student, examId, "10.10.200.8", 200);
        String student2 = registerStudent();
        enterFromIp(student2, examId, "203.0.113.7", 200);
        String student3 = registerStudent();
        enterFromIp(student3, examId, "::1", 200);

        // CIDR 外的相邻地址仍被拒（/16 边界：10.11 段不在名单内）——拒绝原因须为 IP 白名单层
        String student4 = registerStudent();
        assertIpDenied(student4, examId, "10.11.0.1");
        // 未配置 XFF 时回落 remoteAddr=127.0.0.1，同样不在名单内
        String student5 = registerStudent();
        assertIpDenied(student5, examId, null);
    }

    @Test
    void noIpWhitelistAllowsAnyClientIp() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        // 仅开启作答安全开关、未配 ipWhitelist：IP 不作限制（全网放行）
        long examId = preparedInProgressExam(teacher, preparePaper(teacher),
                antiCheatConfig());
        enterFromIp(student, examId, "198.51.100.23", 200);
    }

    // ==================== 防作弊配置契约下发 ====================

    @Test
    void enterResponseCarriesAntiCheatConfigWithoutDroppingExistingFields() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long examId = preparedInProgressExam(teacher, preparePaper(teacher),
                antiCheatConfig("192.168.1.*"));

        JsonNode data = enterFromIp(student, examId, "192.168.1.60", 200).get("data");

        // 契约只增不减：新增 antiCheatConfig 下发，既有字段一个不少
        JsonNode config = data.get("antiCheatConfig");
        assertNotNull(config, "EnterExamResponse 须下发 antiCheatConfig");
        assertEquals("192.168.1.*", config.get("ipWhitelist").get(0).asText());
        assertTrue(config.get("forbidCopy").asBoolean());
        assertTrue(config.get("fullScreen").asBoolean());
        assertNotNull(data.get("examId"));
        assertNotNull(data.get("examTitle"));
        assertNotNull(data.get("submissionId"));
        assertNotNull(data.get("status"));
        assertNotNull(data.get("startTime"));
        assertNotNull(data.get("deadlineTime"));
        assertNotNull(data.get("serverTime"));
        assertNotNull(data.get("remainingSeconds"));
        assertNotNull(data.get("questions"));
        assertNotNull(data.get("marked"));

        // 断线恢复入口（GET /paper）同样下发（同一组装路径）
        JsonNode paper = perform(jsonGet("/api/exam-taking/exams/" + examId + "/paper", student), 200)
                .get("data");
        assertEquals("192.168.1.*", paper.get("antiCheatConfig").get("ipWhitelist").get(0).asText());

        // 未配防作弊的考试：字段存在但为空，且不影响既有进入语义
        long plainExam = preparedInProgressExam(teacher, preparePaper(teacher));
        String student2 = registerStudent();
        JsonNode plain = enter(student2, plainExam, 200);
        assertTrue(plain.get("antiCheatConfig") == null || plain.get("antiCheatConfig").isNull(),
                "未配置防作弊时不下发规则，前端按未开启处理");
    }

    // ==================== 作答安全模式事件（复制粘贴 / 全屏退出） ====================

    @Test
    void copyPasteAndFullScreenExitCollectedAsMediumAndNeverForceSubmit() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long examId = preparedInProgressExam(teacher, preparePaper(teacher),
                antiCheatConfig("192.168.1.*"));
        enterFromIp(student, examId, "192.168.1.50", 200);

        // 复制/粘贴违规：默认"中"级 + 前端提示，事件照常落库
        JsonNode copy = report(student, examId, "COPY_PASTE");
        assertTrue(copy.get("warned").asBoolean(), "复制粘贴违规须提示");
        assertEquals(2, copy.get("severity").asInt());
        assertEquals("中", copy.get("severityName").asText());

        JsonNode fullscreen = report(student, examId, "FULLSCREEN_EXIT");
        assertTrue(fullscreen.get("warned").asBoolean(), "退出全屏须提示");
        assertEquals(2, fullscreen.get("severity").asInt());

        // 落库口径：两条事件分别落 exam_behavior_logs，严重度均为 2
        assertEquals(1, behaviorLogMapper.selectCount(Wrappers.<ExamBehaviorLog>lambdaQuery()
                .eq(ExamBehaviorLog::getExamId, examId)
                .eq(ExamBehaviorLog::getEventType, "COPY_PASTE")
                .eq(ExamBehaviorLog::getSeverity, 2)));
        assertEquals(1, behaviorLogMapper.selectCount(Wrappers.<ExamBehaviorLog>lambdaQuery()
                .eq(ExamBehaviorLog::getExamId, examId)
                .eq(ExamBehaviorLog::getEventType, "FULLSCREEN_EXIT")
                .eq(ExamBehaviorLog::getSeverity, 2)));

        // 绝不自动强制交卷：行为违规只记录，答卷仍在进行中（决策记录 §3.1）
        assertEquals(ExamSubmission.STATUS_IN_PROGRESS,
                submissionMapper.selectByExamStudent(examId, studentIdOf(student)).getStatus());
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

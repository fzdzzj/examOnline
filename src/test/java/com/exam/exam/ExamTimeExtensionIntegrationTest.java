package com.exam.exam;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.audit.entity.AuditLog;
import com.exam.audit.mapper.AuditLogMapper;
import com.exam.auth.service.JwtUtil;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.exam.service.ExamStateMachineService;
import com.exam.message.entity.UserMessage;
import com.exam.message.mapper.UserMessageMapper;
import com.exam.submission.entity.ExamSubmission;
import com.exam.submission.mapper.ExamSubmissionMapper;
import com.exam.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 考中应急顺延与个性化补时全链路集成测试（add-exam-time-extension）。
 *
 * <p>打真实 HTTP 端点而不是直调服务：要证的正是"全场顺延/个人补时双模式真的改了主表与
 * 在途答卷 deadline、状态门控/归属权守卫/参数校验真的前置拦截、审计与站内通知真的落库"。
 * H2 数据跨用例保留，断言围绕各用例自己的考试与考生精确定位，不数全表。
 */
class ExamTimeExtensionIntegrationTest extends IntegrationTestBase {

    private static final String EXTEND_URL_PREFIX = "/api/exams/";

    @Autowired
    private ExamStateMachineService stateMachineService;

    @Autowired
    private ExamMapper examMapper;

    @Autowired
    private ExamSubmissionMapper submissionMapper;

    @Autowired
    private AuditLogMapper auditLogMapper;

    @Autowired
    private UserMessageMapper userMessageMapper;

    @Autowired
    private JwtUtil jwtUtil;

    // ==================== 造数 ====================

    private long addPaperQuestion(String token, long paperId, long questionId, double score)
            throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("questionId", questionId);
        body.put("score", score);
        perform(jsonPost("/api/papers/" + paperId + "/questions", token,
                objectMapper.writeValueAsString(body)), 200);
        return questionId;
    }

    /** 组一卷两题（6+4=10）：发布要求卷面总分一致，空卷会被快照生成拒绝。 */
    private long preparePaperWithTwoQuestions(String token) throws Exception {
        long q1 = createQuestion(token, 1, "单选题干：1+1=?", "A", List.of("A", "B", "C", "D"), null);
        long q2 = createQuestion(token, 4, "简答题干：描述进程与线程区别", "进程有独立地址空间", null, null);
        long paperId = createPaper(token, unique("试卷"), 10);
        addPaperQuestion(token, paperId, q1, 6);
        addPaperQuestion(token, paperId, q2, 4);
        return paperId;
    }

    /** 造一场"进行中"考试（30 分钟个人时长，时间窗 2 小时）：拨开始时间→发布→手动推进状态机。 */
    private long preparedInProgressExam(String teacher) throws Exception {
        long paperId = preparePaperWithTwoQuestions(teacher);
        long classId = createClassForExam(teacher);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("title", unique("顺延考试"));
        body.put("paperId", paperId);
        body.put("classId", classId);
        body.put("startTime", LocalDateTime.now().minusMinutes(1).toString());
        body.put("endTime", LocalDateTime.now().plusHours(2).toString());
        body.put("durationMinutes", 30);
        long examId = perform(jsonPost("/api/exams", teacher,
                objectMapper.writeValueAsString(body)), 200).get("data").get("id").asLong();
        perform(jsonPost("/api/exams/" + examId + "/publish", teacher, null), 200);
        stateMachineService.autoAdvance();
        return examId;
    }

    /** 学生进入考试创建在途答卷（返回答卷 ID 供断言定位）。 */
    private long enterExam(String student, long examId) throws Exception {
        ensureExamClassMembership(student, examId);
        perform(jsonPost("/api/exam-taking/exams/" + examId + "/enter", student, null), 200);
        return submissionMapper.selectByExamStudent(examId, studentId(student)).getId();
    }

    private long studentId(String token) {
        return jwtUtil.parseAccessToken(token).getId();
    }

    private Exam examRow(long examId) {
        return examMapper.selectById(examId);
    }

    /** 全场顺延请求（不指定 studentIds = 全场模式）。 */
    private String globalBody(int minutes, String reason) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("extensionMinutes", minutes);
        body.put("reason", reason);
        return objectMapper.writeValueAsString(body);
    }

    /** 个人补时请求（指定 studentIds）。 */
    private String individualBody(int minutes, String reason, List<Long> studentIds) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("extensionMinutes", minutes);
        body.put("reason", reason);
        var ids = body.putArray("studentIds");
        studentIds.forEach(ids::add);
        return objectMapper.writeValueAsString(body);
    }

    private List<AuditLog> extendAuditRows(long examId) {
        return auditLogMapper.selectList(Wrappers.<AuditLog>lambdaQuery()
                .eq(AuditLog::getAction, "EXAM_EXTEND_TIME")
                .like(AuditLog::getDetails, "examId=" + examId));
    }

    private List<UserMessage> messagesFor(long examId, long userId) {
        return userMessageMapper.selectList(Wrappers.<UserMessage>lambdaQuery()
                .eq(UserMessage::getExamId, examId)
                .eq(UserMessage::getUserId, userId));
    }

    // ==================== 全场顺延 ====================

    @Test
    void globalExtensionShiftsExamWindowAndAllInflightDeadlines() throws Exception {
        String teacher = registerTeacher();
        String studentA = registerStudent();
        String studentB = registerStudent();
        long examId = preparedInProgressExam(teacher);
        long submissionA = enterExam(studentA, examId);
        long submissionB = enterExam(studentB, examId);

        LocalDateTime oldEnd = examRow(examId).getEndTime();
        int oldDuration = examRow(examId).getDurationMinutes();
        LocalDateTime deadlineA = submissionMapper.selectById(submissionA).getDeadlineTime();
        LocalDateTime deadlineB = submissionMapper.selectById(submissionB).getDeadlineTime();

        JsonNode data = perform(jsonPost(EXTEND_URL_PREFIX + examId + "/extend-time", teacher,
                globalBody(30, "机房突发断电应急顺延")), 200).get("data");

        assertEquals(examId, data.get("examId").asLong());
        assertEquals(30, data.get("extensionMinutes").asInt());
        assertTrue(data.get("isGlobal").asBoolean(), "未指定 studentIds 应判为全场模式");
        assertEquals(2, data.get("affectedSubmissionsCount").asInt(), "两名在途考生都应被顺延");
        assertEquals(oldEnd.plusMinutes(30), LocalDateTime.parse(data.get("newEndTime").asText()));
        assertEquals(oldDuration + 30, data.get("newDurationMinutes").asInt());

        Exam after = examRow(examId);
        assertEquals(oldEnd.plusMinutes(30), after.getEndTime(), "主表 end_time 应顺延 30 分钟");
        assertEquals(oldDuration + 30, after.getDurationMinutes(), "主表 duration_minutes 应加 30");
        assertEquals(deadlineA.plusMinutes(30),
                submissionMapper.selectById(submissionA).getDeadlineTime(), "在途答卷 A 截止应顺延");
        assertEquals(deadlineB.plusMinutes(30),
                submissionMapper.selectById(submissionB).getDeadlineTime(), "在途答卷 B 截止应顺延");

        // 审计（m3 击红面）：操作人/IP/模式/时长/原因全带
        List<AuditLog> audits = extendAuditRows(examId);
        assertEquals(1, audits.size(), "全场顺延应恰好落一条 EXAM_EXTEND_TIME 审计");
        AuditLog audit = audits.get(0);
        assertEquals(studentId(teacher), audit.getUserId(), "操作人应记教师本人");
        assertEquals("机房突发断电应急顺延", reasonOf(audit));
        assertTrue(audit.getDetails().contains("mode=GLOBAL"), "details 应标明全场模式");
        assertTrue(audit.getDetails().contains("minutes=30"));
        assertTrue(audit.getDetails().contains("affectedSubmissions=2"));

        // 旁路通知：两名在途考生各一条延时通知
        assertEquals(1, messagesFor(examId, studentId(studentA)).size(), "考生 A 应收到延时通知");
        assertEquals(1, messagesFor(examId, studentId(studentB)).size(), "考生 B 应收到延时通知");
    }

    // ==================== 个人补时 ====================

    @Test
    void individualCompensationTouchesOnlySpecifiedInflightStudents() throws Exception {
        String teacher = registerTeacher();
        String studentA = registerStudent();
        String studentB = registerStudent();
        long examId = preparedInProgressExam(teacher);
        long submissionA = enterExam(studentA, examId);
        long submissionB = enterExam(studentB, examId);

        LocalDateTime oldEnd = examRow(examId).getEndTime();
        int oldDuration = examRow(examId).getDurationMinutes();
        LocalDateTime deadlineA = submissionMapper.selectById(submissionA).getDeadlineTime();
        LocalDateTime deadlineB = submissionMapper.selectById(submissionB).getDeadlineTime();

        JsonNode data = perform(jsonPost(EXTEND_URL_PREFIX + examId + "/extend-time", teacher,
                individualBody(20, "考生设备死机重启补时", List.of(studentId(studentA)))), 200)
                .get("data");

        assertFalse(data.get("isGlobal").asBoolean(), "指定 studentIds 应判为个人模式");
        assertEquals(1, data.get("affectedSubmissionsCount").asInt(), "仅指定考生 1 人受影响");
        assertEquals(oldEnd, LocalDateTime.parse(data.get("newEndTime").asText()),
                "个人模式主表未动，newEndTime 应回显原值");
        assertEquals(oldDuration, data.get("newDurationMinutes").asInt());

        Exam after = examRow(examId);
        assertEquals(oldEnd, after.getEndTime(), "个人补时不得改主表 end_time");
        assertEquals(oldDuration, after.getDurationMinutes(), "个人补时不得改主表 duration_minutes");
        assertEquals(deadlineA.plusMinutes(20),
                submissionMapper.selectById(submissionA).getDeadlineTime(), "指定考生截止应补时 20 分钟");
        assertEquals(deadlineB,
                submissionMapper.selectById(submissionB).getDeadlineTime(), "未指定考生截止不得变动");

        List<AuditLog> audits = extendAuditRows(examId);
        assertEquals(1, audits.size());
        AuditLog audit = audits.get(0);
        assertTrue(audit.getDetails().contains("mode=INDIVIDUAL"), "details 应标明个人模式");
        assertTrue(audit.getDetails().contains("studentIds=[" + studentId(studentA) + "]"),
                "details 应带受影响学生名单，实际=" + audit.getDetails());
        assertEquals("考生设备死机重启补时", reasonOf(audit));

        assertEquals(1, messagesFor(examId, studentId(studentA)).size(), "被补时考生应收到通知");
        assertEquals(0, messagesFor(examId, studentId(studentB)).size(), "未指定考生不应收到通知");
    }

    // ==================== 非进行中拦截 ====================

    @Test
    void rejectsNonInProgressExamWithoutAnyMutation() throws Exception {
        String teacher = registerTeacher();
        long paperId = preparePaperWithTwoQuestions(teacher);

        // 未开始（已发布但未到 start_time）
        ObjectNode body = objectMapper.createObjectNode();
        body.put("title", unique("未开始考试"));
        body.put("paperId", paperId);
        body.put("startTime", LocalDateTime.now().plusHours(1).toString());
        body.put("endTime", LocalDateTime.now().plusHours(3).toString());
        body.put("durationMinutes", 30);
        long notStarted = perform(jsonPost("/api/exams", teacher,
                objectMapper.writeValueAsString(body)), 200).get("data").get("id").asLong();
        perform(jsonPost("/api/exams/" + notStarted + "/publish", teacher, null), 200);

        LocalDateTime endBefore = examRow(notStarted).getEndTime();
        perform(jsonPost(EXTEND_URL_PREFIX + notStarted + "/extend-time", teacher,
                globalBody(15, "误操作")), 400);

        assertEquals(endBefore, examRow(notStarted).getEndTime(), "被拒请求不得改主表");
        assertEquals(0, extendAuditRows(notStarted).size(), "被拒请求不得落成功审计");

        // 已结束（自然结束路径：end_time 拨到过去后状态机推进）
        long ended = preparedInProgressExam(teacher);
        examMapper.update(null, Wrappers.<Exam>lambdaUpdate()
                .eq(Exam::getId, ended)
                .set(Exam::getEndTime, LocalDateTime.now().minusMinutes(1)));
        stateMachineService.autoAdvance();
        assertEquals(Exam.STATUS_ENDED, examRow(ended).getStatus());

        perform(jsonPost(EXTEND_URL_PREFIX + ended + "/extend-time", teacher,
                globalBody(15, "误操作")), 400);
        assertEquals(0, extendAuditRows(ended).size());
    }

    // ==================== 水平越权拦截 ====================

    @Test
    void forbidsNonOwnerTeacherWithoutAnyMutation() throws Exception {
        String owner = registerTeacher();
        String outsider = registerTeacher();
        long examId = preparedInProgressExam(owner);
        String student = registerStudent();
        long submissionId = enterExam(student, examId);

        LocalDateTime endBefore = examRow(examId).getEndTime();
        LocalDateTime deadlineBefore = submissionMapper.selectById(submissionId).getDeadlineTime();

        perform(jsonPost(EXTEND_URL_PREFIX + examId + "/extend-time", outsider,
                globalBody(30, "越权顺延他人考试")), 403);

        assertEquals(endBefore, examRow(examId).getEndTime(), "越权请求不得改主表");
        assertEquals(deadlineBefore,
                submissionMapper.selectById(submissionId).getDeadlineTime(), "越权请求不得改答卷");
        assertEquals(0, extendAuditRows(examId).size(), "越权请求不得落审计");
    }

    // ==================== 参数边界 ====================

    @Test
    void validatesExtensionMinutesBounds() throws Exception {
        String teacher = registerTeacher();
        long examId = preparedInProgressExam(teacher);
        enterExam(registerStudent(), examId);

        // 下界外：0 分钟
        perform(jsonPost(EXTEND_URL_PREFIX + examId + "/extend-time", teacher,
                globalBody(0, "零分钟")), 400);
        // 上界外：241 分钟
        perform(jsonPost(EXTEND_URL_PREFIX + examId + "/extend-time", teacher,
                globalBody(241, "超上限")), 400);
        // 缺 extensionMinutes
        ObjectNode missing = objectMapper.createObjectNode();
        perform(jsonPost(EXTEND_URL_PREFIX + examId + "/extend-time", teacher,
                objectMapper.writeValueAsString(missing)), 400);
        // reason 超长（256 字符 > @Size(max=255)）
        ObjectNode longReason = objectMapper.createObjectNode();
        longReason.put("extensionMinutes", 5);
        longReason.put("reason", "长".repeat(256));
        perform(jsonPost(EXTEND_URL_PREFIX + examId + "/extend-time", teacher,
                objectMapper.writeValueAsString(longReason)), 400);

        // 上下边界内合法：1 分钟与 240 分钟均应成功
        perform(jsonPost(EXTEND_URL_PREFIX + examId + "/extend-time", teacher,
                globalBody(1, "边界下界")), 200);
        perform(jsonPost(EXTEND_URL_PREFIX + examId + "/extend-time", teacher,
                globalBody(240, "边界上界")), 200);
        assertEquals(2, extendAuditRows(examId).size(), "两次合法顺延各落一条审计");
    }

    /** 从审计 details 中抽取 reason= 之后的原文（reason 可能含逗号，取末段整行）。 */
    private String reasonOf(AuditLog audit) {
        String details = audit.getDetails();
        assertNotNull(details);
        int idx = details.indexOf("reason=");
        assertTrue(idx >= 0, "details 应带 reason 字段，实际=" + details);
        return details.substring(idx + "reason=".length());
    }
}

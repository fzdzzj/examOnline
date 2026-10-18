package com.exam.audit;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.audit.entity.AuditLog;
import com.exam.audit.mapper.AuditLogMapper;
import com.exam.auth.service.JwtUtil;
import com.exam.auth.security.LoginUser;
import com.exam.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 考务核心操作审计全链路验证（add-exam-operation-audit-logs）。
 *
 * <p>打真实 HTTP 端点而不是直调服务：要证的正是"发布/删除/踢人的完整链路真的把审计写进了
 * audit_log"，包括只有走完整请求才会浮现的环节——操作人身份（SecurityUtil）、客户端 IP
 * （RequestContextHolder + ClientIpResolver）与 traceId（MDC）。单元侧契约见
 * AuditLogServiceExamOperationTest；成绩发布/撤回接线见 ScoreServiceTest。
 *
 * <p>H2 数据跨用例保留，断言围绕各用例自己的唯一标题/目标 ID 精确定位，不数全表。
 */
class ExamOperationAuditIntegrationTest extends IntegrationTestBase {

    /** MockMvc 直连请求经 ClientIpResolver 解析后的预期 IP（X-Real-IP 覆写头场景）。 */
    private static final String CLIENT_IP = "203.0.113.77";

    @Autowired
    private AuditLogMapper auditLogMapper;

    @Autowired
    private JwtUtil jwtUtil;

    // ==================== 造数 ====================

    /** 组一卷两题（6+4=10）：发布要求卷面总分一致，空卷会被快照生成拒绝。 */
    private long preparePaperWithTwoQuestions(String token) throws Exception {
        long q1 = createQuestion(token, 1, "单选题干：1+1=?", "A", List.of("A", "B", "C", "D"), null);
        long q2 = createQuestion(token, 4, "简答题干：描述进程与线程区别", "进程有独立地址空间", null, null);
        long paperId = createPaper(token, unique("试卷"), 10);
        addPaperQuestion(token, paperId, q1, 6);
        addPaperQuestion(token, paperId, q2, 4);
        return paperId;
    }

    private long addPaperQuestion(String token, long paperId, long questionId, double score)
            throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("questionId", questionId);
        body.put("score", score);
        perform(jsonPost("/api/papers/" + paperId + "/questions", token,
                objectMapper.writeValueAsString(body)), 200);
        return questionId;
    }

    /** 建考试并返回 (examId, title)。 */
    private JsonExam createExam(String teacherToken, long paperId) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        String title = unique("审计考试");
        body.put("title", title);
        body.put("paperId", paperId);
        body.put("startTime", LocalDateTime.now().plusDays(1).toString());
        body.put("endTime", LocalDateTime.now().plusDays(2).toString());
        body.put("durationMinutes", 60);
        long examId = perform(jsonPost("/api/exams", teacherToken,
                objectMapper.writeValueAsString(body)), 200).get("data").get("id").asLong();
        return new JsonExam(examId, title);
    }

    private record JsonExam(long id, String title) {
    }

    private List<AuditLog> rowsByDetails(String action, String detailFragment) {
        return auditLogMapper.selectList(Wrappers.<AuditLog>lambdaQuery()
                .eq(AuditLog::getAction, action)
                .like(AuditLog::getDetails, detailFragment));
    }

    // ==================== 考试发布 / 删除 ====================

    /**
     * 发布成功必须落一条 EXAM_PUBLISH，操作人、X-Real-IP、traceId 全带。
     * 这是 m1（移除发布审计打点）的击红面。
     */
    @Test
    void examPublishPersistsOperationAudit() throws Exception {
        String teacher = registerTeacher();
        LoginUser teacherIdentity = jwtUtil.parseAccessToken(teacher);
        long paperId = preparePaperWithTwoQuestions(teacher);
        JsonExam exam = createExam(teacher, paperId);

        perform(jsonPost("/api/exams/" + exam.id() + "/publish", teacher, null)
                        .header("X-Real-IP", CLIENT_IP), 200);

        List<AuditLog> rows = rowsByDetails(AuditLog.ACTION_EXAM_PUBLISH, exam.title());
        assertEquals(1, rows.size(), "发布考试应恰好落一条 EXAM_PUBLISH 审计");
        AuditLog row = rows.get(0);
        assertEquals(AuditLog.STATUS_SUCCESS, row.getStatus());
        assertEquals(teacherIdentity.getId(), row.getUserId(), "操作人应记教师本人");
        assertEquals(teacherIdentity.getUsername(), row.getUsername());
        assertEquals(CLIENT_IP, row.getIpAddress(), "IP 应取 X-Real-IP 覆写头而非 MockMvc 直连地址");
        assertNotNull(row.getTraceId(), "审计行应带 trace_id 才能与链路对齐");
        assertTrue(row.getDetails().contains("examId=" + exam.id()),
                "details 应带关联考试 ID，实际=" + row.getDetails());
    }

    /** 删除成功必须落一条 EXAM_DELETE（m1 同族接线面：删除路径）。 */
    @Test
    void examDeletePersistsOperationAudit() throws Exception {
        String teacher = registerTeacher();
        LoginUser teacherIdentity = jwtUtil.parseAccessToken(teacher);
        long paperId = createPaper(teacher, unique("试卷"), 10);
        JsonExam exam = createExam(teacher, paperId);

        perform(jsonDelete("/api/exams/" + exam.id(), teacher)
                        .header("X-Real-IP", CLIENT_IP), 200);

        List<AuditLog> rows = rowsByDetails(AuditLog.ACTION_EXAM_DELETE, exam.title());
        assertEquals(1, rows.size(), "删除考试应恰好落一条 EXAM_DELETE 审计");
        AuditLog row = rows.get(0);
        assertEquals(teacherIdentity.getId(), row.getUserId());
        assertEquals(CLIENT_IP, row.getIpAddress());
        assertTrue(row.getDetails().contains("examId=" + exam.id()));
    }

    // ==================== 管理员强制下线 ====================

    /**
     * 踢人成功必须落一条 USER_KICK，操作人是管理员、details 带被踢用户 ID。
     * 这是 m3（移除踢人审计打点）的击红面。
     */
    @Test
    void userKickPersistsOperationAudit() throws Exception {
        String student = registerStudent();
        long targetUserId = jwtUtil.parseAccessToken(student).getId();
        String admin = loginToken("admin", "admin123");
        LoginUser adminIdentity = jwtUtil.parseAccessToken(admin);

        perform(jsonPost("/api/admin/users/" + targetUserId + "/kick", admin, null)
                        .header("X-Real-IP", CLIENT_IP), 200);

        List<AuditLog> rows = rowsByDetails(AuditLog.ACTION_USER_KICK, "targetUserId=" + targetUserId);
        assertEquals(1, rows.size(), "踢人应恰好落一条 USER_KICK 审计");
        AuditLog row = rows.get(0);
        assertEquals(adminIdentity.getId(), row.getUserId(), "操作人应记管理员，而非被踢用户");
        assertEquals(adminIdentity.getUsername(), row.getUsername());
        assertEquals(CLIENT_IP, row.getIpAddress());
        assertEquals(AuditLog.STATUS_SUCCESS, row.getStatus());
    }
}

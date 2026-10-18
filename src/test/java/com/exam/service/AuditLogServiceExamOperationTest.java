package com.exam.service;

import com.exam.audit.entity.AuditLog;
import com.exam.audit.mapper.AuditLogMapper;
import com.exam.auth.security.LoginUser;
import com.exam.auth.security.SecurityUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * 考务核心操作审计的单元契约（add-exam-operation-audit-logs）：
 * 五个专用方法各自落对的 action，操作人取 SecurityUtil、IP 走 RequestContextHolder 现场
 * 解析（复用 ClientIpResolver，X-Real-IP 优先），details 必须带关联业务 ID 与标题/原因——
 * 审计记录缺了这几样，合规追溯（谁、在哪、对哪条业务做了什么）就断链。
 *
 * <p>旁路容错单独一例：审计写入抛任何异常都不得外溢——审计是旁路，绝不把一次正常的
 * 考务写操作变成 500。真实链路的落库验证见 ExamOperationAuditIntegrationTest。
 */
@ExtendWith(MockitoExtension.class)
class AuditLogServiceExamOperationTest {

    private static final Long OPERATOR_ID = 7L;
    private static final String OPERATOR_NAME = "tea_audit";
    private static final String CLIENT_IP = "203.0.113.77";

    @Mock
    private AuditLogMapper auditLogMapper;

    private AuditLogService auditLogService;

    @BeforeEach
    void setUp() {
        // 不能用字段初始化器：Mockito 在 beforeEach 才注入 @Mock，先构造会拿到 null mapper，
        // 其 NPE 又会被 record() 的旁路 try-catch 吞掉，用例会整体假红
        auditLogService = new AuditLogService(auditLogMapper);
    }

    @AfterEach
    void clearThreadLocals() {
        SecurityUtil.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    // ==================== 造数 ====================

    private void loginAsOperator() {
        LoginUser operator = new LoginUser();
        operator.setId(OPERATOR_ID);
        operator.setUsername(OPERATOR_NAME);
        SecurityUtil.set(operator);
    }

    /** 模拟请求现场：X-Real-IP 是生产 Nginx 不可伪造的覆写头，解析必须优先采信。 */
    private void bindRequestWithRealIp() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Real-IP", CLIENT_IP);
        request.setRemoteAddr("10.0.0.2");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private AuditLog captured() {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper).insert(captor.capture());
        return captor.getValue();
    }

    // ==================== 五类操作各自落对的 action 与明细 ====================

    @Test
    void examPublishRecordsOperatorIpAndDetails() {
        loginAsOperator();
        bindRequestWithRealIp();

        auditLogService.logExamPublish(10L, "期末考-A");

        AuditLog row = captured();
        assertEquals(AuditLog.ACTION_EXAM_PUBLISH, row.getAction());
        assertEquals(AuditLog.STATUS_SUCCESS, row.getStatus());
        assertEquals(OPERATOR_ID, row.getUserId());
        assertEquals(OPERATOR_NAME, row.getUsername());
        assertEquals(CLIENT_IP, row.getIpAddress(), "应优先采信 X-Real-IP 而非直连代理地址");
        assertTrue(row.getDetails().contains("examId=10"), "details 须带关联考试 ID");
        assertTrue(row.getDetails().contains("期末考-A"), "details 须带考试标题");
    }

    @Test
    void examDeleteRecordsOperatorAndDetails() {
        loginAsOperator();
        bindRequestWithRealIp();

        auditLogService.logExamDelete(11L, "期末考-B");

        AuditLog row = captured();
        assertEquals(AuditLog.ACTION_EXAM_DELETE, row.getAction());
        assertEquals(OPERATOR_ID, row.getUserId());
        assertEquals(CLIENT_IP, row.getIpAddress());
        assertTrue(row.getDetails().contains("examId=11"));
        assertTrue(row.getDetails().contains("期末考-B"));
    }

    @Test
    void scorePublishRecordsHeadcount() {
        loginAsOperator();
        bindRequestWithRealIp();

        auditLogService.logScorePublish(12L, "期末考-C", 42);

        AuditLog row = captured();
        assertEquals(AuditLog.ACTION_SCORE_PUBLISH, row.getAction());
        assertTrue(row.getDetails().contains("examId=12"));
        assertTrue(row.getDetails().contains("count=42"), "details 须带发布人数");
    }

    @Test
    void scoreRevokeRecordsReason() {
        loginAsOperator();
        bindRequestWithRealIp();

        auditLogService.logScoreRevoke(13L, "期末考-D", "成绩录入有误");

        AuditLog row = captured();
        assertEquals(AuditLog.ACTION_SCORE_REVOKE, row.getAction());
        assertTrue(row.getDetails().contains("examId=13"));
        assertTrue(row.getDetails().contains("成绩录入有误"), "details 须带撤回原因（审计要求）");
    }

    @Test
    void userKickRecordsTargetUser() {
        loginAsOperator();
        bindRequestWithRealIp();

        auditLogService.logUserKick(88L);

        AuditLog row = captured();
        assertEquals(AuditLog.ACTION_USER_KICK, row.getAction());
        assertEquals(OPERATOR_ID, row.getUserId(), "操作人是踢人的管理员，不是被踢用户");
        assertTrue(row.getDetails().contains("targetUserId=88"), "details 须带被踢用户 ID");
    }

    // ==================== 考中顺延/补时（add-exam-time-extension） ====================

    @Test
    void examExtendTimeGlobalRecordsModeMinutesAndReason() {
        loginAsOperator();
        bindRequestWithRealIp();

        auditLogService.logExamExtendTime(16L, "期末考-G", 30, true, 25, List.of(101L, 102L), "机房突发断电应急顺延");

        AuditLog row = captured();
        assertEquals(AuditLog.ACTION_EXAM_EXTEND_TIME, row.getAction());
        assertEquals(AuditLog.STATUS_SUCCESS, row.getStatus());
        assertEquals(OPERATOR_ID, row.getUserId());
        assertEquals(CLIENT_IP, row.getIpAddress());
        assertTrue(row.getDetails().contains("examId=16"));
        assertTrue(row.getDetails().contains("mode=GLOBAL"), "details 须标明全场模式");
        assertTrue(row.getDetails().contains("minutes=30"), "details 须带顺延时长");
        assertTrue(row.getDetails().contains("affectedSubmissions=25"), "details 须带受影响在途答卷数");
        assertTrue(row.getDetails().contains("机房突发断电应急顺延"), "details 须带顺延原因（审计要求）");
    }

    @Test
    void examExtendTimeIndividualMarksModeAndStudentIds() {
        loginAsOperator();
        bindRequestWithRealIp();

        auditLogService.logExamExtendTime(17L, "期末考-H", 20, false, 2, List.of(201L, 202L), "考生设备死机重启补时");

        AuditLog row = captured();
        assertEquals(AuditLog.ACTION_EXAM_EXTEND_TIME, row.getAction());
        assertTrue(row.getDetails().contains("mode=INDIVIDUAL"), "details 须标明个人模式");
        assertTrue(row.getDetails().contains("studentIds=[201, 202]"), "个人模式 details 须带受影响学生名单");
        assertTrue(row.getDetails().contains("考生设备死机重启补时"));
    }

    // ==================== 现场缺失与旁路容错 ====================

    @Test
    void missingRequestContextYieldsNullIpWithoutFailing() {
        loginAsOperator();
        RequestContextHolder.resetRequestAttributes();

        assertDoesNotThrow(() -> auditLogService.logExamPublish(14L, "期末考-E"));

        assertNull(captured().getIpAddress(), "无请求上下文（定时任务/MQ 线程）时 IP 允许为空");
    }

    @Test
    void persistenceFailureNeverPropagatesOutOfAnyOperation() {
        loginAsOperator();
        bindRequestWithRealIp();
        doThrow(new RuntimeException("audit db down")).when(auditLogMapper).insert(any(AuditLog.class));

        assertDoesNotThrow(() -> auditLogService.logExamPublish(15L, "期末考-F"));
        assertDoesNotThrow(() -> auditLogService.logExamDelete(15L, "期末考-F"));
        assertDoesNotThrow(() -> auditLogService.logScorePublish(15L, "期末考-F", 1));
        assertDoesNotThrow(() -> auditLogService.logScoreRevoke(15L, "期末考-F", "误发布"));
        assertDoesNotThrow(() -> auditLogService.logUserKick(15L));
    }
}

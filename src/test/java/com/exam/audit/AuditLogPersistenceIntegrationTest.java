package com.exam.audit;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.audit.entity.AuditLog;
import com.exam.audit.mapper.AuditLogMapper;
import com.exam.config.TraceIdInterceptor;
import com.exam.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 审计日志落库验证（harden-security-config 的收尾）。
 *
 * <p>打真实 HTTP 端点而不是直接调 AuditLogService：要证的正是"登录链路真的把事件写进了
 * audit_log"，包括操作人身份与 trace_id 这两处只有走完整链路才会浮现的环节。
 * 旧实现只 log.info，且反射取用户在 @Async 线程里恒为 null——直调单测照样全绿。
 *
 * <p>H2 数据跨用例保留，故每个用例用独立用户名，按 username 精确计数。
 */
class AuditLogPersistenceIntegrationTest extends IntegrationTestBase {

    @Autowired
    private AuditLogMapper auditLogMapper;

    private MvcResult login(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andReturn();
    }

    private List<AuditLog> rowsOf(String username, String action) {
        return auditLogMapper.selectList(Wrappers.<AuditLog>lambdaQuery()
                .eq(AuditLog::getUsername, username)
                .eq(AuditLog::getAction, action));
    }

    /** 登录失败必须留下一条 FAILURE，且此时没有 user_id（账号可能压根不存在）。 */
    @Test
    void failedLoginPersistsFailureRow() throws Exception {
        String username = "aud_fail_" + System.nanoTime();
        login(username, "Nope12345");

        List<AuditLog> rows = rowsOf(username, AuditLog.ACTION_LOGIN);
        assertEquals(1, rows.size(), "一次失败登录应落一条审计");
        assertEquals(AuditLog.STATUS_FAILURE, rows.get(0).getStatus());
        assertNull(rows.get(0).getUserId(), "账号不存在时不该有 user_id");
    }

    /**
     * 登录成功要带上操作人 user_id。
     * 这是旧实现永远做不到的一点：它在 @Async 工作线程里用反射读 SecurityContext，恒为 null。
     */
    @Test
    void successfulLoginPersistsRowWithOperatorId() throws Exception {
        String username = "aud_ok_" + System.nanoTime();
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"Pass1234\","
                                + "\"name\":\"审计用例\",\"email\":\"" + username + "@test.com\","
                                + "\"roleType\":\"STUDENT\"}"))
                .andExpect(status().isOk());

        login(username, "Pass1234");

        List<AuditLog> rows = rowsOf(username, AuditLog.ACTION_LOGIN);
        assertEquals(1, rows.size());
        assertEquals(AuditLog.STATUS_SUCCESS, rows.get(0).getStatus());
        assertNotNull(rows.get(0).getUserId(), "成功登录应记下操作人 user_id");
    }

    /** 连续失败触发锁定时，除逐次 LOGIN 外还要有一条 ACCOUNT_LOCKED / WARNING。 */
    @Test
    void accountLockPersistsWarningRow() throws Exception {
        String username = "aud_lock_" + System.nanoTime();
        for (int i = 0; i < 5; i++) {
            login(username, "Nope12345");
        }

        List<AuditLog> locks = rowsOf(username, AuditLog.ACTION_ACCOUNT_LOCKED);
        assertTrue(locks.size() >= 1, "达到锁定阈值应留下 ACCOUNT_LOCKED 记录");
        assertEquals(AuditLog.STATUS_WARNING, locks.get(0).getStatus());
    }

    /**
     * trace_id 必须是本次请求真实的 OTel traceId。旧实现当场 new 一个随机 UUID，
     * 拿着它去 Jaeger 什么都查不到。
     */
    @Test
    void auditRowTraceIdMatchesRequestTrace() throws Exception {
        String username = "aud_trace_" + System.nanoTime();
        MvcResult result = login(username, "Nope12345");
        String headerTraceId = result.getResponse().getHeader(TraceIdInterceptor.TRACE_ID_HEADER);

        List<AuditLog> rows = rowsOf(username, AuditLog.ACTION_LOGIN);
        assertEquals(1, rows.size());

        String stored = rows.get(0).getTraceId();
        assertNotNull(stored, "审计行应带 trace_id 才能与链路对齐");
        assertEquals(headerTraceId, stored,
                "trace_id 应与响应头 X-Trace-Id 同一个，才能从审计跳查 Jaeger");
    }
}

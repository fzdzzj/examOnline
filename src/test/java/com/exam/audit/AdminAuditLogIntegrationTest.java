package com.exam.audit;

import com.exam.audit.entity.AuditLog;
import com.exam.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 审计日志读接口验证（GET /api/admin/audit-logs）。
 *
 * <p>写侧已由 {@link AuditLogPersistenceIntegrationTest} 证明，这里补的是"查得到"这一半：
 * 审计只落库、无人能查，合规价值只兑现了一半。
 *
 * <p>H2 数据跨用例保留，所以断言一律围绕各用例自己造的唯一用户名，不去数全表。
 */
class AdminAuditLogIntegrationTest extends IntegrationTestBase {

    private String adminToken() throws Exception {
        return loginToken("admin", "admin123");
    }

    /** 造 n 次失败登录（401）：账号不存在也照样落审计，比先注册再登录少一层耦合。 */
    private void failedLogins(String username, int times) throws Exception {
        for (int i = 0; i < times; i++) {
            mockMvc.perform(jsonPost("/api/auth/login", null,
                    "{\"username\":\"" + username + "\",\"password\":\"Nope12345\"}")).andReturn();
        }
    }

    private JsonNode query(String token, String query) throws Exception {
        return perform(get("/api/admin/audit-logs" + query).header("Authorization", bearer(token)), 200)
                .get("data");
    }

    /**
     * 按 username + action 过滤，且倒序返回。
     *
     * <p>倒序是这条接口的核心可用性：管理员进来想看"刚刚发生了什么"，正序会把最近的事件埋在末页。
     * 过滤条件若失效，这里会拿到整张表的其他账号而变红。
     */
    @Test
    void filtersByUserNameAndActionNewestFirst() throws Exception {
        String username = "aud_q_" + System.nanoTime();
        failedLogins(username, 3);

        JsonNode rows = query(adminToken(),
                "?username=" + username + "&action=" + AuditLog.ACTION_LOGIN);

        assertEquals(3, rows.size(), "该账号的 LOGIN 审计应全部返回");
        for (JsonNode row : rows) {
            assertEquals(username, row.get("username").asText());
            assertEquals(AuditLog.ACTION_LOGIN, row.get("action").asText());
            assertEquals(AuditLog.STATUS_FAILURE, row.get("status").asText());
        }
        for (int i = 1; i < rows.size(); i++) {
            assertTrue(rows.get(i - 1).get("id").asLong() > rows.get(i).get("id").asLong(),
                    "id 应严格倒序（最近优先）");
        }
    }

    /**
     * 读接口要能取到 trace_id——审计行跳查 Jaeger 的唯一凭据。
     * 若 DTO 漏映射这个字段，写侧测试照样绿，但链路断了。
     */
    @Test
    void exposesTraceIdForTraceLookup() throws Exception {
        String username = "aud_tx_" + System.nanoTime();
        failedLogins(username, 1);

        JsonNode row = query(adminToken(), "?username=" + username).get(0);
        assertNotNull(row.get("traceId").asText(), "响应应带 traceId");
        assertEquals(32, row.get("traceId").asText().length(),
                "traceId 应是完整的 OTel 链路标识（32 位十六进制），截断则查不到 trace");
    }

    /** 非 ADMIN 一律 403：类级 @RequireRole(ADMIN) 已覆盖，不需要额外的权限点。 */
    @Test
    void nonAdminRolesForbidden() throws Exception {
        mockMvc.perform(get("/api/admin/audit-logs").header("Authorization", bearer(registerTeacher())))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/audit-logs").header("Authorization", bearer(registerStudent())))
                .andExpect(status().isForbidden());
    }

    /**
     * size 越界 → 400。
     *
     * <p>这条守的是类级 {@code @Validated}：去掉它，请求仍会被 Spring 6.1 的内建校验拦下，
     * 但抛 HandlerMethodValidationException 而非 ConstraintViolationException，
     * 未被全局异常处理器映射 → 客户端看到 500（已实测）。
     */
    @Test
    void oversizedPageSizeRejected() throws Exception {
        mockMvc.perform(get("/api/admin/audit-logs")
                        .header("Authorization", bearer(adminToken()))
                        .param("size", "101"))
                .andExpect(status().isBadRequest());
    }
}

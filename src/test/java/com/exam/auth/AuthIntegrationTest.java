package com.exam.auth;

import com.exam.auth.dto.ChangePasswordRequest;
import com.exam.auth.dto.LoginRequest;
import com.exam.auth.dto.RefreshRequest;
import com.exam.auth.dto.RegisterRequest;
import com.exam.auth.dto.ResetPasswordRequest;
import com.exam.auth.dto.SendResetCodeRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证核心链路集成测试（MockMvc + H2 + Redis db 15）：
 * 注册 → 登录 → 双 Token 签发 → 刷新轮换 → 复用检测全端下线 → 登出黑名单 →
 * 改密码/找回密码旧会话失效 → RBAC 授权（角色层级/权限点）→ 锁定 → 限流。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void flushRedis() {
        // 清空测试库（db 15），隔离令牌/锁定/验证码等 Redis 状态
        var conn = redis.getConnectionFactory().getConnection();
        try {
            conn.serverCommands().flushDb();
        } finally {
            conn.close();
        }
    }

    // ==================== 注册 ====================

    @Test
    void studentRegisterLoginAndDuplicateRejected() throws Exception {
        register("stu_a1", "stu_a1@test.com", "STUDENT", null, "Pass1234");

        // 重复注册 → 400 账号已存在
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegisterRequest() {{
                            setUsername("stu_a1");
                            setPassword("Pass1234");
                            setName("重复");
                            setRoleType("STUDENT");
                        }})))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1002));

        // 弱密码 → 400
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegisterRequest() {{
                            setUsername("stu_a2");
                            setPassword("short");
                            setName("弱密码");
                            setRoleType("STUDENT");
                        }})))
                .andExpect(status().isBadRequest());
    }

    @Test
    void loginSuccessReturnsBothTokens() throws Exception {
        register("stu_a3", "stu_a3@test.com", "STUDENT", null, "Pass1234");
        JsonNode data = login("stu_a3", "Pass1234");
        assertNotNull(data.get("accessToken").asText());
        assertNotNull(data.get("refreshToken").asText());
        assertEquals("Bearer", data.get("tokenType").asText());
        assertEquals(30 * 60, data.get("accessExpiresIn").asLong());
    }

    @Test
    void wrongPasswordAndUnknownAccountReturnSameMessage() throws Exception {
        register("stu_a4", "stu_a4@test.com", "STUDENT", null, "Pass1234");
        // 密码错误
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginRequest() {{
                            setUsername("stu_a4");
                            setPassword("wrong999");
                        }})))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1005))
                .andExpect(jsonPath("$.message").value("账号或密码错误"));
        // 账号不存在：同一提示（防账号枚举）
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginRequest() {{
                            setUsername("no_such_user");
                            setPassword("wrong999");
                        }})))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("账号或密码错误"));
    }

    // ==================== 刷新 / 复用检测 ====================

    @Test
    void refreshRotatesAndReuseInvalidatesAllSessions() throws Exception {
        register("stu_b1", "stu_b1@test.com", "STUDENT", null, "Pass1234");
        String access1 = loginToken("stu_b1", "Pass1234");
        String refresh1 = loginRefresh("stu_b1", "Pass1234");

        // 正常轮换
        JsonNode refreshed = refresh(refresh1);
        String access2 = refreshed.get("accessToken").asText();

        // 旧 Refresh 复用 → 全端下线
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RefreshRequest() {{
                            setRefreshToken(refresh1);
                        }})))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1009));

        // 新 Access 也失效（会话版本递增）
        me(access2).andExpect(status().isUnauthorized());
        me(access1).andExpect(status().isUnauthorized());

        // 重新登录恢复正常（版本从递增后的值继续），新会话的 Refresh 可正常轮换
        JsonNode relogin = login("stu_b1", "Pass1234");
        String access3 = relogin.get("accessToken").asText();
        String refresh3 = relogin.get("refreshToken").asText();
        me(access3).andExpect(status().isOk());
        refresh(refresh3); // helper 内断言 200（正常轮换）
    }

    @Test
    void refreshWithExpiredOrGarbageTokenRejected() throws Exception {
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RefreshRequest() {{
                            setRefreshToken("garbage.token.value");
                        }})))
                .andExpect(status().isUnauthorized());
    }

    // ==================== 登出 / 黑名单 ====================

    @Test
    void logoutBlacklistsAccessToken() throws Exception {
        register("stu_c1", "stu_c1@test.com", "STUDENT", null, "Pass1234");
        JsonNode tokens = login("stu_c1", "Pass1234");
        String access = tokens.get("accessToken").asText();
        String refresh = tokens.get("refreshToken").asText();

        mockMvc.perform(post("/api/auth/logout").header("Authorization", bearer(access)))
                .andExpect(status().isOk());

        // 同一 Access Token 后续请求被拒绝（黑名单命中）
        me(access).andExpect(status().isUnauthorized());
        // 登出同时删除 Refresh 会话 → 旧 Refresh 无法续期
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RefreshRequest() {{
                            setRefreshToken(refresh);
                        }})))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpointWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    // ==================== 改密码 / 找回密码 ====================

    @Test
    void changePasswordInvalidatesOldSessions() throws Exception {
        register("stu_d1", "stu_d1@test.com", "STUDENT", null, "Pass1234");
        String access = loginToken("stu_d1", "Pass1234");

        mockMvc.perform(post("/api/auth/password/change")
                        .header("Authorization", bearer(access))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ChangePasswordRequest() {{
                            setOldPassword("Pass1234");
                            setNewPassword("Newpass99");
                        }})))
                .andExpect(status().isOk());

        // 旧 Access 失效（会话版本递增 + jti 黑名单）
        me(access).andExpect(status().isUnauthorized());
        // 旧密码登录失败
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginRequest() {{
                            setUsername("stu_d1");
                            setPassword("Pass1234");
                        }})))
                .andExpect(status().isUnauthorized());
        // 新密码可登录
        login("stu_d1", "Newpass99");
    }

    @Test
    void changePasswordWithWrongOldPasswordRejected() throws Exception {
        register("stu_d2", "stu_d2@test.com", "STUDENT", null, "Pass1234");
        String access = loginToken("stu_d2", "Pass1234");
        mockMvc.perform(post("/api/auth/password/change")
                        .header("Authorization", bearer(access))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ChangePasswordRequest() {{
                            setOldPassword("wrong999");
                            setNewPassword("Newpass99");
                        }})))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1011));
    }

    @Test
    void passwordResetViaEmailCodeIsSingleUse() throws Exception {
        String email = "reset1@test.com";
        register("stu_d3", email, "STUDENT", null, "Pass1234");

        mockMvc.perform(post("/api/auth/password/reset-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new SendResetCodeRequest() {{
                            setEmail(email);
                        }})))
                .andExpect(status().isOk());

        // 从 Redis 取回验证码（SMTP 未配置时日志兜底，测试直接读存储）
        String code = redis.opsForValue().get("auth:reset:" + email);
        assertNotNull(code, "验证码应已写入 Redis");

        mockMvc.perform(post("/api/auth/password/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ResetPasswordRequest() {{
                            setEmail(email);
                            setCode(code);
                            setNewPassword("Resetpass1");
                        }})))
                .andExpect(status().isOk());

        // 单次使用：同码复用被拒
        mockMvc.perform(post("/api/auth/password/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ResetPasswordRequest() {{
                            setEmail(email);
                            setCode(code);
                            setNewPassword("Resetpass2");
                        }})))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1010));

        // 旧密码失效、新密码可登录
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginRequest() {{
                            setUsername("stu_d3");
                            setPassword("Pass1234");
                        }})))
                .andExpect(status().isUnauthorized());
        login("stu_d3", "Resetpass1");
    }

    @Test
    void resetWithWrongCodeRejected() throws Exception {
        register("stu_d4", "reset2@test.com", "STUDENT", null, "Pass1234");
        mockMvc.perform(post("/api/auth/password/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ResetPasswordRequest() {{
                            setEmail("reset2@test.com");
                            setCode("000000");
                            setNewPassword("Resetpass1");
                        }})))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1010));
    }

    // ==================== RBAC 授权（角色/权限 AOP） ====================

    @Test
    void teacherRegisterRequiresValidInviteCode() throws Exception {
        // 管理员生成邀请码
        String adminAccess = loginToken("admin", "admin123");
        String createdBody = mockMvc.perform(post("/api/admin/invite-codes")
                        .header("Authorization", bearer(adminAccess))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String inviteCode = objectMapper.readTree(createdBody).get("data").get("code").asText();
        assertNotNull(inviteCode);

        // 无效邀请码 → 400
        register("tea_bad", "tea_bad@test.com", "TEACHER", "INVALIDCODE", "Pass1234")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1004));

        // 有效邀请码 → 注册成功且角色为 TEACHER
        register("tea_good", "tea_good@test.com", "TEACHER", inviteCode, "Pass1234")
                .andExpect(status().isOk());
        String teaAccess = loginToken("tea_good", "Pass1234");
        me(teaAccess)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roles", hasItem("TEACHER")));

        // 角色层级不足：TEACHER 访问 ADMIN 专属接口 → 403（类级 @RequireRole(ADMIN)）
        mockMvc.perform(post("/api/admin/invite-codes")
                        .header("Authorization", bearer(teaAccess))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void roleAspectBlocksStudentFromAdminEndpoint() throws Exception {
        register("stu_e1", "stu_e1@test.com", "STUDENT", null, "Pass1234");
        String studentAccess = loginToken("stu_e1", "Pass1234");

        // 学生访问邀请码管理（invite:manage）→ 403
        mockMvc.perform(post("/api/admin/invite-codes")
                        .header("Authorization", bearer(studentAccess))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        // 学生访问踢人（user:manage）→ 403
        mockMvc.perform(post("/api/admin/users/1/kick")
                        .header("Authorization", bearer(studentAccess)))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanManageInviteCodesAndKick() throws Exception {
        String adminAccess = loginToken("admin", "admin123");

        // 列表接口可访问（权限点满足）
        mockMvc.perform(get("/api/admin/invite-codes")
                        .header("Authorization", bearer(adminAccess)))
                .andExpect(status().isOk());

        // 踢人：目标用户全端下线
        register("stu_e2", "stu_e2@test.com", "STUDENT", null, "Pass1234");
        String stuAccess = loginToken("stu_e2", "Pass1234");
        me(stuAccess).andExpect(status().isOk());

        Long stuId = objectMapper.readTree(me(stuAccess).andReturn().getResponse().getContentAsString())
                .get("data").get("id").asLong();
        mockMvc.perform(post("/api/admin/users/" + stuId + "/kick")
                        .header("Authorization", bearer(adminAccess)))
                .andExpect(status().isOk());
        me(stuAccess).andExpect(status().isUnauthorized());
    }

    // ==================== 登录锁定 / 限流 ====================

    @Test
    void accountLockedAfterFiveFailures() throws Exception {
        register("stu_f1", "stu_f1@test.com", "STUDENT", null, "Pass1234");

        // 连续 5 次密码错误 → 401
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new LoginRequest() {{
                                setUsername("stu_f1");
                                setPassword("wrong999");
                            }})))
                    .andExpect(status().isUnauthorized());
        }
        // 第 6 次（即使密码正确）→ 423 锁定
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginRequest() {{
                            setUsername("stu_f1");
                            setPassword("Pass1234");
                        }})))
                .andExpect(status().is(423))
                .andExpect(jsonPath("$.code").value(1007))
                .andExpect(jsonPath("$.message", containsString("锁定")));
    }

    @Test
    void loginRateLimitReturns429() throws Exception {
        register("stu_f2", "stu_f2@test.com", "STUDENT", null, "Pass1234");

        // 前 10 次正常（成功不重置限流计数）
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new LoginRequest() {{
                                setUsername("stu_f2");
                                setPassword("Pass1234");
                            }})))
                    .andExpect(status().isOk());
        }
        // 第 11 次 → 429
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginRequest() {{
                            setUsername("stu_f2");
                            setPassword("Pass1234");
                        }})))
                .andExpect(status().is(429))
                .andExpect(jsonPath("$.code").value(1008));
    }

    // ==================== 工具方法 ====================

    private String json(Object obj) throws Exception {
        return objectMapper.writeValueAsString(obj);
    }

    private org.springframework.test.web.servlet.ResultActions register(String username, String email,
                                                                        String roleType, String inviteCode,
                                                                        String password) throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(new RegisterRequest() {{
                    setUsername(username);
                    setPassword(password);
                    setName("用户-" + username);
                    setEmail(email);
                    setRoleType(roleType);
                    setInviteCode(inviteCode);
                }})));
    }

    private JsonNode login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginRequest() {{
                            setUsername(username);
                            setPassword(password);
                        }})))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private String loginToken(String username, String password) throws Exception {
        return login(username, password).get("accessToken").asText();
    }

    private String loginRefresh(String username, String password) throws Exception {
        return login(username, password).get("refreshToken").asText();
    }

    private JsonNode refresh(String refreshToken) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RefreshRequest() {{
                            setRefreshToken(refreshToken);
                        }})))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private org.springframework.test.web.servlet.ResultActions me(String accessToken) throws Exception {
        return mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(accessToken)));
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}

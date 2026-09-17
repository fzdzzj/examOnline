package com.exam.auth;

import com.exam.auth.dto.ChangePasswordRequest;
import com.exam.auth.dto.LoginRequest;
import com.exam.auth.dto.RegisterRequest;
import com.exam.config.AdminInitializer;
import com.exam.user.entity.User;
import com.exam.user.service.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 初始密码强制修改标记（add-auth-must-change-password）集成测试：
 * 走真实链路（MockMvc + H2 + Redis db 15，不 mock 任何 Service），覆盖
 * 首次创建 admin 置 1 → /api/auth/me 可读 → 改密成功置 0 → 重复初始化不打回（防重启死循环）→
 * 普通注册用户不受影响 → 标记不入 JWT claim → 标记出现在对外契约中。
 *
 * <p><b>为什么用独立 H2 库</b>：主测试库 {@code jdbc:h2:mem:exam} 跨用例、跨上下文保留，
 * 全仓有 6 处用例依赖 {@code admin/admin123} 能登录。本类的「改密」场景必然改掉 admin 口令，
 * 若跑在共享库里会污染其它用例。这里用 properties 覆盖主从数据源指向一个独立命名的内存库，
 * 于是 {@link AdminInitializer} 会在一个全新库里真实走一遍「首次创建」分支（而不是命中已存在分支），
 * 改密与重复初始化都可以放心作用于真实 admin。
 *
 * <p>用例间存在有意共享的状态推进（Order 3 会改掉本库 admin 的口令），故显式声明执行顺序，
 * Order 1/2 只读、Order 4/5 不依赖 admin。
 */
@SpringBootTest(properties = {
        "spring.datasource.dynamic.datasource.master.url="
                + "jdbc:h2:mem:exam_must_change_password;MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
                + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.dynamic.datasource.slave.url="
                + "jdbc:h2:mem:exam_must_change_password;MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
                + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MustChangePasswordIntegrationTest {

    /** 本库 admin 的初始口令（= application-test.yml 的 exam.auth.admin.password）。 */
    private static final String ADMIN_INIT_PASSWORD = "admin123";
    private static final String ADMIN_NEW_PASSWORD = "adminPass456";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private UserService userService;

    @Autowired
    private AdminInitializer adminInitializer;

    /** 注册账号唯一序号，避免同库内账号冲突。 */
    private final AtomicLong seq = new AtomicLong();

    @BeforeEach
    void flushRedis() {
        // 清空测试库（db 15），隔离令牌/锁定/限流等 Redis 状态
        var conn = redis.getConnectionFactory().getConnection();
        try {
            conn.serverCommands().flushDb();
        } finally {
            conn.close();
        }
    }

    // ==================== 读路径 + 写路径 A ====================

    @Test
    @Order(1)
    void firstCreatedAdminIsFlaggedAndFlagIsReadable() throws Exception {
        // AdminInitializer 在独立新库里真实走「首次创建」分支 → 库内该列应为 1
        User admin = userService.getByUsername("admin");
        assertNotNull(admin, "AdminInitializer 应在本用例的独立库中创建 admin");
        assertEquals(1, admin.getMustChangePassword(), "首次创建的 admin 必须被置 must_change_password=1");

        // 客户端可实时读到该标记
        String access = loginToken("admin", ADMIN_INIT_PASSWORD);
        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(access)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("admin"))
                .andExpect(jsonPath("$.data.mustChangePassword").value(true));
    }

    @Test
    @Order(2)
    void flagIsNotCarriedAsJwtClaim() throws Exception {
        // 可变状态不入无状态 token：Access Token 载荷里不得出现该标记（客户端只能实时查 /me）
        String access = loginToken("admin", ADMIN_INIT_PASSWORD);
        JsonNode claims = readJwtPayload(access);
        assertTrue(claims.has("username"), "JWT 载荷应可正常解析（既有 claim 存在）");
        claims.fieldNames().forEachRemaining(name ->
                assertFalse(name.toLowerCase().contains("must"),
                        "强制改密标记不得作为 JWT 声明携带，实际 claim=" + name));
    }

    // ==================== 写路径 B + 防重启死循环 ====================

    @Test
    @Order(3)
    void passwordChangeClearsFlagAndRepeatedInitializationDoesNotRelockAdmin() throws Exception {
        String access = loginToken("admin", ADMIN_INIT_PASSWORD);
        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(access)))
                .andExpect(jsonPath("$.data.mustChangePassword").value(true));

        // 1) 改密成功 → 标记解除（库内归 0，接口返回 false）
        mockMvc.perform(post("/api/auth/password/change")
                        .header("Authorization", bearer(access))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ChangePasswordRequest() {{
                            setOldPassword(ADMIN_INIT_PASSWORD);
                            setNewPassword(ADMIN_NEW_PASSWORD);
                        }})))
                .andExpect(status().isOk());
        assertEquals(0, currentAdminMustChangeFlag(), "改密成功后库内 must_change_password 应归 0");

        String newAccess = loginToken("admin", ADMIN_NEW_PASSWORD);
        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(newAccess)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mustChangePassword").value(false));

        // 2) 模拟重启：再跑一遍初始化器（幂等路径）→ 绝不得把已改密的 admin 打回强制改密
        adminInitializer.run();
        adminInitializer.run();
        assertEquals(0, currentAdminMustChangeFlag(),
                "重复执行初始化不得改写已存在 admin 的强制改密标记（重启死循环护栏）");
        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(newAccess)))
                .andExpect(jsonPath("$.data.mustChangePassword").value(false));

        // 3) 初始化器也不得重置口令：新口令仍可登录，初始口令已失效
        loginToken("admin", ADMIN_NEW_PASSWORD);
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginRequest() {{
                            setUsername("admin");
                            setPassword(ADMIN_INIT_PASSWORD);
                        }})))
                .andExpect(status().isUnauthorized());
    }

    // ==================== 普通账号不受影响 ====================

    @Test
    @Order(4)
    void normalRegisteredUserIsNotFlagged() throws Exception {
        String username = "stu_mcp_" + seq.incrementAndGet();
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegisterRequest() {{
                            setUsername(username);
                            setPassword("pass1234");
                            setName("自助注册-" + username);
                            setEmail(username + "@test.com");
                            setRoleType("STUDENT");
                        }})))
                .andExpect(status().isOk());

        assertEquals(0, userService.getByUsername(username).getMustChangePassword(),
                "自助注册账号不该被要求强制改密（用户自己选的口令）");
        String access = loginToken(username, "pass1234");
        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(access)))
                .andExpect(status().isOk())
                // non_null 序列化下 false 仍会输出，故直接断言布尔值
                .andExpect(jsonPath("$.data.mustChangePassword").value(false));
    }

    // ==================== 契约护栏 ====================

    @Test
    @Order(5)
    void flagIsPublishedInOpenApiContract() throws Exception {
        // 标记必须出现在对外契约中，否则前端类型化客户端生成不到该字段
        MvcResult result = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
        JsonNode schema = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("components").path("schemas").path("CurrentUserResponse").path("properties");
        assertTrue(schema.has("mustChangePassword"),
                "openapi 契约的 CurrentUserResponse 应含 mustChangePassword，实际字段=" + schema);
        assertEquals("boolean", schema.path("mustChangePassword").path("type").asText());
    }

    // ==================== 工具方法 ====================

    private Integer currentAdminMustChangeFlag() {
        return userService.getByUsername("admin").getMustChangePassword();
    }

    /** 解出 Access Token 的载荷（不校验签名，只看声明集合）。 */
    private JsonNode readJwtPayload(String jwt) throws Exception {
        String[] parts = jwt.split("\\.");
        assertEquals(3, parts.length, "Access Token 应为三段式 JWT");
        byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
        return objectMapper.readTree(new String(decoded, StandardCharsets.UTF_8));
    }

    private String json(Object obj) throws Exception {
        return objectMapper.writeValueAsString(obj);
    }

    private String loginToken(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginRequest() {{
                            setUsername(username);
                            setPassword(password);
                        }})))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        return data.get("accessToken").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}

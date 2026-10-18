package com.exam.auth;

import com.exam.auth.dto.RegisterRequest;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 注册端点 IP 频控集成测试（harden-register-rate-limit）：
 * /api/auth/register 按客户端 IP 维度固定窗口限流（exam.auth.rate-limit.register-per-minute，默认 10
 * 次/分钟/IP），防范高并发注册触发密集 BCrypt 计算造成 CPU 耗尽 DoS；超限 429 且先于 BCrypt 与入库拒绝，
 * 计数按 IP 隔离。请求经 X-Real-IP 头模拟反代来源（ClientIpResolver 优先采信，
 * LoginAuditRealIpIntegrationTest 同款先例）。test 档造数放宽到 50（application-test.yml），
 * 本类显式钉回生产默认 10 验证执行路径。
 */
@SpringBootTest(properties = "exam.auth.rate-limit.register-per-minute=10")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RegisterRateLimitIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void flushRedis() {
        var conn = redis.getConnectionFactory().getConnection();
        try {
            conn.serverCommands().flushDb();
        } finally {
            conn.close();
        }
    }

    private void registerAs(String ip, String username) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .header("X-Real-IP", ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest() {{
                            setUsername(username);
                            setPassword("Pass1234");
                            setName("注册限流");
                            setEmail(username + "@test.com");
                            setRoleType("STUDENT");
                        }})))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions registerRaw(String ip, String username)
            throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                .header("X-Real-IP", ip)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new RegisterRequest() {{
                    setUsername(username);
                    setPassword("Pass1234");
                    setName("注册限流");
                    setEmail(username + "@test.com");
                    setRoleType("STUDENT");
                }})));
    }

    @Test
    void registerAllowsUpToThresholdPerIpThenReturns429() throws Exception {
        // 阈值内（默认 10 次/分钟/IP）正常放行
        for (int i = 0; i < 10; i++) {
            registerAs("203.0.113.31", "stu_rrla" + i);
        }
        // 第 11 次 → 429 TOO_MANY_REQUESTS（先于 BCrypt 计算与入库拒绝）
        registerRaw("203.0.113.31", "stu_rrla_over")
                .andExpect(status().is(429))
                .andExpect(jsonPath("$.code").value(1008));
    }

    @Test
    void registerRateLimitIsIsolatedPerIp() throws Exception {
        // IP A 耗尽阈值：10 次放行、第 11 次 429
        for (int i = 0; i < 10; i++) {
            registerAs("203.0.113.32", "stu_rrlb" + i);
        }
        registerRaw("203.0.113.32", "stu_rrlb_over")
                .andExpect(status().is(429))
                .andExpect(jsonPath("$.code").value(1008));
        // IP B 首次注册不受 IP A 计数影响（计数按客户端 IP 隔离）
        registerAs("203.0.113.33", "stu_rrlb_other");
    }
}

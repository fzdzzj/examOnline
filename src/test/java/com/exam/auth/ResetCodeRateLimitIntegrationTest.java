package com.exam.auth;

import com.exam.auth.dto.RegisterRequest;
import com.exam.auth.dto.SendResetCodeRequest;
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
 * 找回密码验证码按维度限流集成测试（harden-auth-edge-cases）：
 * /api/auth/password/reset-code 按 email+IP 维度固定窗口限流（复用登录限流阈值），
 * 超限 429；限流先于邮箱存在性判断——未注册邮箱同样计数、同样不暴露存在性。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ResetCodeRateLimitIntegrationTest {

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

    private void requestResetCode(String email) throws Exception {
        mockMvc.perform(post("/api/auth/password/reset-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SendResetCodeRequest() {{
                            setEmail(email);
                        }})))
                .andExpect(status().isOk());
    }

    @Test
    void resetCodeEndpointRateLimitedPerEmailAfterPerMinuteThreshold() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest() {{
                            setUsername("stu_rl1");
                            setPassword("Pass1234");
                            setName("限流");
                            setEmail("rl1@test.com");
                            setRoleType("STUDENT");
                        }})))
                .andExpect(status().isOk());

        for (int i = 0; i < 10; i++) {
            requestResetCode("rl1@test.com");
        }
        // 第 11 次 → 429
        mockMvc.perform(post("/api/auth/password/reset-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SendResetCodeRequest() {{
                            setEmail("rl1@test.com");
                        }})))
                .andExpect(status().is(429))
                .andExpect(jsonPath("$.code").value(1008));
    }

    @Test
    void unregisteredEmailIsRateLimitedWithoutExposingExistence() throws Exception {
        for (int i = 0; i < 10; i++) {
            requestResetCode("ghost@test.com");
        }
        // 限流先于邮箱存在性判断：未注册邮箱同样计数，第 11 次同样 429
        mockMvc.perform(post("/api/auth/password/reset-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SendResetCodeRequest() {{
                            setEmail("ghost@test.com");
                        }})))
                .andExpect(status().is(429))
                .andExpect(jsonPath("$.code").value(1008));
    }
}

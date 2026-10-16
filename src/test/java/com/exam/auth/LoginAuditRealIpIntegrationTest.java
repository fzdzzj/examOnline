package com.exam.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.audit.entity.AuditLog;
import com.exam.audit.mapper.AuditLogMapper;
import com.exam.auth.dto.LoginRequest;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 登录审计真实客户端 IP 集成测试（harden-auth-edge-cases）：
 * 反代（Nginx TLS 终结）形态下 login 必须用 ClientIpResolver 解析 X-Forwarded-For 首项，
 * 登录失败审计记录的 ip_address 不得恒为代理内网地址。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoginAuditRealIpIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private AuditLogMapper auditLogMapper;

    @BeforeEach
    void flushRedis() {
        var conn = redis.getConnectionFactory().getConnection();
        try {
            conn.serverCommands().flushDb();
        } finally {
            conn.close();
        }
    }

    @Test
    void loginFailureAuditRecordsClientIpResolvedFromForwardedHeader() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest() {{
                            setUsername("stu_ip1");
                            setPassword("Pass1234");
                            setName("IP审计");
                            setEmail("stu_ip1@test.com");
                            setRoleType("STUDENT");
                        }})))
                .andExpect(status().isOk());

        // 模拟反代场景：X-Forwarded-For 携带真实客户端 IP
        mockMvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", "203.0.113.9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest() {{
                            setUsername("stu_ip1");
                            setPassword("wrong999");
                        }})))
                .andExpect(status().isUnauthorized());

        List<AuditLog> logs = auditLogMapper.selectList(Wrappers.<AuditLog>lambdaQuery()
                .eq(AuditLog::getUsername, "stu_ip1")
                .eq(AuditLog::getAction, AuditLog.ACTION_LOGIN)
                .orderByDesc(AuditLog::getId)
                .last("LIMIT 5"));
        assertTrue(logs.stream().anyMatch(l -> "203.0.113.9".equals(l.getIpAddress())),
                "登录失败审计必须记录解析后的真实客户端 IP（XFF 首项），实际=" + logs);
    }
}

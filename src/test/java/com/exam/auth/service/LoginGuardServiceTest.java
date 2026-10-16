package com.exam.auth.service;

import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.config.AuthProperties;
import com.exam.service.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LoginGuardService 单元测试（harden-auth-edge-cases）：
 * <ul>
 *   <li>三计数点（登录限流 / 失败计数 / 找回密码验证码限流）INCR+首置 EXPIRE 必须走 Lua 原子脚本，
 *       不得再出现「INCR 与 EXPIRE 两步分离」形态；</li>
 *   <li>recordFailure 必须把真实客户端 IP 透传给锁定审计（替代硬编码占位值）；</li>
 *   <li>checkCodeRateLimit 按 email+IP 维度固定窗口计数，超阈值 429。</li>
 * </ul>
 * Redis 以 Mockito mock 驱动：以内存计数表模拟 Lua 脚本的「INCR + 首置 TTL」语义，
 * 用 execute 调用断言替代真实 Redis。
 */
class LoginGuardServiceTest {

    private StringRedisTemplate redis;
    private AuditLogService auditLog;
    private AuthProperties props;
    private LoginGuardService guard;
    private Map<String, Long> counters;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        auditLog = mock(AuditLogService.class);
        props = new AuthProperties(); // 默认：perMinute=10 / maxFailures=5 / lockMinutes=15
        counters = new HashMap<>();
        // 模拟 Lua 脚本：按 key 自增并返回计数（首置 TTL 由 ARGV 传入，测试断言其值）
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    List<String> keys = inv.getArgument(1);
                    return counters.merge(keys.get(0), 1L, Long::sum);
                });
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
        guard = new LoginGuardService(redis, props, auditLog);
    }

    // ==================== 登录限流：Lua 原子化 ====================

    @Test
    void checkRateLimitIncrementsViaAtomicLuaScriptWithOneMinuteTtl() {
        guard.checkRateLimit("stu1", "1.2.3.4");

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<List> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(redis).execute(any(DefaultRedisScript.class), keysCaptor.capture(), argsCaptor.capture());
        assertEquals(List.of("auth:rl:stu1:1.2.3.4"), keysCaptor.getValue());
        assertEquals("60", argsCaptor.getValue()[0], "固定窗口必须 1 分钟（60s）");
        // 原子化后不得再走「opsForValue().increment + expire」两步
        verify(redis, never()).opsForValue();
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void checkRateLimitThrowsTooManyRequestsBeyondPerMinute() {
        for (int i = 0; i < 10; i++) {
            guard.checkRateLimit("stu2", "1.2.3.4");
        }
        BusinessException ex = assertThrows(BusinessException.class,
                () -> guard.checkRateLimit("stu2", "1.2.3.4"));
        assertEquals(ResponseCode.TOO_MANY_REQUESTS.getCode(), ex.getCode());
    }

    @Test
    void checkRateLimitKeysAreIsolatedByIpDimension() {
        for (int i = 0; i < 10; i++) {
            guard.checkRateLimit("stu3", "10.0.0.1");
        }
        // 同账号另一 IP 独立计数：10 次不共享阈值
        assertDoesNotThrow(() -> guard.checkRateLimit("stu3", "10.0.0.2"));
        // 原维度继续累计：第 11 次抛 429
        assertThrows(BusinessException.class, () -> guard.checkRateLimit("stu3", "10.0.0.1"));
    }

    // ==================== 失败计数 / 锁定审计：真实 IP ====================

    @Test
    void recordFailurePassesRealIpToLockAudit() {
        for (int i = 0; i < 5; i++) {
            guard.recordFailure("stu4", "203.0.113.9");
        }
        verify(auditLog).logAccountLock(eq("stu4"), eq("203.0.113.9"), anyString());
    }

    @Test
    void recordFailureBelowThresholdDoesNotLockNorAudit() {
        for (int i = 0; i < 4; i++) {
            guard.recordFailure("stu5", "203.0.113.9");
        }
        verify(auditLog, never()).logAccountLock(anyString(), anyString(), anyString());
        verify(redis, never()).opsForValue();
    }

    @Test
    void recordFailureLocksAndClearsCounterAtThreshold() {
        for (int i = 0; i < 5; i++) {
            guard.recordFailure("stu6", "203.0.113.9");
        }
        ValueOperations<String, String> valueOps = redis.opsForValue();
        verify(valueOps).set(eq("auth:lock:stu6"), eq("1"), any(Duration.class));
        verify(redis).delete("auth:fail:stu6");
    }

    // ==================== 找回密码验证码限流（email+IP 维度） ====================

    @Test
    void checkCodeRateLimitKeysByEmailAndIpWithOneMinuteWindow() {
        guard.checkCodeRateLimit("a@b.c", "198.51.100.7");

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<List> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(redis).execute(any(DefaultRedisScript.class), keysCaptor.capture(), argsCaptor.capture());
        assertEquals(List.of("auth:coderl:a@b.c:198.51.100.7"), keysCaptor.getValue());
        assertEquals("60", argsCaptor.getValue()[0], "固定窗口必须 1 分钟（60s）");
    }

    @Test
    void checkCodeRateLimitThrowsBeyondPerMinute() {
        for (int i = 0; i < 10; i++) {
            guard.checkCodeRateLimit("rl@test.com", "198.51.100.8");
        }
        BusinessException ex = assertThrows(BusinessException.class,
                () -> guard.checkCodeRateLimit("rl@test.com", "198.51.100.8"));
        assertEquals(ResponseCode.TOO_MANY_REQUESTS.getCode(), ex.getCode());
    }
}

package com.exam.auth.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.config.AuthProperties;
import com.exam.monitoring.metrics.BusinessMetrics;
import com.exam.service.AuditLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LoginGuardService 单元测试（harden-auth-edge-cases + harden-login-guard-redis-degradation）：
 * <ul>
 *   <li>三计数点（登录限流 / 失败计数 / 找回密码验证码限流）INCR+首置 EXPIRE 必须走 Lua 原子脚本，
 *       不得再出现「INCR 与 EXPIRE 两步分离」形态；</li>
 *   <li>recordFailure 必须把真实客户端 IP 透传给锁定审计（替代硬编码占位值）；</li>
 *   <li>checkCodeRateLimit 按 email+IP 维度固定窗口计数，超阈值 429；</li>
 *   <li>Redis 异常降级容错（harden-login-guard-redis-degradation，对齐 AuthenticationInterceptor
 *       先例）：五个 Redis 触点异常时降级放行/跳过该步 + ERROR 日志 + 降级计数三断言，
 *       正常拒绝/放行路径不因容错改造漂移。</li>
 * </ul>
 * Redis 以 Mockito mock 驱动：以内存计数表模拟 Lua 脚本的「INCR + 首置 TTL」语义，
 * 用 execute 调用断言替代真实 Redis。
 */
class LoginGuardServiceTest {

    private StringRedisTemplate redis;
    private AuditLogService auditLog;
    private AuthProperties props;
    private BusinessMetrics businessMetrics;
    private LoginGuardService guard;
    private Map<String, Long> counters;

    private Logger guardLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        auditLog = mock(AuditLogService.class);
        businessMetrics = mock(BusinessMetrics.class);
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
        guard = new LoginGuardService(redis, props, auditLog, businessMetrics);

        guardLogger = (Logger) LoggerFactory.getLogger(LoginGuardService.class);
        appender = new ListAppender<>();
        appender.start();
        guardLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        guardLogger.detachAppender(appender);
    }

    private List<String> errorMessages() {
        return appender.list.stream()
                .filter(e -> e.getLevel().toString().equals("ERROR"))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
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

    // ==================== Redis 异常降级容错（harden-login-guard-redis-degradation） ====================

    @Test
    @DisplayName("登录限流 Redis 异常：fail-open 放行 + ERROR 日志 + 降级计数（check=login_rate_limit）")
    void checkRateLimitRedisError_degradesPass_logsError_counts() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RuntimeException("redis connection refused"));

        assertDoesNotThrow(() -> guard.checkRateLimit("stu9", "1.2.3.4"),
                "登录限流 Redis 异常应 fail-open 放行，而不是异常裸穿 500");

        verify(businessMetrics).countAuthDegraded(eq(LoginGuardService.DEGRADED_LOGIN_RATE_LIMIT));
        assertTrue(errorMessages().stream().anyMatch(m -> m.contains("登录守卫降级") && m.contains("限流")),
                "降级必须落 ERROR 日志供告警观测，实际 ERROR 列表: " + errorMessages());
    }

    @Test
    @DisplayName("锁定检查 Redis 异常：降级放行 + ERROR 日志 + 降级计数（check=login_lock_check）")
    void checkLockedRedisError_degradesPass_logsError_counts() {
        when(redis.getExpire(anyString(), any(TimeUnit.class)))
                .thenThrow(new RuntimeException("redis timeout"));

        assertDoesNotThrow(() -> guard.checkLocked("stu9"),
                "锁定检查 Redis 异常应降级放行，而不是异常裸穿 500");

        verify(businessMetrics).countAuthDegraded(eq(LoginGuardService.DEGRADED_LOGIN_LOCK_CHECK));
        assertTrue(errorMessages().stream().anyMatch(m -> m.contains("登录守卫降级") && m.contains("锁定检查")),
                "降级必须落 ERROR 日志供告警观测，实际 ERROR 列表: " + errorMessages());
    }

    @Test
    @DisplayName("失败计数 Redis 异常：跳过计数继续失败流程 + ERROR 日志 + 降级计数，审计不动（check=login_fail_record）")
    void recordFailureRedisError_skipsCounting_logsError_counts() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RuntimeException("redis down"));

        assertDoesNotThrow(() -> guard.recordFailure("stu9", "203.0.113.9"),
                "失败计数 Redis 异常应跳过计数（锁定保护暂缺）继续既有失败流程，而不是异常裸穿 500");

        verify(businessMetrics).countAuthDegraded(eq(LoginGuardService.DEGRADED_LOGIN_FAIL_RECORD));
        verify(auditLog, never()).logAccountLock(anyString(), anyString(), anyString());
        assertTrue(errorMessages().stream().anyMatch(m -> m.contains("登录守卫降级") && m.contains("失败计数")),
                "降级必须落 ERROR 日志供告警观测，实际 ERROR 列表: " + errorMessages());
    }

    @Test
    @DisplayName("锁定写入 Redis 异常：放弃本次锁定 + ERROR 日志 + 降级计数，审计不动（check=login_lock_write）")
    void recordFailureLockWriteRedisError_givesUpLock_logsError_counts() {
        // 计数臂正常递增至阈值，锁定写入（opsForValue().set）时 Redis 异常
        ValueOperations<String, String> failingOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(failingOps);
        doThrow(new RuntimeException("redis down")).when(failingOps)
                .set(anyString(), anyString(), any(Duration.class));

        assertDoesNotThrow(() -> {
                    for (int i = 0; i < 5; i++) {
                        guard.recordFailure("stu9", "203.0.113.9");
                    }
                },
                "锁定写入 Redis 异常应放弃本次锁定（计数键未清理，下次失败重试自愈），而不是异常裸穿 500");

        verify(businessMetrics).countAuthDegraded(eq(LoginGuardService.DEGRADED_LOGIN_LOCK_WRITE));
        verify(auditLog, never()).logAccountLock(anyString(), anyString(), anyString());
        assertTrue(errorMessages().stream().anyMatch(m -> m.contains("登录守卫降级") && m.contains("锁定写入")),
                "降级必须落 ERROR 日志供告警观测，实际 ERROR 列表: " + errorMessages());
    }

    @Test
    @DisplayName("失败计数清理 Redis 异常：跳过清理（计数键 TTL 自愈）+ ERROR 日志 + 降级计数（check=login_fail_clear）")
    void clearFailuresRedisError_skipsDeletion_logsError_counts() {
        doThrow(new RuntimeException("redis down")).when(redis).delete("auth:fail:stu9");

        assertDoesNotThrow(() -> guard.clearFailures("stu9"),
                "清理 Redis 异常应跳过删除（有效凭据登录不得被挡在最后一步），而不是异常裸穿 500");

        verify(businessMetrics).countAuthDegraded(eq(LoginGuardService.DEGRADED_LOGIN_FAIL_CLEAR));
        assertTrue(errorMessages().stream().anyMatch(m -> m.contains("登录守卫降级") && m.contains("清理")),
                "降级必须落 ERROR 日志供告警观测，实际 ERROR 列表: " + errorMessages());
    }

    @Test
    @DisplayName("验证码限流 Redis 异常：fail-open 放行 + ERROR 日志 + 降级计数（check=code_rate_limit）")
    void checkCodeRateLimitRedisError_degradesPass_logsError_counts() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RuntimeException("redis down"));

        assertDoesNotThrow(() -> guard.checkCodeRateLimit("u@x.com", "198.51.100.9"),
                "验证码限流 Redis 异常应 fail-open 放行，而不是异常裸穿 500");

        verify(businessMetrics).countAuthDegraded(eq(LoginGuardService.DEGRADED_CODE_RATE_LIMIT));
        assertTrue(errorMessages().stream().anyMatch(m -> m.contains("登录守卫降级") && m.contains("验证码限流")),
                "降级必须落 ERROR 日志供告警观测，实际 ERROR 列表: " + errorMessages());
    }

    @Test
    @DisplayName("回归：Redis 可读时锁定命中的账号仍被拒绝（降级不得吞掉正常拒绝路径）")
    void checkLockedAccountLockedStillRejectedWhenRedisReadable() {
        when(redis.getExpire(eq("auth:lock:stu10"), any(TimeUnit.class))).thenReturn(300L);

        BusinessException ex = assertThrows(BusinessException.class, () -> guard.checkLocked("stu10"));
        assertEquals(ResponseCode.ACCOUNT_LOCKED.getCode(), ex.getCode());
        verify(businessMetrics, never()).countAuthDegraded(anyString());
    }

    @Test
    @DisplayName("回归：Redis 可读且无锁定时仍放行（正常放行路径零漂移）")
    void checkLockedNoLockStillPassesWhenRedisReadable() {
        when(redis.getExpire(anyString(), any(TimeUnit.class))).thenReturn(-2L);

        assertDoesNotThrow(() -> guard.checkLocked("stu11"));
        verify(businessMetrics, never()).countAuthDegraded(anyString());
    }
}

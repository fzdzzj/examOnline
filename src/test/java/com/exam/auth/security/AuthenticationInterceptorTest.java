package com.exam.auth.security;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.exam.auth.service.JwtUtil;
import com.exam.auth.service.TokenStoreService;
import com.exam.monitoring.metrics.BusinessMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 鉴权拦截器 Redis 降级容错单测（harden-auth-interceptor-redis-degradation，spec-delta
 * 「Redis 异常时放行」Scenario）：
 *
 * <p>不启动 Spring，直接 mock 四依赖构造拦截器，验证：
 * <ol>
 *   <li>黑名单查询 Redis 异常 → 降级放行（返回 true，请求继续）+ ERROR 日志 + 降级计数递增；</li>
 *   <li>会话版本查询 Redis 异常 → 同上；</li>
 *   <li>回归：黑名单命中 / 会话版本不匹配 / 无 Authorization 头仍 401 拒绝（降级不得吞掉正常拒绝路径）。</li>
 * </ol>
 *
 * <p>变异对应：还原无容错旧形态（Redis 异常直接上抛）时，1/2 两用例确定性失败
 * （preHandle 抛异常而非放行）——先红后绿留证见 evidence/。
 */
class AuthenticationInterceptorTest {

    private static final String TOKEN = "token-abc";
    private static final String AUTH_HEADER = "Bearer " + TOKEN;

    private JwtUtil jwtUtil;
    private TokenStoreService tokenStore;
    private BusinessMetrics businessMetrics;
    private AuthenticationInterceptor interceptor;
    private HttpServletRequest request;
    private HttpServletResponse response;

    private Logger interceptorLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() throws Exception {
        jwtUtil = mock(JwtUtil.class);
        tokenStore = mock(TokenStoreService.class);
        businessMetrics = mock(BusinessMetrics.class);
        interceptor = new AuthenticationInterceptor(jwtUtil, tokenStore, new ObjectMapper(), businessMetrics);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        LoginUser user = new LoginUser();
        user.setId(7L);
        user.setUsername("tea_0001");
        user.setJti("jti-abc");
        user.setSessionVersion(2L);
        when(request.getHeader("Authorization")).thenReturn(AUTH_HEADER);
        when(jwtUtil.parseAccessToken(TOKEN)).thenReturn(user);
        // 默认放行基线：不在黑名单、会话版本与 token 一致
        when(tokenStore.isBlacklisted(anyString())).thenReturn(false);
        when(tokenStore.getSessionVersion(any())).thenReturn(2L);

        interceptorLogger = (Logger) LoggerFactory.getLogger(AuthenticationInterceptor.class);
        appender = new ListAppender<>();
        appender.start();
        interceptorLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        interceptorLogger.detachAppender(appender);
        SecurityUtil.clear();
    }

    private List<String> errorMessages() {
        return appender.list.stream()
                .filter(e -> e.getLevel().toString().equals("ERROR"))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Test
    @DisplayName("Redis 黑名单查询异常：降级放行 + ERROR 日志 + 降级计数递增（check=blacklist）")
    void blacklistRedisError_degradesAllow_logsError_counts() throws Exception {
        when(tokenStore.isBlacklisted(anyString())).thenThrow(new RuntimeException("redis connection refused"));

        assertTrue(interceptor.preHandle(request, response, new Object()),
                "黑名单查询 Redis 异常时应降级放行，而不是异常裸穿 500");

        verify(businessMetrics).countAuthDegraded(eq(AuthenticationInterceptor.DEGRADED_CHECK_BLACKLIST));
        assertTrue(errorMessages().stream().anyMatch(m -> m.contains("鉴权拦截器降级") && m.contains("黑名单")),
                "降级必须落 ERROR 日志供告警观测，实际 ERROR 列表: " + errorMessages());
    }

    @Test
    @DisplayName("Redis 会话版本查询异常：降级放行 + ERROR 日志 + 降级计数递增（check=session_version）")
    void sessionVersionRedisError_degradesAllow_logsError_counts() throws Exception {
        when(tokenStore.getSessionVersion(any())).thenThrow(new RuntimeException("redis timeout"));

        assertTrue(interceptor.preHandle(request, response, new Object()),
                "会话版本查询 Redis 异常时应降级放行，而不是异常裸穿 500");

        verify(businessMetrics).countAuthDegraded(eq(AuthenticationInterceptor.DEGRADED_CHECK_SESSION_VERSION));
        assertTrue(errorMessages().stream().anyMatch(m -> m.contains("鉴权拦截器降级") && m.contains("会话版本")),
                "降级必须落 ERROR 日志供告警观测，实际 ERROR 列表: " + errorMessages());
    }

    @Test
    @DisplayName("回归：黑名单命中仍 401 拒绝（降级不得吞掉正常拒绝路径）")
    void blacklistHit_stillRejects() throws Exception {
        when(tokenStore.isBlacklisted(anyString())).thenReturn(true);

        assertFalse(interceptor.preHandle(request, response, new Object()));
        verify(response).setStatus(401);
    }

    @Test
    @DisplayName("回归：会话版本不匹配仍 401 拒绝")
    void sessionVersionMismatch_stillRejects() throws Exception {
        when(tokenStore.getSessionVersion(any())).thenReturn(3L);

        assertFalse(interceptor.preHandle(request, response, new Object()));
        verify(response).setStatus(401);
    }

    @Test
    @DisplayName("回归：无 Authorization 头仍 401 拒绝")
    void missingHeader_stillRejects() throws Exception {
        when(request.getHeader("Authorization")).thenReturn(null);

        assertFalse(interceptor.preHandle(request, response, new Object()));
        verify(response).setStatus(401);
    }
}

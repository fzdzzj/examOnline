package com.exam.auth.security;

import com.exam.auth.service.JwtUtil;
import com.exam.auth.service.TokenStoreService;
import com.exam.common.ApiResponse;
import com.exam.common.RequestIdFilter;
import com.exam.common.ResponseCode;
import com.exam.monitoring.metrics.BusinessMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * 认证拦截器（所有受保护接口入口）：
 * <ol>
 *   <li>解析 Authorization: Bearer &lt;Access Token&gt;；</li>
 *   <li>校验签名/有效期/类型（JwtUtil）；</li>
 *   <li>查 Redis 黑名单（登出/踢人/改密码写入的 jti）；</li>
 *   <li>校验会话版本（改密码/踢人/Refresh 复用后全端下线）；</li>
 *   <li>通过后写入 {@link SecurityUtil}（ThreadLocal），请求结束清除。</li>
 * </ol>
 * JWT 校验失败返回 401，不泄漏具体原因（防探测）。
 *
 * <p><b>为什么 Redis 检查（黑名单/会话版本）异常要降级放行（harden-auth-interceptor-redis-degradation）：</b>
 * 令牌签名校验（第 2 步）才是鉴权正确性的底座，黑名单与会话版本只是<b>主动失效的加速器</b>。
 * Redis 故障时若异常裸穿，携带有效 JWT 的全部请求一律 500——全 API 停摆；降级放行则退化为
 * 「吊销延迟生效」：黑名单项 TTL = Access Token 剩余寿命（≤30min，access-expire-minutes）自限，
 * 已吊销 token 的复活半径不超过其自然剩余寿命，与在案观察项「Redis 清空后已吊销 Access
 * ≤30min 复活」同半径 by design（黑名单本就是 TTL 自限设计）；会话版本检查失败 = 改密/踢人
 * 的全端下线暂不生效，半径同性质同上界——替代口径（全 API 500 停摆）更糟。降级对齐限流
 * fail-open 先例（{@link com.exam.common.ratelimit.RateLimitInterceptor}）：ERROR 日志 + 降级
 * 计数指标（exam.auth.degraded，告警联动 AuthDegraded），不新增第二套降级哲学。JWT 校验
 * 不降级——校验失败仍 401。
 */
@Slf4j
@Component
public class AuthenticationInterceptor implements HandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    /** 降级计数 tag：黑名单吊销检查（BusinessMetrics.countAuthDegraded，代码常量有界） */
    static final String DEGRADED_CHECK_BLACKLIST = "blacklist";
    /** 降级计数 tag：会话版本检查 */
    static final String DEGRADED_CHECK_SESSION_VERSION = "session_version";

    private final JwtUtil jwtUtil;
    private final TokenStoreService tokenStore;
    private final ObjectMapper objectMapper;
    private final BusinessMetrics businessMetrics;

    public AuthenticationInterceptor(JwtUtil jwtUtil, TokenStoreService tokenStore, ObjectMapper objectMapper,
                                     BusinessMetrics businessMetrics) {
        this.jwtUtil = jwtUtil;
        this.tokenStore = tokenStore;
        this.objectMapper = objectMapper;
        this.businessMetrics = businessMetrics;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return reject(response);
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        LoginUser user;
        try {
            user = jwtUtil.parseAccessToken(token);
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Access Token 校验失败: {}", e.getMessage());
            return reject(response);
        }
        // 黑名单：登出/踢人/改密码已将该 jti 置为失效。
        // try 范围收紧到只包 Redis 调用（对齐 RateLimitInterceptor）：查询结果 false=正常放行路径，
        // 绝不能被降级分支吞掉；只有 Redis 基础设施异常才走降级放行。
        boolean blacklisted;
        try {
            blacklisted = tokenStore.isBlacklisted(user.getJti());
        } catch (Exception e) {
            // Redis 基础设施故障而非吊销命中：降级放行（吊销延迟生效，半径受黑名单 TTL 自限），
            // 先记日志/埋点再放行，保证降级对告警可观测
            log.error("鉴权拦截器降级：Redis 黑名单查询失败，userId={}，jti={}，异常={}，requestId={}",
                    user.getId(), user.getJti(), e.getMessage(), MDC.get(RequestIdFilter.MDC_KEY), e);
            businessMetrics.countAuthDegraded(DEGRADED_CHECK_BLACKLIST);
            blacklisted = false;
        }
        if (blacklisted) {
            log.info("黑名单命中拒绝访问, userId={}, jti={}", user.getId(), user.getJti());
            return reject(response);
        }
        // 会话版本：改密码/踢人/Refresh 复用后版本递增，旧 Token 全部失效（降级口径同黑名单）
        long currentVersion;
        try {
            currentVersion = tokenStore.getSessionVersion(user.getId());
        } catch (Exception e) {
            // 降级放行：视为版本未 bump（改密/踢人的全端下线暂不生效，半径同黑名单自限性质）
            log.error("鉴权拦截器降级：Redis 会话版本查询失败，userId={}，异常={}，requestId={}",
                    user.getId(), e.getMessage(), MDC.get(RequestIdFilter.MDC_KEY), e);
            businessMetrics.countAuthDegraded(DEGRADED_CHECK_SESSION_VERSION);
            currentVersion = user.getSessionVersion();
        }
        if (user.getSessionVersion() < currentVersion) {
            log.info("会话版本不匹配拒绝访问, userId={}, tokenSv={}, currentSv={}",
                    user.getId(), user.getSessionVersion(), currentVersion);
            return reject(response);
        }
        SecurityUtil.set(user);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        SecurityUtil.clear();
    }

    /** 统一 401 响应（ApiResponse 格式）。 */
    private boolean reject(HttpServletResponse response) throws Exception {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.error(ResponseCode.TOKEN_INVALID)));
        return false;
    }
}

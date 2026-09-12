package com.exam.auth.security;

import com.exam.auth.service.JwtUtil;
import com.exam.auth.service.TokenStoreService;
import com.exam.common.ApiResponse;
import com.exam.common.ResponseCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
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
 * 任一环节失败返回 401，不泄漏具体原因（防探测）。
 */
@Slf4j
@Component
public class AuthenticationInterceptor implements HandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;
    private final TokenStoreService tokenStore;
    private final ObjectMapper objectMapper;

    public AuthenticationInterceptor(JwtUtil jwtUtil, TokenStoreService tokenStore, ObjectMapper objectMapper) {
        this.jwtUtil = jwtUtil;
        this.tokenStore = tokenStore;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return reject(response);
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        try {
            LoginUser user = jwtUtil.parseAccessToken(token);
            // 黑名单：登出/踢人/改密码已将该 jti 置为失效
            if (tokenStore.isBlacklisted(user.getJti())) {
                log.info("黑名单命中拒绝访问, userId={}, jti={}", user.getId(), user.getJti());
                return reject(response);
            }
            // 会话版本：改密码/踢人/Refresh 复用后版本递增，旧 Token 全部失效
            long currentVersion = tokenStore.getSessionVersion(user.getId());
            if (user.getSessionVersion() < currentVersion) {
                log.info("会话版本不匹配拒绝访问, userId={}, tokenSv={}, currentSv={}",
                        user.getId(), user.getSessionVersion(), currentVersion);
                return reject(response);
            }
            SecurityUtil.set(user);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Access Token 校验失败: {}", e.getMessage());
            return reject(response);
        }
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

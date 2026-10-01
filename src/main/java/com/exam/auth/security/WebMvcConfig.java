package com.exam.auth.security;

import com.exam.auth.service.JwtUtil;
import com.exam.auth.service.TokenStoreService;
import com.exam.common.ratelimit.RateLimitInterceptor;
import com.exam.common.ratelimit.RedisTokenBucket;
import com.exam.monitoring.metrics.BusinessMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：注册 {@link AuthenticationInterceptor}（鉴权）、{@link RateLimitInterceptor}（限流），
 * 均拦截 /api/**；公开端点（登录/注册/刷新/找回密码/健康检查）放行。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final JwtUtil jwtUtil;
    private final TokenStoreService tokenStore;
    private final ObjectMapper objectMapper;
    private final RedisTokenBucket redisTokenBucket;
    private final BusinessMetrics businessMetrics;
    private final boolean failOpen;

    public WebMvcConfig(JwtUtil jwtUtil, TokenStoreService tokenStore, ObjectMapper objectMapper,
                        RedisTokenBucket redisTokenBucket, BusinessMetrics businessMetrics,
                        @Value("${exam.ratelimit.fail-open:true}") boolean failOpen) {
        this.jwtUtil = jwtUtil;
        this.tokenStore = tokenStore;
        this.objectMapper = objectMapper;
        this.redisTokenBucket = redisTokenBucket;
        this.businessMetrics = businessMetrics;
        this.failOpen = failOpen;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthenticationInterceptor(jwtUtil, tokenStore, objectMapper))
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        // 公开端点：无需 Access Token
                        "/api/auth/login",
                        "/api/auth/register",
                        "/api/auth/refresh",
                        "/api/auth/password/reset-code",
                        "/api/auth/password/reset",
                        "/actuator/**"
                );
        // 限流拦截器排在鉴权之后注册（先鉴权再限流）：拦截 /api/**，只对有 @RateLimit 的核心接口生效
        registry.addInterceptor(new RateLimitInterceptor(redisTokenBucket, businessMetrics, failOpen))
                .addPathPatterns("/api/**");
    }
}

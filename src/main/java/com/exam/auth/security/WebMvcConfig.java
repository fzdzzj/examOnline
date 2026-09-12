package com.exam.auth.security;

import com.exam.auth.service.JwtUtil;
import com.exam.auth.service.TokenStoreService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：注册 {@link AuthenticationInterceptor}，拦截 /api/**；
 * 公开端点（登录/注册/刷新/找回密码/健康检查）放行。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final JwtUtil jwtUtil;
    private final TokenStoreService tokenStore;
    private final ObjectMapper objectMapper;

    public WebMvcConfig(JwtUtil jwtUtil, TokenStoreService tokenStore, ObjectMapper objectMapper) {
        this.jwtUtil = jwtUtil;
        this.tokenStore = tokenStore;
        this.objectMapper = objectMapper;
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
    }
}

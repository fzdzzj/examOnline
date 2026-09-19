package com.exam.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 认证相关配置（前缀 exam.auth），集中管理：
 * - jwt：签名密钥与双 Token 有效期（Access 30min / Refresh 7d）
 * - admin：管理员初始化账号（AdminInitializer 使用）
 * - lock：登录锁定（连续失败次数与锁定分钟数）
 * - rate-limit：登录接口限流（次/分钟/账号+IP）
 * - reset-code：找回密码验证码有效期
 */
@Data
@Component
@ConfigurationProperties(prefix = "exam.auth")
public class AuthProperties {

    private Jwt jwt = new Jwt();
    private Admin admin = new Admin();
    private Lock lock = new Lock();
    private RateLimit rateLimit = new RateLimit();
    private ResetCode resetCode = new ResetCode();

    /** JWT 配置 */
    @Data
    public static class Jwt {
        /** HS256 签名密钥（生产必须通过环境变量 JWT_SECRET 注入，长度 >= 32 字节） */
        private String secret;
        /** Access Token 有效期（分钟），默认 30 */
        private long accessExpireMinutes = 30;
        /** Refresh Token 有效期（天），默认 7 */
        private long refreshExpireDays = 7;
    }

    /** 管理员初始化账号 */
    @Data
    public static class Admin {
        private String username = "admin";
        private String password = "admin123";
    }

    /** 登录锁定 */
    @Data
    public static class Lock {
        /** 连续失败阈值 */
        private int maxFailures = 5;
        /** 锁定分钟数 */
        private int lockMinutes = 15;
    }

    /** 登录接口限流 */
    @Data
    public static class RateLimit {
        /** 每分钟允许的登录尝试次数（按 账号+IP 维度） */
        private int perMinute = 10;
    }

    /** 找回密码验证码 */
    @Data
    public static class ResetCode {
        /** 验证码有效分钟数 */
        private int ttlMinutes = 5;
        /** 验证码长度 */
        private int length = 6;
    }
}

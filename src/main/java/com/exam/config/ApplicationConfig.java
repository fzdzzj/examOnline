package com.exam.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 应用配置校验器：启动时强制校验关键安全配置
 */
@Slf4j
@Component
public class ApplicationConfig implements ApplicationRunner {

    private final AuthProperties authProperties;

    public ApplicationConfig(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        validateJwtSecret();
        log.info("Application configuration validated successfully");
    }

    /**
     * 校验 JWT 密钥：必须通过环境变量注入且长度≥32
     */
    private void validateJwtSecret() {
        String secret = authProperties.getJwt().getSecret();
        
        if (secret == null || secret.isEmpty()) {
            throw new IllegalStateException(
                "JWT_SECRET environment variable is required and must be configured. " +
                "Please set the JWT_SECRET environment variable with a value of at least 32 characters."
            );
        }
        
        if (secret.length() < 32) {
            throw new IllegalStateException(
                "JWT_SECRET must be at least 32 characters long. Current length: " + secret.length()
            );
        }
        
        log.info("JWT_SECRET validated successfully (length: {})", secret.length());
    }
}

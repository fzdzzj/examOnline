package com.exam.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Collections;

/**
 * API 限流配置（add-api-rate-limiting 提案）：
 * 使用 Token Bucket 算法实现分布式限流，支持降级为本地限流。
 */
@Configuration
@ConditionalOnProperty(name = "rate-limiting.enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitConfig {

    @Value("${rate-limiting.qps:100}")
    private int defaultQps;

    @Value("${rate-limiting.burst:150}")
    private int defaultBurst;

    @Value("${rate-limiting.key-prefix:api:ratelimit}")
    private String keyPrefix;

    /**
     * 必须用容器托管的 StringRedisTemplate，不能自己 new RedisTemplate()：
     * <ol>
     *   <li>手工 new 出的模板不会走 {@code afterPropertiesSet()}，其内部 scriptExecutor 为 null，
     *       第一次 EVAL 就 NPE，全局拦截器会把每个请求变成 500；</li>
     *   <li>手工 new 的模板默认 JDK 序列化，Lua 里 {@code tonumber(ARGV[1])} 拿到的是序列化字节而非数字。</li>
     * </ol>
     * 与 {@code RedisTokenBucket} 保持同一口径。
     */
    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    public RateLimitConfig(StringRedisTemplate redisTemplate, MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Token Bucket Lua 脚本：原子性检查 + 扣减 token
     * KEYS[1]: rate limit key
     * ARGV[1]: current timestamp (seconds)
     * ARGV[2]: max tokens (burst)
     * ARGV[3]: tokens per second (qps)
     * ARGV[4]: current time tokens needed
     * Returns: 1 if allowed, 0 if rate limited
     */
    private static final String RATE_LIMIT_SCRIPT =
            "local key = KEYS[1]\n" +
            "local now = tonumber(ARGV[1])\n" +
            "local capacity = tonumber(ARGV[2])\n" +
            "local rate = tonumber(ARGV[3])\n" +
            "local requested = tonumber(ARGV[4])\n" +
            "\n" +
            "local last_time = redis.call('HGET', key, 'last_time')\n" +
            "local tokens = redis.call('HGET', key, 'tokens')\n" +
            "\n" +
            // Redis 把「字段不存在」的 HGET 结果转成 Lua 的 false，而不是 nil：
            // 用 == nil 判断会让首次请求走 else 分支，拿 false 做算术直接报
            // "attempt to perform arithmetic on local 'last_time' (a boolean value)"。
            "if not last_time or not tokens then\n" +
            "    last_time = now\n" +
            "    tokens = capacity\n" +
            "else\n" +
            "    local elapsed = now - tonumber(last_time)\n" +
            "    local added = elapsed * rate\n" +
            "    tokens = math.min(capacity, tonumber(tokens) + added)\n" +
            "end\n" +
            "\n" +
            "if tokens >= requested then\n" +
            "    tokens = tokens - requested\n" +
            "    redis.call('HSET', key, 'last_time', now)\n" +
            "    redis.call('HSET', key, 'tokens', tokens)\n" +
            "    redis.call('EXPIRE', key, 3600)\n" +
            "    return 1\n" +
            "else\n" +
            "    return 0\n" +
            "end";

    @Bean
    public RedisScript<Long> rateLimitScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(RATE_LIMIT_SCRIPT);
        script.setResultType(Long.class);
        return script;
    }

    /**
     * 检查请求是否被限流
     * @param clientId 客户端标识（IP + 接口路径）
     * @return true 如果允许通过，false 如果被限流
     */
    public boolean isAllowed(String clientId) {
        long now = System.currentTimeMillis() / 1000;
        Long result = redisTemplate.execute(
                rateLimitScript(),
                Collections.singletonList(keyPrefix + ":" + clientId),
                String.valueOf(now),
                String.valueOf(defaultBurst),
                String.valueOf(defaultQps),
                "1"
        );

        if (result == null || result == 0) {
            // 限流 - 不记录详细 client 标签，避免指标爆炸
            meterRegistry.counter("http_request_rate_limited_total").increment();
            return false;
        }

        return true;
    }

    /**
     * 限流拦截器 Bean
     */
    @Bean
    public org.springframework.web.servlet.HandlerInterceptor rateLimitHandlerInterceptor() {
        return new org.springframework.web.servlet.HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                String clientId = getClientId(request);
                
                if (!isAllowed(clientId)) {
                    meterRegistry.counter("http_request_rate_limited_total").increment();
                    
                    try {
                        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                        response.setHeader("Retry-After", "60");
                        response.setContentType("application/json");
                        response.getWriter().write("{\"code\":\"RATE_LIMIT_EXCEEDED\",\"message\":\"请求过于频繁，请稍后重试\",\"retryAfter\":60}");
                    } catch (Exception e) {
                        // 忽略写入错误
                    }
                    
                    return false;
                }
                
                return true;
            }

            private String getClientId(HttpServletRequest request) {
                String ip = request.getHeader("X-Forwarded-For");
                if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
                    ip = request.getRemoteAddr();
                }
                String path = request.getRequestURI();
                return ip + ":" + path;
            }
        };
    }

    /**
     * 注册限流拦截器
     */
    @Bean
    public WebMvcConfigurer webMvcConfigurer(org.springframework.web.servlet.HandlerInterceptor rateLimitHandlerInterceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(rateLimitHandlerInterceptor)
                        .addPathPatterns("/api/**")
                        .excludePathPatterns("/actuator/**", "/health");
            }
        };
    }
}

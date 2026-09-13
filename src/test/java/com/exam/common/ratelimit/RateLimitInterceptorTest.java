package com.exam.common.ratelimit;

import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 限流拦截器测试（add-slow-sql-and-rate-limit task4）：不启动 Spring，mock {@link RedisTokenBucket}，
 * 用真实带 {@link RateLimit} 注解的方法构造 {@link HandlerMethod}，验证：
 * <ol>
 *   <li>令牌桶判定为超限（false）→ 抛 {@link BusinessException}，code=1008、httpStatus=429、提示匹配；</li>
 *   <li>令牌桶判定放行（true）→ preHandle 返回 true 不抛异常；</li>
 *   <li>非 HandlerMethod（静态资源等）或未打注解的方法 → 直接放行不进入限流。</li>
 * </ol>
 */
class RateLimitInterceptorTest {

    /** 测试专用限流端点：capacity=2、qps=1，便于断言；模拟真实 Controller 方法。 */
    static class AnnotatedController {
        @RateLimit(qps = 1, capacity = 2, key = "ut-endpoint")
        public Object endpoint() {
            return "ok";
        }
    }

    private HandlerMethod handler(Class<?> clazz, String method) throws Exception {
        return new HandlerMethod(clazz.getDeclaredConstructor().newInstance(), clazz.getMethod(method));
    }

    private HandlerMethod annotatedHandler() throws Exception {
        return handler(AnnotatedController.class, "endpoint");
    }

    private static final class UnannotatedController {
        public Object plain() {
            return "plain";
        }
    }

    @Test
    @DisplayName("超限：令牌桶返回 false → 抛出 429（code=1008）业务异常")
    void throws429WhenLimited() throws Exception {
        RedisTokenBucket bucket = mock(RedisTokenBucket.class);
        when(bucket.tryAcquire(anyString(), anyInt(), anyDouble())).thenReturn(false);
        RateLimitInterceptor interceptor = new RateLimitInterceptor(bucket);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> interceptor.preHandle(null, null, annotatedHandler()));
        assertEquals(ResponseCode.TOO_MANY_REQUESTS.getCode(), ex.getCode(), "业务码应为 1008");
        assertEquals(ResponseCode.TOO_MANY_REQUESTS.getHttpStatus(), 429, "HTTP 状态应为 429");
        assertTrue(ex.getMessage().contains("频繁"), "提示应说明请求过于频繁");
    }

    @Test
    @DisplayName("放行：令牌桶返回 true → preHandle 返回 true 不抛异常")
    void allowsWhenPassed() throws Exception {
        RedisTokenBucket bucket = mock(RedisTokenBucket.class);
        when(bucket.tryAcquire(anyString(), anyInt(), anyDouble())).thenReturn(true);
        RateLimitInterceptor interceptor = new RateLimitInterceptor(bucket);

        assertTrue(interceptor.preHandle(null, null, annotatedHandler()),
                "令牌足够时应放行（返回 true）");
    }

    @Test
    @DisplayName("未打注解的方法或非 HandlerMethod → 不进入限流直接放行")
    void skipsUnannotated() throws Exception {
        RedisTokenBucket bucket = mock(RedisTokenBucket.class);
        RateLimitInterceptor interceptor = new RateLimitInterceptor(bucket);

        HandlerMethod plain = handler(UnannotatedController.class, "plain");
        assertTrue(interceptor.preHandle(null, null, plain), "未打 @RateLimit 的方法应放行");
        // 非 HandlerMethod（如静态资源）也应放行
        assertTrue(interceptor.preHandle(null, null, new Object()), "非 HandlerMethod 应放行");
    }
}
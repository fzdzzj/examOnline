package com.exam.common.ratelimit;

import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 接口限流拦截器（add-slow-sql-and-rate-limit task2）：在 {@code preHandle} 读方法上挂的
 * {@link RateLimit} 注解，对打了注解的 Controller 方法按「接口」维度跑 Redis 令牌桶判定，
 * 超限抛 {@code BusinessException(ResponseCode.TOO_MANY_REQUESTS)}（429，由全局异常处理器转 HTTP）。
 *
 * <p><b>为什么限流拦截器注册在鉴权（AuthenticationInterceptor）之后：</b>顺序是<b>先鉴权再限流</b>。
 * 其一，先确认请求者身份、再决定是否限流，避免匿名/伪造请求绕过鉴权直接打到限流逻辑换取信息；
 * 其二，把"该不该限流"建立在认证通过的受信访问上，恶意未授权流量在鉴权层即被 401 拦截，
 * 限流只聚焦保护真正的核心接口峰值，职责互不重叠。因此本拦截器排在鉴权之后注册。
 *
 * <p><b>为什么按接口维度（而非用户维度）：</b>同 {@link RedisTokenBucket} 所述——限流是保护整条
 * 后端链路（DB/MQ）平滑过载，应对 5000 人开考尖峰，而非惩罚单个用户。
 *
 * @see RedisTokenBucket
 */
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RedisTokenBucket tokenBucket;

    public RateLimitInterceptor(RedisTokenBucket tokenBucket) {
        this.tokenBucket = tokenBucket;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            // 静态资源等非 HandlerMethod 场景不需要限流
            return true;
        }
        RateLimit rateLimit = handlerMethod.getMethodAnnotation(RateLimit.class);
        if (rateLimit == null) {
            // 未打 @RateLimit 的接口不参与限流（只对核心接口生效，改为注解后即收敛到有限热点）
            return true;
        }
        String key = RedisTokenBucket.key(resolveInterfaceId(handlerMethod, rateLimit));
        boolean allowed = tokenBucket.tryAcquire(key, rateLimit.capacity(), rateLimit.qps());
        if (!allowed) {
            // 桶空：超限，抛业务异常 → GlobalExceptionHandler 统一转 429（ResponseCode.TOO_MANY_REQUESTS）
            throw new BusinessException(ResponseCode.TOO_MANY_REQUESTS);
        }
        return true;
    }

    /**
     * 接口标识优先用注解自定义 {@code key()}，否则取「Controller 类名.方法名」。
     * 同一接口的多次调用稳定映射到同一把 Redis 桶（多实例共享一把桶 = 分布式一致，见 proposal）。
     */
    static String resolveInterfaceId(HandlerMethod handlerMethod, RateLimit rateLimit) {
        if (rateLimit.key() != null && !rateLimit.key().isBlank()) {
            return rateLimit.key();
        }
        return handlerMethod.getBeanType().getSimpleName() + "." + handlerMethod.getMethod().getName();
    }
}
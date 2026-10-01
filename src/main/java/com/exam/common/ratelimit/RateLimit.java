package com.exam.common.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口限流注解（add-slow-sql-and-rate-limit task2）：挂在 Controller 方法上声明式限流，
 * 由 {@link RateLimitInterceptor} 读取，按「接口」维度跑 Redis 令牌桶判定，超限抛 429。
 *
 * <p>两参数的语义（进 RedisTokenBucket 的 Lua 时即成为桶的容量与补充速率）：
 * <ul>
 *   <li>{@code capacity} = 桶容量，即接口容忍的瞬时突发量（可一次性打满的令牌数，不欠账）；</li>
 *   <li>{@code qps} = 每秒补充的令牌数，即接口稳定放行的平均速率（超过部分被拒绝/被整流）。</li>
 * </ul>
 * 建议 capacity ≥ 单场景突发峰值、qps ≥ 该场景的稳态均值，二者共同构成"突发吸收 + 稳态收敛"的
 * 双层保护（抗 5000 人开考尖峰同时防止恒压慢速打垮后端）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** 每秒放行数（令牌补充速率）；默认 100/s，业务接口显式声明各自阈值 */
    double qps() default 100;

    /** 桶容量（可一次性连发的最大请求数）；默认 2000，业务接口显式声明各自阈值 */
    int capacity() default 2000;

    /** 可选自定义 key；缺省按「Controller 类名.方法名」生成接口标识（见 RateLimitInterceptor） */
    String key() default "";
}
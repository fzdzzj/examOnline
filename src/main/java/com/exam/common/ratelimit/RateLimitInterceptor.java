package com.exam.common.ratelimit;

import com.exam.common.BusinessException;
import com.exam.common.RequestIdFilter;
import com.exam.common.ResponseCode;
import com.exam.monitoring.metrics.BusinessMetrics;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
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
 * <p><b>为什么 Redis 异常要 fail-open 放行：</b>限流是<b>保护手段</b>而非业务正确性约束。
 * 若 Redis 连接失败/超时/脚本异常直接冒泡，交卷、拉卷、抽题这三个核心接口会整体不可用——
 * 为保护核心链路而加的限流反而成了核心链路的单点。取舍上优先保核心链路可用：Redis 故障期间
 * fail-open 放行（极端情况可能放行超额流量，由 {@link BusinessMetrics} 降级指标暴露，便于告警），
 * 而 `false`（桶空 = 业务超限）仍是正常的 429 语义，绝不与基础设施异常混淆。可配 fail-open=false
 * 走 fail-close（先记日志/埋点再上抛异常），代价是 Redis 故障时核心链路快速失败。
 *
 * @see RedisTokenBucket
 */
@Slf4j
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RedisTokenBucket tokenBucket;
    private final BusinessMetrics businessMetrics;
    private final boolean failOpen;

    public RateLimitInterceptor(RedisTokenBucket tokenBucket, BusinessMetrics businessMetrics, boolean failOpen) {
        this.tokenBucket = tokenBucket;
        this.businessMetrics = businessMetrics;
        this.failOpen = failOpen;
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
        String interfaceId = resolveInterfaceId(handlerMethod, rateLimit);
        String key = RedisTokenBucket.key(interfaceId);
        // try 范围收紧到只包 tryAcquire：下面的 catch 只应命中 Redis 基础设施异常（连不上/超时/脚本异常）。
        // 若把整段都包进去，false、NPE、参数错误等也会被当成「降级」静默放行，反而架空限流。
        boolean allowed;
        try {
            allowed = tokenBucket.tryAcquire(key, rateLimit.capacity(), rateLimit.qps());
        } catch (Exception e) {
            // 基础设施故障而非业务超限：先记日志/埋点再决定放行 or 上抛，保证 fail-close 分支也可观测
            log.error("限流器降级：Redis 令牌桶判定失败，接口={}，异常={}，requestId={}",
                    interfaceId, e.getMessage(), MDC.get(RequestIdFilter.MDC_KEY), e);
            businessMetrics.countRateLimitDegraded(interfaceId);
            if (failOpen) {
                // fail-open：放行以保核心链路可用，降级由指标/日志暴露给告警
                return true;
            }
            // fail-close：宁可快速失败也不放行超额流量，原样上抛
            throw e;
        }
        if (!allowed) {
            // 桶空：超限，抛业务异常 → GlobalExceptionHandler 统一转 429（ResponseCode.TOO_MANY_REQUESTS）
            // false 是正常的业务判定不是异常，必须在 try 外判，绝不能被上面的降级分支吞掉
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
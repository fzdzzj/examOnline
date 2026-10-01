package com.exam.config;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 把当前 OTel Trace ID 暴露给日志与响应头（add-distributed-tracing）。
 *
 * <p><b>只做读取，不做创建。</b>HTTP server span 由 opentelemetry-spring-boot-starter 的过滤器
 * 负责开启、打点与关闭；本拦截器跑在它之后的 DispatcherServlet 阶段，{@code Span.current()}
 * 已经是那个 span，直接取用即可。此前它自建根 Span 并 {@code Context.current().with(span)
 * .makeCurrent()}，带来三类问题：
 * <ol>
 *   <li>{@code Scope} 从不 close——线程池复用同一根线程，上一个请求的上下文会泄漏给下一个请求，
 *       后者把 span 挂到毫不相关的 trace 上；</li>
 *   <li>{@code afterCompletion} 里对 {@code Span.current()} 调 {@code end()}，结束的是自己造的
 *       那个 span，与 starter 的 server span 叠成两层重复；</li>
 *   <li>自造的 traceId 与真正的 OTel traceId 无关，日志里那份 id 无法在 Jaeger 查到对应链路。</li>
 * </ol>
 *
 * <p><b>不回显客户端头。</b>旧实现读 {@code X-Trace-ID} 请求头再写回响应头，等于把客户端可控
 * 字符串放进响应头，存在响应头注入面。{@link com.exam.common.RequestIdFilter} 已用白名单专防此点；
 * 这里索性只写服务端 SpanContext 产出的 hex traceId（字符集天然安全）。
 * 跨服务串联本就由 starter 依据 W3C {@code traceparent} 完成，无需自己转发私有头。
 *
 * <p>MDC key 用 {@code traceId}，与 {@code requestId}（{@link com.exam.common.RequestIdFilter}
 * 持有、业务日志与工单沟通用）并列打进日志 pattern：前者用于跳 Jaeger，后者用于跨服务对齐同一次调用。
 */
@Component
public class TraceIdInterceptor implements HandlerInterceptor {

    /** MDC key，需与 logging.pattern.console 中的 %X{traceId} 保持一致。 */
    public static final String MDC_KEY = "traceId";

    /** 响应头名：只承载服务端 SpanContext 生成的值。 */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        SpanContext spanContext = Span.current().getSpanContext();
        if (spanContext.isValid()) {
            String traceId = spanContext.getTraceId();
            MDC.put(MDC_KEY, traceId);
            response.setHeader(TRACE_ID_HEADER, traceId);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        MDC.remove(MDC_KEY);
    }
}

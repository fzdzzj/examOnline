package com.exam.config;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import java.util.UUID;

/**
 * Trace ID 拦截器（add-distributed-tracing）
 * 自动注入/生成 Trace ID 并设置到 MDC，贯穿整个请求链路
 * 
 * @author 凤媚珍
 */
@Component
public class TraceIdInterceptor implements HandlerInterceptor {

    private final Tracer tracer;

    @Autowired
    public TraceIdInterceptor(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 从请求头获取 Trace ID，或生成新的
        String traceId = request.getHeader("X-Trace-ID");
        if (traceId == null || traceId.isEmpty()) {
            traceId = generateTraceId();
        }
        
        // 设置到 MDC，日志框架会自动注入到日志输出
        MDC.put("traceId", traceId);
        
        // 创建根 Span
        Span span = tracer.spanBuilder("http-" + request.getMethod())
                .setParent(Context.current())
                .setAttribute("http.method", request.getMethod())
                .setAttribute("http.url", request.getRequestURI())
                .setAttribute("component", "controller")
                .startSpan();
        
        // 将 Span 绑定到当前上下文
        Context.current().with(span).makeCurrent();
        
        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) {
        // 可以在这里添加响应头
        String traceId = MDC.get("traceId");
        if (traceId != null) {
            response.setHeader("X-Trace-ID", traceId);
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        try {
            Span span = Span.current();
            if (ex != null) {
                span.recordException(ex);
                span.setAttribute("error", true);
                span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, ex.getMessage());
            } else {
                span.setAttribute("http.status_code", response.getStatus());
                span.setStatus(io.opentelemetry.api.trace.StatusCode.OK);
            }
            span.end();
        } finally {
            // 清理 MDC
            MDC.remove("traceId");
        }
    }

    private String generateTraceId() {
        // 生成 16 位 Hex 格式的 Trace ID
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}

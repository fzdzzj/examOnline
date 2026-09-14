package com.exam.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * RequestId 全链路过滤器（可观测性基础）：
 * 1. 请求进来生成/透传 requestId 写入 MDC，日志 pattern 带 [%X{requestId}]；
 * 2. 记录基础 HTTP 日志（方法 / 路径 / 耗时 / 状态码）。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    /**
     * requestId 白名单：仅接受字母/数字/下划线/连字符，长度 1-64。
     * 该值来自客户端且会被写回响应头，必须收敛字符集（理由见 resolveRequestId）。
     */
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.currentTimeMillis();
        String requestId = resolveRequestId(request);
        MDC.put(MDC_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        boolean async = request.isAsyncStarted();
        try {
            chain.doFilter(request, response);
        } finally {
            long cost = System.currentTimeMillis() - start;
            if (!async && !request.getRequestURI().startsWith("/actuator")) {
                log.info("{} {} -> {} ({}ms)", request.getMethod(), request.getRequestURI(),
                        response.getStatus(), cost);
            }
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * 解析 requestId：透传客户端值以便跨服务串联，但**只接受白名单字符集**。
     *
     * <p><b>为什么不直接透传：</b>该值随后由 {@code response.setHeader} 写回响应头。
     * 若原样透传客户端可控字符串，攻击者可提交含 CR/LF 的值来注入额外响应头
     * （HTTP 响应头注入，进一步可做响应拆分）。虽然主流容器会拒绝非法头字符，
     * 但把安全性寄托在容器实现上本身就是隐患。收敛字符集后，非法值一律替换为
     * 服务端生成的 UUID —— requestId 仅是链路追踪标识，非法时重新生成不改变
     * 任何业务语义，故此处选择静默替换而非报错（不因追踪字段影响正常请求）。
     */
    private static String resolveRequestId(HttpServletRequest request) {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId != null && SAFE_REQUEST_ID.matcher(requestId).matches()) {
            return requestId;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}

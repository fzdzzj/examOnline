package com.exam.config;

import com.exam.support.IntegrationTestBase;
import io.opentelemetry.api.trace.Span;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * TraceIdInterceptor 的上下文级验证（add-distributed-tracing 偏差修正）。
 *
 * <p>走真实 MVC 而非直调 preHandle：本组件的问题全在"与过滤器链、线程上下文、响应头的交互"上，
 * 直调方法一律看不见。
 *
 * <p>统一打 /api/auth/login（permitAll，一定会进到 DispatcherServlet 才失败）。若用
 * /api/auth/me 之类受保护端点，会被 Spring Security 过滤器在 MVC 之前挡掉，拦截器压根不执行，
 * 测出来的"没有响应头"是假阴性。
 */
class TraceIdInterceptorIntegrationTest extends IntegrationTestBase {

    private static final String OTLP_TRACE_ID = "^[0-9a-f]{32}$";

    private MvcResult login() throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody\",\"password\":\"Nope12345\"}"))
                .andReturn();
    }

    private String traceHeader(MvcResult result) {
        return result.getResponse().getHeader(TraceIdInterceptor.TRACE_ID_HEADER);
    }

    /** OTel trace id 恒为 32 位小写 hex；旧的自造实现是 UUID 截 16 位，格式先不过。 */
    @Test
    void responseHeaderCarriesRealOtelTraceId() throws Exception {
        String header = traceHeader(login());

        assertTrue(header != null && header.matches(OTLP_TRACE_ID),
                "X-Trace-Id 应是 32 位小写 hex 的 OTel traceId，实际=" + header);
    }

    /**
     * 客户端提交的 X-Trace-ID 不得被回显。旧实现读请求头再写回响应头，等于把客户端可控字符串
     * 放进响应头（响应头注入面），而 RequestIdFilter 早已用白名单专防同一问题。
     */
    @Test
    void clientSuppliedTraceIdIsNotEchoedBack() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody\",\"password\":\"Nope12345\"}")
                        .header("X-Trace-ID", "attacker\r\nX-Injected: yes"))
                .andReturn();

        String header = traceHeader(result);
        assertTrue(header != null && header.matches(OTLP_TRACE_ID),
                "响应头只该有服务端自产 hex id，实际=" + header);
        assertFalse(result.getResponse().containsHeader("X-Injected"),
                "不允许因回显客户端串而多出额外响应头");
    }

    /**
     * 请求结束后当前线程不应残留 span。旧实现 {@code Context.current().with(span).makeCurrent()}
     * 从不 close Scope，MockMvc 与测试同线程，泄漏会让后续同线程工作挂到这条已结束的 trace 上。
     */
    @Test
    void noScopeLeaksOutOfRequest() throws Exception {
        assertFalse(Span.current().getSpanContext().isValid(), "前置：请求前本线程不应有活跃 span");

        String header = traceHeader(login());
        assertTrue(header != null && !header.isEmpty(), "本次请求应已产出 traceId");

        assertFalse(Span.current().getSpanContext().isValid(),
                "请求结束后 Scope 未关闭，残留 traceId=" + Span.current().getSpanContext().getTraceId());
    }

    /** MDC 必须在请求收尾时清掉，否则线程复用会把上一条链路的 id 带给下一条。 */
    @Test
    void mdcIsClearedAfterRequest() throws Exception {
        login();
        assertNull(MDC.get(TraceIdInterceptor.MDC_KEY),
                "afterCompletion 未清理 MDC." + TraceIdInterceptor.MDC_KEY);
    }

    /** 两次独立请求应落在两条不同 trace 上（被泄漏的父上下文会把它俩串成同一条）。 */
    @Test
    void sequentialRequestsGetDistinctTraces() throws Exception {
        String first = traceHeader(login());
        String second = traceHeader(login());

        assertTrue(first != null && second != null, "两次请求都应产出 traceId");
        assertFalse(first.equals(second), "同一线程上连续两次请求不该复用同一条 trace");
    }
}

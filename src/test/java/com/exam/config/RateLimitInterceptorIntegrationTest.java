package com.exam.config;

import com.exam.support.IntegrationTestBase;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 全局 API 限流的上下文级验证（add-api-rate-limiting）。
 *
 * <p>刻意走 MockMvc 打真实 HTTP 请求，而不是直接调 {@code RateLimitConfig.isAllowed()}：
 * 本组件此前三个缺陷全在同一条线上——自建 RedisTemplate 未走 afterPropertiesSet 导致
 * scriptExecutor 为 null（每个请求 500）、Lua 里用 {@code == nil} 判 HGET 缺字段导致首次
 * 请求算术异常、@Value 读 {@code rate.limit.*} 而开关注到 {@code rate-limiting.enabled}
 * 导致 qps/burst 静默不绑定。直调单测和 Bean 存在性断言都抓不到它们，只有穿过
 * 真实 MVC 拦截器链才会暴露。
 */
@TestPropertySource(properties = {
        "rate-limiting.enabled=true",
        // 阈值压到个位数，6 次请求内必定触顶；同时把 burst 与 qps 区分开，
        // 若绑定名写错则回落默认 150/100，本用例就再也限不住 → 直接变红。
        "rate-limiting.qps=1",
        "rate-limiting.burst=3"
})
class RateLimitInterceptorIntegrationTest extends IntegrationTestBase {

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private RateLimitConfig rateLimitConfig;

    /** 触发限流：桶容量 3，第 4 次起应被拒。 */
    @Test
    void overLimitRequestsAreRejectedWith429() throws Exception {
        MvcResult limited = null;
        int rejected = 0;
        for (int i = 0; i < 6; i++) {
            MvcResult result = loginWithBogusCredentials();
            if (result.getResponse().getStatus() == 429) {
                rejected++;
                limited = result;
            }
        }

        assertTrue(rejected >= 1, "qps=1/burst=3 下连发 6 次应至少触发一次 429，实际 0 次——限流拦截器可能没进 MVC 链");
        assertTrue(limited.getResponse().containsHeader("Retry-After"),
                "429 应带 Retry-After 供客户端退避");
    }

    /** 429 响应体须是可解析的业务错误契约，不能是空 body 或 HTML。 */
    @Test
    void rateLimitResponseCarriesUnifiedErrorContract() throws Exception {
        MvcResult limited = null;
        for (int i = 0; i < 6 && limited == null; i++) {
            MvcResult result = loginWithBogusCredentials();
            if (result.getResponse().getStatus() == 429) {
                limited = result;
            }
        }

        org.junit.jupiter.api.Assumptions.assumeTrue(limited != null, "未触发限流，交由上个用例诊断");

        var body = objectMapper.readTree(limited.getResponse().getContentAsString());
        assertEquals("RATE_LIMIT_EXCEEDED", body.path("code").asText(),
                "429 响应体应带统一错误码，实际 body=" + limited.getResponse().getContentAsString());
        assertTrue(body.hasNonNull("message"), "429 响应体应带人类可读 message");
    }

    /** 未触顶时不得误伤正常请求：桶够用时全部放行（登录失败是 401，不是 429）。 */
    @Test
    void requestsWithinBurstAreNotRateLimited() throws Exception {
        for (int i = 0; i < 2; i++) {
            MvcResult result = loginWithBogusCredentials();
            assertTrue(result.getResponse().getStatus() != 429,
                    "burst=3 内第 " + (i + 1) + " 次请求不该被限流");
        }
    }

    /** 每次拒绝都要留下可观测痕迹，否则线上只看到 429 却不知规模。 */
    @Test
    void rejectionsAreRecordedOnMeterRegistry() throws Exception {
        double before = counterValue();
        for (int i = 0; i < 6; i++) {
            loginWithBogusCredentials();
        }
        double after = counterValue();

        assertTrue(after - before >= 1,
                "被拒请求应递增 http_request_rate_limited_total，实测增量 " + (after - before));
    }

    /**
     * 三个 @Value 绑定名必须与配置里的 {@code rate-limiting.*} 完全一致。
     * 行为用例只能钉住 burst（同秒内是否触顶由容量决定）；qps 是回填速率、key-prefix 是 Redis 键名，
     * 写错表现为"静默回落默认值"而行为不变，故直接读字段核对绑定。
     */
    @Test
    void rateLimitPropertiesAreBoundNotSilentlyDefaulted() {
        assertEquals(1, org.springframework.test.util.ReflectionTestUtils
                .getField(rateLimitConfig, "defaultQps"), "qps 未绑定 rate-limiting.qps");
        assertEquals(3, org.springframework.test.util.ReflectionTestUtils
                .getField(rateLimitConfig, "defaultBurst"), "burst 未绑定 rate-limiting.burst");
        assertEquals("api:ratelimit", org.springframework.test.util.ReflectionTestUtils
                .getField(rateLimitConfig, "keyPrefix"), "key-prefix 未绑定 rate-limiting.key-prefix");
    }

    private MvcResult loginWithBogusCredentials() throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"rl_nobody\",\"password\":\"RlWrong999\"}"))
                .andReturn();
    }

    private double counterValue() {
        var counter = meterRegistry.find("http_request_rate_limited_total").counter();
        return counter == null ? 0d : counter.count();
    }
}

package com.exam.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 交卷链路观测补齐（add-submit-observability）的存在性集成测试：
 * 起真实嵌入式 Tomcat（RANDOM_PORT），直接打指标端点断言两项观测在——
 * <ul>
 *   <li>tomcat.threads.busy / config.max：依赖 application.yml 的
 *       server.tomcat.mbeanregistry.enabled=true（Spring Boot 默认不注册 Tomcat MBean，
 *       该开关回退后 metrics 端点对这两个名字返回 404）；</li>
 *   <li>exam_submit_duration_seconds_bucket 的 0.5/1/2/5s SLO 桶：依赖
 *       BusinessMetrics 构造器里的 serviceLevelObjectives（拿掉后只剩默认几何桶，
 *       这段边界没有桶，histogram_quantile 只能跨数量级插值）。</li>
 * </ul>
 * 任一配置被回退，本测试变红——防「配置写了但指标静默消失」。
 *
 * <p><b>@AutoConfigureObservability 的由来：</b>spring-boot-test 默认给测试上下文注入
 * {@code management.defaults.metrics.export.enabled=false}（属性源名 "test"），Prometheus
 * 导出自动配置整体退场、/actuator/prometheus 不会注册——不加该注解时 registry 退化为
 * SimpleMeterRegistry，Prometheus 端点读取测试必红（不是业务配置问题，是测试框架行为）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
@ActiveProfiles("test")
class SubmitObservabilityIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("Tomcat 线程水位：busy 与 config.max 可从指标端点读取")
    void tomcatThreadMetricsExposed() {
        for (String metric : new String[]{"tomcat.threads.busy", "tomcat.threads.config.max"}) {
            ResponseEntity<String> resp = rest.getForEntity("/actuator/metrics/" + metric, String.class);
            // 现场留证：原始响应体打进 surefire 输出（判据要求端点原始输出为证）
            System.out.println("[evidence] GET /actuator/metrics/" + metric + " -> " + resp.getStatusCode()
                    + " body=" + resp.getBody());
            assertEquals(HttpStatus.OK, resp.getStatusCode(),
                    metric + " 应出现在指标端点（server.tomcat.mbeanregistry.enabled 未生效则 404）");
            assertNotNull(resp.getBody(), metric + " 响应体不应为空");
            assertTrue(resp.getBody().contains("measurements"), metric + " 响应应含 measurements");
        }
    }

    @Test
    @DisplayName("交卷耗时直方图：SLO 桶出现在 Prometheus 端点")
    void submitDurationBucketsExposed() {
        ResponseEntity<String> resp = rest.getForEntity("/actuator/prometheus", String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        // 现场留证：只打印交卷耗时桶与 Tomcat 线程相关行，避免整页刷屏
        StringBuilder evidence = new StringBuilder();
        for (String line : resp.getBody().split("\n")) {
            if (line.startsWith("exam_submit_duration_seconds_bucket") || line.startsWith("tomcat_threads_")) {
                evidence.append(line).append('\n');
            }
        }
        System.out.println("[evidence] GET /actuator/prometheus ->\n" + evidence);
        assertTrue(resp.getBody().contains("exam_submit_duration_seconds_bucket{le=\"0.5\""),
                "Prometheus 端点应有 0.5s 桶（SLO 桶被拿掉则只剩默认桶，该断言变红）");
        assertTrue(resp.getBody().contains("exam_submit_duration_seconds_bucket{le=\"1.0\""),
                "Prometheus 端点应有 1s 桶");
        assertTrue(resp.getBody().contains("exam_submit_duration_seconds_bucket{le=\"2.0\""),
                "Prometheus 端点应有 2s 桶");
        assertTrue(resp.getBody().contains("exam_submit_duration_seconds_bucket{le=\"5.0\""),
                "Prometheus 端点应有 5s 桶");
    }
}
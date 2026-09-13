package com.exam.monitoring.metrics;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务指标导出单元测试（add-performance-deepening 阶段8 可观测性 task5）：
 * 用真实的 Prometheus 注册表装配 {@link BusinessMetrics}，触发各打点后直接 {@code scrape()}
 * 成 Prometheus 文本格式，断言自定义指标名与计数正确暴露——等价于验证运行期
 * {@code /actuator/prometheus} 端点会包含这些指标（RabbitMQ 未启动时无法整包启动验证，
 * 故用最小注册表证明导出路径与指标口径）。
 */
class BusinessMetricsTest {

    private PrometheusMeterRegistry registry() {
        return new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    }

    /** 无 RabbitAdmin 的 ObjectProvider 兜底：getIfAvailable() 返回 null（模拟测试环境无 RabbitMQ）。 */
    private ObjectProvider<RabbitAdmin> noRabbitAdmin() {
        return new ObjectProvider<RabbitAdmin>() {
            @Override
            public RabbitAdmin getObject() {
                return null;
            }

            @Override
            public RabbitAdmin getObject(Object... args) {
                return null;
            }
        };
    }

    @Test
    @DisplayName("交卷指标：耗时 Timer + 成功/失败 Counter 正确导出")
    void submitMetricsExported() {
        PrometheusMeterRegistry registry = registry();
        // ObjectProvider 兜底：无 RabbitAdmin，Gauge 走 -1 降级分支，不影响本用例
        BusinessMetrics metrics = new BusinessMetrics(registry, noRabbitAdmin());

        metrics.recordSubmitSuccess(metrics.startSubmit());
        metrics.recordSubmitSuccess(metrics.startSubmit());
        metrics.recordSubmitFailure(metrics.startSubmit());

        String text = registry.scrape();
        // 命中 3 次 Timer count=3；成功 2 次、失败 1 次（Prometheus 中 Timer 的 _count 为整数，Counter._total 为小数）
        assertTrue(text.contains("exam_submit_duration_seconds_count 3"), "Timer count=3 应导出（3 次交卷）");
        assertTrue(text.contains("exam_submit_success_total 2.0"), "成功计数=2 应导出");
        assertTrue(text.contains("exam_submit_failure_total 1.0"), "失败计数=1 应导出");
    }

    @Test
    @DisplayName("防作弊事件计数：按事件类型打 tag 的 Counter 正确导出")
    void anticheatEventCounterExported() {
        PrometheusMeterRegistry registry = registry();
        BusinessMetrics metrics = new BusinessMetrics(registry, noRabbitAdmin());

        metrics.countAntiCheatEvent("SWITCH_SCREEN");
        metrics.countAntiCheatEvent("SWITCH_SCREEN");
        metrics.countAntiCheatEvent("PAGE_REFRESH");

        String text = registry.scrape();
        assertTrue(text.contains("exam_anticheat_events_total{type=\"SWITCH_SCREEN\"} 2.0"),
                "切屏开关事件计数=2 应按 type tag 导出");
        assertTrue(text.contains("exam_anticheat_events_total{type=\"PAGE_REFRESH\"} 1.0"),
                "刷新事件计数=1 应按 type tag 导出");
    }

    @Test
    @DisplayName("MQ 队列深度：无 RabbitAdmin 时导出 -1（不可用降级），不断言业务变扎实")
    void queueDepthExportedWhenRabbitAdminAbsent() {
        PrometheusMeterRegistry registry = registry();
        BusinessMetrics metrics = new BusinessMetrics(registry, noRabbitAdmin());

        String text = registry.scrape();
        assertTrue(text.contains("exam_mq_submit_queue_depth -1.0"),
                "无 RabbitAdmin 时队列深度应导出 -1（表示不可用而非 0 积压）");
    }
}
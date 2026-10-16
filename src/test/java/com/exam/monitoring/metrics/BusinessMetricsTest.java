package com.exam.monitoring.metrics;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import com.exam.anticheat.collector.BehaviorEventRegistry;
import com.exam.anticheat.collector.BehaviorEventTypes;
import com.exam.anticheat.collector.CopyPasteEventCollector;
import com.exam.anticheat.collector.DraftConflictEventCollector;
import com.exam.anticheat.collector.FullScreenExitEventCollector;
import com.exam.anticheat.collector.PageRefreshEventCollector;
import com.exam.anticheat.collector.SubmitAnomalyEventCollector;
import com.exam.anticheat.collector.SwitchScreenEventCollector;
import com.exam.anticheat.collector.UnknownEventCollector;
import com.exam.anticheat.collector.WindowBlurEventCollector;
import com.exam.anticheat.service.BehaviorCounterService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

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

    /**
     * 真实事件注册表（七个策略 + 兜底）：指标侧的事件类型白名单就取自它，
     * 故此处必须装配真实注册表——用 mock 会让"归并"断言失去意义（白名单为空则全落 unknown）。
     */
    private BehaviorEventRegistry behaviorRegistry() {
        return new BehaviorEventRegistry(
                List.of(new SwitchScreenEventCollector(mock(BehaviorCounterService.class)),
                        new WindowBlurEventCollector(), new PageRefreshEventCollector(),
                        new SubmitAnomalyEventCollector(), new DraftConflictEventCollector(),
                        new CopyPasteEventCollector(), new FullScreenExitEventCollector()),
                new UnknownEventCollector());
    }

    private BusinessMetrics newMetrics(PrometheusMeterRegistry registry) {
        return new BusinessMetrics(registry, noRabbitAdmin(), behaviorRegistry());
    }

    @Test
    @DisplayName("交卷指标：耗时 Timer + 成功/失败 Counter 正确导出")
    void submitMetricsExported() {
        PrometheusMeterRegistry registry = registry();
        // ObjectProvider 兜底：无 RabbitAdmin，Gauge 走 -1 降级分支，不影响本用例
        BusinessMetrics metrics = newMetrics(registry);

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
        BusinessMetrics metrics = newMetrics(registry);

        metrics.countAntiCheatEvent("SWITCH_SCREEN");
        metrics.countAntiCheatEvent("SWITCH_SCREEN");
        metrics.countAntiCheatEvent("PAGE_REFRESH");

        String text = registry.scrape();
        assertTrue(text.contains("exam_anticheat_events_total{type=\"SWITCH_SCREEN\"} 2.0"),
                "切屏开关事件计数=2 应按 type tag 导出");
        assertTrue(text.contains("exam_anticheat_events_total{type=\"PAGE_REFRESH\"} 1.0"),
                "刷新事件计数=1 应按 type tag 导出");
    }

    // ==================== 指标 tag 归并（change harden-behavior-report-and-monitor） ====================

    @Test
    @DisplayName("tag 基数有界：未注册事件类型归并 tag \"unknown\"，原类型名不得出现在指标中")
    void unregisteredEventTypesCollapseToUnknownTag() {
        PrometheusMeterRegistry registry = registry();
        BusinessMetrics metrics = newMetrics(registry);

        // 客户端可控 eventType 曾直接建 Counter tag（DTO 仅 @Size(32) 不限取值）→ 基数无界
        metrics.countAntiCheatEvent("SOME_FUTURE_EVENT");
        metrics.countAntiCheatEvent("!!injected-type!!");
        metrics.countAntiCheatEvent("SWITCH_SCREEN");

        String text = registry.scrape();
        assertTrue(text.contains("exam_anticheat_events_total{type=\"unknown\"} 2.0"),
                "两个未注册类型必须归并到同一个 unknown tag（计数=2）");
        assertFalse(text.contains("SOME_FUTURE_EVENT"), "未注册类型名不得成为 tag 值（基数无界来源）");
        assertFalse(text.contains("!!injected-type!!"), "任意客户端字符串都不得进入指标维度");
        assertEquals(2, occurrences(text, "exam_anticheat_events_total{"),
                "全部指标只应有两条 series：注册类型一条 + unknown 一条");
        assertTrue(text.contains("exam_anticheat_events_total{type=\"SWITCH_SCREEN\"} 1.0"),
                "已注册类型仍按自身 tag 计数（归并不得吞掉已知类型）");
    }

    @Test
    @DisplayName("注册集取自 BehaviorEventTypes 七常量：各占一个 tag，不落入 unknown")
    void everyRegisteredEventTypeKeepsItsOwnTag() {
        PrometheusMeterRegistry registry = registry();
        BusinessMetrics metrics = newMetrics(registry);

        for (String type : registeredEventTypes()) {
            metrics.countAntiCheatEvent(type);
        }

        String text = registry.scrape();
        for (String type : registeredEventTypes()) {
            assertTrue(text.contains("exam_anticheat_events_total{type=\"" + type + "\"} 1.0"),
                    "已注册类型 " + type + " 必须按自身 tag 计数");
        }
        assertFalse(text.contains("type=\"unknown\""),
                "七个注册常量都不得被归并（注册集必须与 BehaviorEventTypes 对齐）");
    }

    /** 由 BehaviorEventTypes 的 public String 常量反射取得注册集（不硬编码七元组，避免与常量集漂移）。 */
    private List<String> registeredEventTypes() {
        List<String> types = new java.util.ArrayList<>();
        for (java.lang.reflect.Field field : BehaviorEventTypes.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                    && field.getType() == String.class) {
                try {
                    types.add((String) field.get(null));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        assertEquals(7, types.size(), "BehaviorEventTypes 注册常量应为七个（新增常量须同批补本护栏口径）");
        return types;
    }

    private int occurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }

    @Test
    @DisplayName("MQ 队列深度：无 RabbitAdmin 时导出 -1（不可用降级），不断言业务变扎实")
    void queueDepthExportedWhenRabbitAdminAbsent() {
        PrometheusMeterRegistry registry = registry();
        BusinessMetrics metrics = newMetrics(registry);

        String text = registry.scrape();
        assertTrue(text.contains("exam_mq_submit_queue_depth -1.0"),
                "无 RabbitAdmin 时队列深度应导出 -1（表示不可用而非 0 积压）");
    }

    @Test
    @DisplayName("重复扫描计数：按 task tag 的 Counter 正确导出")
    void duplicateSweepCounterExported() {
        PrometheusMeterRegistry registry = registry();
        BusinessMetrics metrics = newMetrics(registry);

        metrics.countSweepDuplicateDetected("sweep");
        metrics.countSweepDuplicateDetected("sweep");
        metrics.countSweepDuplicateDetected("state-advance");

        String text = registry.scrape();
        assertTrue(text.contains("exam_sweep_duplicate_detected_total{task=\"sweep\"} 2.0"),
                "sweep 重复扫描计数=2 应按 task tag 导出");
        assertTrue(text.contains("exam_sweep_duplicate_detected_total{task=\"state-advance\"} 1.0"),
                "state-advance 重复扫描计数=1 应按 task tag 导出");
    }

    @Test
    @DisplayName("死信指标：深度 Gauge(-1) + retry/entered Counter 正确导出")
    void dlqMetricsExported() {
        PrometheusMeterRegistry registry = registry();
        BusinessMetrics metrics = newMetrics(registry);

        metrics.recordMqRetry("retried");
        metrics.recordMqRetry("retried");
        metrics.recordMqRetry("exhausted");
        metrics.countDlqEntered();

        String text = registry.scrape();
        assertTrue(text.contains("exam_mq_dlq_depth -1.0"),
                "无 RabbitAdmin 时死信深度应导出 -1");
        assertTrue(text.contains("exam_mq_retry_total{outcome=\"retried\"} 2.0")
                        || text.contains("exam_mq_retry_total{outcome=\"retried\",} 2.0"),
                "retried 计数=2 应导出");
        assertTrue(text.contains("exam_mq_retry_total{outcome=\"exhausted\"} 1.0")
                        || text.contains("exam_mq_retry_total{outcome=\"exhausted\",} 1.0"),
                "exhausted 计数=1 应导出");
        assertTrue(text.contains("exam_mq_dlq_entered_total 1.0"),
                "进入死信计数=1 应导出");
    }

    @Test
    @DisplayName("数据保留行数：按 table+action tag 的 Counter 正确导出")
    void retentionRowsCounterExported() {
        PrometheusMeterRegistry registry = registry();
        BusinessMetrics metrics = newMetrics(registry);

        metrics.countRetentionRows("exam_behavior_logs", "candidate", 5);
        metrics.countRetentionRows("exam_behavior_logs", "candidate", 2);
        metrics.countRetentionRows("exam_behavior_logs", "deleted", 3);
        metrics.countRetentionRows("exam_submit_dedups", "candidate", 1);
        // dry-run 语义：amount<=0 不打点
        metrics.countRetentionRows("score_audit_logs", "deleted", 0);

        String text = registry.scrape();
        assertTrue(text.contains("exam_retention_rows_total{action=\"candidate\",table=\"exam_behavior_logs\"} 7.0")
                        || text.contains("exam_retention_rows_total{table=\"exam_behavior_logs\",action=\"candidate\"} 7.0"),
                "behavior candidate 计数=7 应导出");
        assertTrue(text.contains("exam_retention_rows_total{action=\"deleted\",table=\"exam_behavior_logs\"} 3.0")
                        || text.contains("exam_retention_rows_total{table=\"exam_behavior_logs\",action=\"deleted\"} 3.0"),
                "behavior deleted 计数=3 应导出");
        assertTrue(text.contains("exam_retention_rows_total{action=\"candidate\",table=\"exam_submit_dedups\"} 1.0")
                        || text.contains("exam_retention_rows_total{table=\"exam_submit_dedups\",action=\"candidate\"} 1.0"),
                "dedup candidate 计数=1 应导出");
        assertFalse(text.contains("score_audit_logs"),
                "amount=0 时不应注册 score_audit_logs 指标");
    }

    @Test
    @DisplayName("锁等待指标：亚秒等待不得被截成 0")
    void lockWaitRecordsSubSecondDuration() {
        PrometheusMeterRegistry registry = registry();
        BusinessMetrics metrics = newMetrics(registry);

        // SETNX 的正常等待本就是毫秒级
        metrics.recordLockWait(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(5));

        var timer = registry.get("exam.submit.lock.wait").timer();
        assertEquals(1, timer.count(), "应记到 1 次采样");
        assertEquals(5000L, timer.totalTime(java.util.concurrent.TimeUnit.MICROSECONDS),
                "5ms 等待必须累计为 5000µs；此前实现把秒转 long 会把亚秒等待全截成 0，"
                        + "指标恒 0 却看起来'非常健康'");
        assertTrue(registry.scrape().contains("exam_submit_lock_wait_seconds_count 1"),
                "锁等待计数应导出到 Prometheus 文本");
    }

    @Test
    @DisplayName("锁等待指标：必须有毫秒级分桶，否则 P95 是跨数量级插值")
    void lockWaitHasMillisecondBuckets() {
        PrometheusMeterRegistry registry = registry();
        BusinessMetrics metrics = newMetrics(registry);

        metrics.recordLockWait(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(5));
        String text = registry.scrape();

        // Micrometer 默认桶在 10ms 之后直接跳到 8s，锁等待几乎全落在这段空档里；
        // 验收口径是"P95 < 100ms"，没有 50/100ms 这两个桶该断言怎么都能通过。
        assertTrue(text.contains("le=\"0.05\""), "应存在 50ms 分桶");
        assertTrue(text.contains("le=\"0.1\""), "应存在 100ms 分桶");
    }

    @Test
    @DisplayName("锁获取/竞争计数：两个 Counter 正确导出")
    void lockAcquisitionAndContentionCountersExported() {
        PrometheusMeterRegistry registry = registry();
        BusinessMetrics metrics = newMetrics(registry);

        metrics.recordLockWait(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(2));
        metrics.recordLockContention();
        metrics.recordLockContention();

        String text = registry.scrape();
        assertTrue(text.contains("exam_submit_lock_acquisitions_total 1.0"),
                "锁获取成功计数=1 应导出");
        assertTrue(text.contains("exam_submit_lock_contentions_total 2.0")
                        || text.contains("exam_submit_lock_contentions_total{} 2.0"),
                "锁竞争失败计数=2 应导出");
    }

    @Test
    @DisplayName("交卷耗时直方图：SLO 桶覆盖 0.5/1/2/5s 量级，服务端 P99 可算")
    void submitDurationHasSecondScaleBuckets() {
        PrometheusMeterRegistry registry = registry();
        BusinessMetrics metrics = newMetrics(registry);

        metrics.recordSubmitSuccess(metrics.startSubmit());

        String text = registry.scrape();
        // Micrometer 默认桶（几何间隔）在 1.17s 与 6.82s 之间是空档，交卷耗时恰落在
        // 这段——不显式铺 SLO 桶时，面板/告警里的 histogram_quantile 只能跨数量级插值。
        // 断言带指标名前缀，避免与锁等待桶（同样有 le="0.5"）串味。
        assertTrue(text.contains("exam_submit_duration_seconds_bucket{le=\"0.5\""),
                "交卷耗时应有 0.5s 直方图桶");
        assertTrue(text.contains("exam_submit_duration_seconds_bucket{le=\"1.0\""),
                "交卷耗时应有 1s 直方图桶");
        assertTrue(text.contains("exam_submit_duration_seconds_bucket{le=\"2.0\""),
                "交卷耗时应有 2s 直方图桶");
        assertTrue(text.contains("exam_submit_duration_seconds_bucket{le=\"5.0\""),
                "交卷耗时应有 5s 直方图桶");
    }
}

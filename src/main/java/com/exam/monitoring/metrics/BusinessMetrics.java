package com.exam.monitoring.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * 业务自定义指标集合（add-performance-deepening 阶段8 可观测性 task5）：
 * 集中注册交卷 QPS/耗时、交卷成功率、MQ 队列深度、防作弊事件计数四类指标，
 * 业务侧只依赖本类做打点，不在各 Service 里散落 Micrometer API。
 *
 * <p><b>Timer / Counter / Gauge 各自适用场景：</b>
 * <ul>
 *   <li><b>Timer</b>：天然统计「事件的次数 + 每事件耗时」两级维度，Prometheus 侧由
 *       *_count 推导 QPS、由 *_sum / *_count 推导平均耗时，并支持 histogram 分位（p99）。
 *       所以「交卷 QPS + 交卷耗时」用一个 Timer 即可同时覆盖，无需再用独立 Counter；</li>
 *   <li><b>Counter</b>：只增不减的累加计数，适合「某类事件发生了几次」（如交卷成功/失败、防作弊事件），
 *       配 {@code %_increase()} 可算速率。此处配合 Timer 拆成成功/失败两个 Counter，导出成功率；</li>
 *   <li><b>Gauge</b>：反映「当前时刻快照」的可增可减数值，不适合累加、适合实时状态量——
 *       典型即 MQ 队列积压深度（Q 内消息数实时变化，Prometheus 每次抓取读当前值）。
 *       Gauge 直接绑定{@link RabbitAdmin#getQueueInfo(String)}为数据源，采集端抓取时才取值。</li>
 * </ul>
 *
 * <p><b>指标如何与 requestId 关联：</b>Counter/Timer/Gauge 是聚合统计量，本身不携带
 * 具体某次请求的 ID——它们回答「系统整体是否健康/多慢」，不回答「哪一次请求慢」。
 * 两者的关联靠合作链路：慢 SQL 拦截器与 {@link com.exam.common.RequestIdFilter}
 * 共用 MDC 的 requestId（少 SQL WARN 日志带 requestId），排查时先看指标锁定大方向
 * （交卷 QPS 骤降 / 队列积压上涨 / 耗时 p99 恶化），再按 requestId 去日志 grep 该请求
 * 命中的慢 SQL 与链路明细——「指标定方向，日志定个案」，两者互补。
 *
 * @see com.exam.common.slow_sql.SlowSqlInterceptor
 */
@Component
public class BusinessMetrics {

    /** 指标名：交卷耗时（Timer，隐式覆盖 QPS 与平均耗时/分位） */
    private static final String SUBMIT_DURATION = "exam.submit.duration";
    /** 指标名：交卷成功次数（Counter） */
    private static final String SUBMIT_SUCCESS_COUNT = "exam.submit.success";
    /** 指标名：交卷失败次数（Counter） */
    private static final String SUBMIT_FAILURE_COUNT = "exam.submit.failure";
    /** 指标名：交卷 MQ 队列积压深度（Gauge） */
    private static final String SUBMIT_QUEUE_DEPTH = "exam.mq.submit.queue.depth";
    /** 指标名：防作弊事件计数（Counter，按事件类型打 tag） */
    private static final String ANTICHEAT_EVENTS = "exam.anticheat.events";
    /** 交卷队列名：与 RabbitMqConfig.SUBMIT_QUEUE 保持一致（避免依赖具体实现类） */
    private static final String SUBMIT_QUEUE = "exam.submit.queue";

    private final MeterRegistry registry;
    private final ObjectProvider<RabbitAdmin> rabbitAdmin;

    private final Timer submitTimer;
    private final Counter submitSuccessCounter;
    private final Counter submitFailureCounter;
    /** 防作弊事件计数按事件类型缓存：不同 eventType 各自注册一个 Counter（tag=type） */
    private final Map<String, Counter> anticheatEventCounters = new ConcurrentHashMap<>();

    public BusinessMetrics(MeterRegistry registry, ObjectProvider<RabbitAdmin> rabbitAdmin) {
        this.registry = registry;
        // 用 ObjectProvider 兜底：无 RabbitMQ 连接（如单元/集成测试环境无 RabbitAdmin 时）优雅降级，Gauge 返回 -1
        this.rabbitAdmin = rabbitAdmin;

        this.submitTimer = Timer.builder(SUBMIT_DURATION)
                .description("交卷链路耗时（含 MQ confirm 等待），count 可推导 QPS，sum/count 可推导平均耗时")
                .register(registry);
        this.submitSuccessCounter = Counter.builder(SUBMIT_SUCCESS_COUNT)
                .description("交卷成功次数")
                .register(registry);
        this.submitFailureCounter = Counter.builder(SUBMIT_FAILURE_COUNT)
                .description("交卷失败次数（如 MQ confirm 失败）")
                .register(registry);

        // Gauge：供外部采集端实时读取当前队列积压深度（Prometheus 抓取时才调用 provider）
        Gauge.builder(SUBMIT_QUEUE_DEPTH, this::submitQueueDepthProvider)
                .description("交卷 MQ 队列当前积压消息数，经 RabbitAdmin 实时查询；-1 表示查询失败/无 RabbitMQ")
                .register(registry);
    }

    /** 记录一次交卷请求开始（配合 success/failure 一起 stop）。 */
    public Timer.Sample startSubmit() {
        return Timer.start(registry);
    }

    /** 交卷成功：记录耗时 + 成功计数。 */
    public void recordSubmitSuccess(Timer.Sample sample) {
        sample.stop(submitTimer);
        submitSuccessCounter.increment();
    }

    /** 交卷失败：记录耗时 + 失败计数。 */
    public void recordSubmitFailure(Timer.Sample sample) {
        sample.stop(submitTimer);
        submitFailureCounter.increment();
    }

    /** 防作弊事件计数：按事件类型各自的 Counter 加一（类型取自 BehaviorEventTypes 常量）。 */
    public void countAntiCheatEvent(String eventType) {
        anticheatEventCounters.computeIfAbsent(eventType, type -> Counter.builder(ANTICHEAT_EVENTS)
                .tag("type", type)
                .description("防作弊事件计数，按事件类型")
                .register(registry))
                .increment();
    }

    /** Gauge 数据源：查 RabbitAdmin 队列信息取消息数；无 RabbitAdmin 或查询异常返回 -1（表示不可用，而非 0 积压）。 */
    private double submitQueueDepthProvider() {
        RabbitAdmin admin = rabbitAdmin.getIfAvailable();
        if (admin == null) {
            return -1;
        }
        try {
            var info = admin.getQueueInfo(SUBMIT_QUEUE);
            return info == null ? 0 : info.getMessageCount();
        } catch (Exception e) {
            return -1;
        }
    }
}
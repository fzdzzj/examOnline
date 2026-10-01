package com.exam.taking.config;

import com.exam.common.RequestIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑（交卷削峰链路，add-exam-taking）：
 *
 * <pre>
 * 交卷 HTTP → [exam.submit.exchange] --exam.submit--> [exam.submit.queue] → 消费者批量落库（落库成功才 ack）
 *                     ↑ 失败重试（x-retry-count 头，超阈值放弃）
 * [exam.submit.dlx] ← 死信路由 ← exam.submit.queue (x-dead-letter-exchange) → [exam.submit.dead.queue] 人工排查
 * </pre>
 *
 * <p>可靠性约定：
 * <ul>
 *   <li>生产者 confirm（application.yml publisher-confirm-type=correlated）——消息确认到达 broker 才算发送成功；</li>
 *   <li>消费者手动 ack——批量落库成功后才确认，未确认消息断连后自动重投；</li>
 *   <li>消费端幂等——casFillAnswers 仅在 answers 为 NULL 时写入，重复投递业务只执行一次；</li>
 *   <li>死信兜底——重试超过阈值进死信队列；死信队列不设 TTL/max-length（不以过期代替兜底），
 *       稳态深度应为 0；可观测与有界重投见 DlqReplayService / BusinessMetrics（add-dlq-observability-and-replay）。</li>
 * </ul>
 */
@Configuration
public class RabbitMqConfig {

    /** 交卷业务交换机 */
    public static final String SUBMIT_EXCHANGE = "exam.submit.exchange";

    /** 交卷队列：消费者批量落库的数据源 */
    public static final String SUBMIT_QUEUE = "exam.submit.queue";

    public static final String SUBMIT_ROUTING_KEY = "exam.submit";

    /** 死信交换机：消费重试耗尽的消息最终去处 */
    public static final String SUBMIT_DLX = "exam.submit.dlx";

    public static final String SUBMIT_DLQ = "exam.submit.dead.queue";

    public static final String SUBMIT_DEAD_ROUTING_KEY = "exam.submit.dead";

    /** 重试计数消息头：消费失败重发时递增，超过阈值进死信 */
    public static final String RETRY_HEADER = "x-retry-count";

    /** 请求链路 id 消息头：与 HTTP 头 X-Request-Id 同名，便于跨 MQ 边界按同一键对照。
     *  为何透传：交卷离开 HTTP 线程后，SlowSqlInterceptor 与落库日志仍读 MDC；
     *  不带此头则消费端日志/慢 SQL 全部丢失 requestId，无法从"某学生交卷"逐单追到"答案落库"（add-mq-trace-and-capacity）。 */
    public static final String REQUEST_ID_HEADER = "x-request-id";

    @Bean
    public DirectExchange submitExchange() {
        return new DirectExchange(SUBMIT_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange submitDeadLetterExchange() {
        return new DirectExchange(SUBMIT_DLX, true, false);
    }

    /** 交卷队列：持久化，并声明死信路由（消费 nack 不重入队时消息转入死信队列） */
    @Bean
    public Queue submitQueue() {
        return QueueBuilder.durable(SUBMIT_QUEUE)
                .deadLetterExchange(SUBMIT_DLX)
                .deadLetterRoutingKey(SUBMIT_DEAD_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue submitDeadQueue() {
        return QueueBuilder.durable(SUBMIT_DLQ).build();
    }

    @Bean
    public Binding submitBinding() {
        return BindingBuilder.bind(submitQueue()).to(submitExchange()).with(SUBMIT_ROUTING_KEY);
    }

    @Bean
    public Binding submitDeadBinding() {
        return BindingBuilder.bind(submitDeadQueue()).to(submitDeadLetterExchange()).with(SUBMIT_DEAD_ROUTING_KEY);
    }

    /** 消息体走 JSON：复用 Spring 的 ObjectMapper（含 LocalDateTime 支持），与业务序列化口径一致 */
    @Bean
    public MessageConverter submitMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    /**
     * 生产端 trace 透传：把当前线程 MDC 的 requestId 写入每条待发消息头（为空不写，兼容存量/非 HTTP 发送）。
     * 为什么在模板级（beforePublishPostProcessors）而非 Sender 内 4 参 convertAndSend 重载：
     * 冻结的集成测试（ExamTakingIntegrationTest/GradingScoreIntegrationTest/AntiCheatIntegrationTest，禁止修改）
     * 用 Mockito 验证的是 3 参 convertAndSend，4 参重载在 mock 上被视为不同方法、8 个用例会失配；
     * 本机制意图不变（requestId 跨 MQ 边界透传到消费端 MDC，SlowSqlInterceptor/落库日志据此打点），仅接线位置不同。
     * 副作用说明：作用域是共享 RabbitTemplate，交卷消费者重试 republish 亦会带上当前线程（已按条恢复）的 requestId，属预期。
     */
    @Bean
    public MessagePostProcessor submitRequestIdPostProcessor() {
        return message -> {
            String requestId = MDC.get(RequestIdFilter.MDC_KEY);
            if (requestId != null) {
                message.getMessageProperties().setHeader(REQUEST_ID_HEADER, requestId);
            }
            return message;
        };
    }

    /** 把上面的 MPP 挂到 Boot 自动装配的 RabbitTemplate 上（发布前统一加头）。 */
    @Bean
    public RabbitTemplateCustomizer submitRequestIdTemplateCustomizer(MessagePostProcessor submitRequestIdPostProcessor) {
        return template -> template.addBeforePublishPostProcessors(submitRequestIdPostProcessor);
    }

    /**
     * 批量消费容器工厂（交卷消费者专用）：broker 侧按批拉取（batchSize 条/批，上限受 prefetch 约束），
     * 手动 ack 由消费者在落库成功后逐条确认——削峰批量落库（rewriteBatchedStatements）的前提。
     *
     * <p>并发显式化（此前用 new 手工构造、从未 setConcurrentConsumers，实际并发=1）：
     *  concurrency 默认 1，与改动前实际行为一致，不借"修配置"之名隐式提速。</p>
     *
     * <p>容量模型（可复算）：吞吐 ≈ 并发 × batchSize / 单批落库耗时。
     *  <ul>
     *    <li>batchSize=100，单批(100条)落库耗时 T：单并发线程吞吐 = 100/T 条/s，清空 5000 条需 5000×T/100 秒；</li>
     *    <li>例：T=0.5s → 200 条/s，5000 条约 25s；T=1s → 100 条/s，约 50s（均指单并发、重试≈0 的理想曲线）；</li>
     *    <li>并发 ↑ ⇒ 吞吐近似线性 ↑，但每个并发线程落库各占一条写连接，必须 < 连接池上限：dev 主库 20 / 从库 10，建议并发 ≤ 4~8；</li>
     *    <li>调整优先级：先抬 batchSize（每批更大分摊解析/往返开销）再抬 prefetch（保证每并发有足够批可拉）；</li>
     *    <li>⚠ 数值仅模型推导，真实瓶颈必须等独立压测提案验证，本变更不给实测定稿。 </li>
     *  </ul></p>
     *
     * <p>配置陷阱：手工构造的工厂只认此处 @Value；spring.rabbitmq.listener.simple.* 仅作用于 Boot 自动装配的
     * rabbitListenerContainerFactory，对本工厂无效 ⇒ RABBIT_CONCURRENCY / RABBIT_PREFETCH 对交卷消费者不生效，
     * 交卷容量只由下方 exam.taking.mq.* 旋钮控制。</p>
     */
    @Bean
    public SimpleRabbitListenerContainerFactory batchContainerFactory(
            ConnectionFactory connectionFactory, MessageConverter submitMessageConverter,
            @Value("${exam.taking.mq.batch-size:100}") int batchSize,
            @Value("${exam.taking.mq.prefetch:200}") int prefetch,
            @Value("${exam.taking.mq.concurrency:1}") int concurrency) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(submitMessageConverter);
        factory.setAcknowledgeMode(org.springframework.amqp.core.AcknowledgeMode.MANUAL);
        factory.setPrefetchCount(prefetch);
        factory.setBatchListener(true);
        factory.setConsumerBatchEnabled(true);
        factory.setBatchSize(batchSize);
        // 并发与最大并发同取一个旋钮：默认 1 保持改动前行为，不加动态伸缩，避免掉扩容窗口外突刺
        factory.setConcurrentConsumers(concurrency);
        factory.setMaxConcurrentConsumers(concurrency);
        return factory;
    }
}

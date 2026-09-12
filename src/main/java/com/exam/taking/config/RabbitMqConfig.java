package com.exam.taking.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
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
 *   <li>死信兜底——重试超过阈值进死信队列，可人工排查（spec「失败进死信」场景）。</li>
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
     * 批量消费容器工厂（交卷消费者专用）：broker 侧按批拉取（batchSize 条/批，上限受 prefetch 约束），
     * 手动 ack 由消费者在落库成功后逐条确认——削峰批量落库（rewriteBatchedStatements）的前提。
     */
    @Bean
    public SimpleRabbitListenerContainerFactory batchContainerFactory(
            ConnectionFactory connectionFactory, MessageConverter submitMessageConverter,
            @Value("${exam.taking.mq.batch-size:100}") int batchSize,
            @Value("${exam.taking.mq.prefetch:200}") int prefetch) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(submitMessageConverter);
        factory.setAcknowledgeMode(org.springframework.amqp.core.AcknowledgeMode.MANUAL);
        factory.setPrefetchCount(prefetch);
        factory.setBatchListener(true);
        factory.setConsumerBatchEnabled(true);
        factory.setBatchSize(batchSize);
        return factory;
    }
}

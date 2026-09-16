package com.exam.submission.mq;

import com.exam.common.RequestIdFilter;
import com.exam.monitoring.metrics.BusinessMetrics;
import com.exam.submission.dto.SubmitMessage;
import com.exam.submission.service.ExamSubmissionService;
import com.exam.taking.config.RabbitMqConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 交卷消息消费者（MQ 削峰的消费端，spec「交卷削峰落库」需求）：
 *
 * <ul>
 *   <li>批量落库：容器按批（batchContainerFactory）投递一批消息，先整批 JDBC batch 写入
 *       （rewriteBatchedStatements 生效）；任一失败降级为逐条处理，保证好消息不受坏消息牵连；</li>
 *   <li>手动 ack：落库成功才 basicAck——未确认消息在连接断开后由 broker 自动重投（至少一次）；</li>
 *   <li>消费端幂等：casFillAnswers 仅在 answers 为 NULL 时写入，重复投递业务只执行一次
 *       （spec「重复投递幂等」场景）；</li>
 *   <li>失败重试 + 死信：逐条失败先带 x-retry-count 重发回原队列，超过阈值 basicNack 进死信队列，
 *       人工排查（spec「失败进死信」场景）。</li>
 * </ul>
 *
 * <p>请求链路透传（为什么）：交卷消息由 HTTP 线程经 ExamSubmitSender 发出、此处消费，MQ 边界两侧是不同线程；
 * SlowSqlInterceptor 与落库日志都读 MDC 的 requestId，若消费端不按消息头恢复 MDC，
 * 交卷离开 HTTP 线程后即丢失链路 id——无法从「某学生交卷」逐单追到「答案落库/慢 SQL」。
 * 因此本类在批次入口按首条消息的 x-request-id 头恢复 MDC，finally 必清理（容器线程复用防串味）。
 */
@Slf4j
@Component
public class ExamSubmitConsumer {

    private final ExamSubmissionService submissionService;
    private final ObjectMapper objectMapper;
    private final RabbitTemplate rabbitTemplate;
    private final BusinessMetrics metrics;
    private final int retryMax;

    public ExamSubmitConsumer(ExamSubmissionService submissionService, ObjectMapper objectMapper,
                              RabbitTemplate rabbitTemplate, BusinessMetrics metrics,
                              @Value("${exam.taking.mq.retry-max:3}") int retryMax) {
        this.submissionService = submissionService;
        this.objectMapper = objectMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.metrics = metrics;
        this.retryMax = retryMax;
    }

    /**
     * 批量消费入口：先整批落库，失败降级逐条处理。
     *
     * <p>MDC 恢复与清理：按批次首条消息头恢复 requestId，finally 必清理——批量容器线程会被复用，
     * 不清理则下一批日志/慢 SQL 会继承上一批的 requestId，串味后比没有更糟（指向错误请求）。
     * 首条无 header（存量消息/非 HTTP 发送）则不写入，MDC 保持无值。
     */
    @RabbitListener(queues = RabbitMqConfig.SUBMIT_QUEUE, containerFactory = "batchContainerFactory")
    public void onBatch(List<Message> messages, Channel channel) throws IOException {
        String firstRequestId = requestIdOf(messages.get(0));
        if (firstRequestId != null) {
            MDC.put(RequestIdFilter.MDC_KEY, firstRequestId);
        }
        try {
            try {
                List<SubmitMessage> parsed = new ArrayList<>(messages.size());
                for (Message message : messages) {
                    parsed.add(parse(message));
                }
                ExamSubmissionService.FillStats stats = submissionService.fillAnswersBatch(parsed);
                for (Message message : messages) {
                    channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
                }
                // 整批 filled==0：重复投递被消费端幂等跳过（或多实例/补发扫描的可见信号），不是故障
                if (stats.filled() == 0) {
                    metrics.countSweepDuplicateDetected("sweep");
                }
                log.info("交卷批量落库: 批次={} 落库={} 幂等跳过={}", messages.size(), stats.filled(), stats.skipped());
                return;
            } catch (Exception e) {
                log.warn("交卷批量落库失败，降级逐条处理: 批次={} 原因={}", messages.size(), e.getMessage());
            }
            for (Message message : messages) {
                handleOne(message, channel);
            }
        } finally {
            // 批量容器线程复用：本批处理完必须清理，否则下一批会继承上一批的 requestId（串味比没有更糟）
            MDC.remove(RequestIdFilter.MDC_KEY);
        }
    }

    /** 逐条处理：成功即 ack；失败带计数重发（未超阈值）或 nack 进死信（重试耗尽）。 */
    private void handleOne(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        // 逐条降级：批次粒度的一次性 MDC 已不可信（同批来自多个请求），按该条自己的 requestId 覆盖；
        // 无 header 的旧消息被覆盖为空，属预期——该条本就无链路 id 可追溯。
        String ownRequestId = requestIdOf(message);
        if (ownRequestId != null) {
            MDC.put(RequestIdFilter.MDC_KEY, ownRequestId);
        } else {
            MDC.remove(RequestIdFilter.MDC_KEY);
        }
        try {
            SubmitMessage msg = parse(message);
            ExamSubmissionService.FillStats stats = submissionService.fillAnswersBatch(List.of(msg));
            if (stats.filled() == 0) {
                // 0 行 = 重复投递（已落库，幂等跳过）或答卷缺失（重试无意义）：均不再重试。
                // 重复投递被消费端幂等跳过 = 多实例/补发扫描的可见信号（不是故障）
                metrics.countSweepDuplicateDetected("sweep");
                log.warn("交卷消息落库 0 行（重复投递或答卷缺失）: submission={}", msg.getSubmissionId());
            }
            channel.basicAck(tag, false);
        } catch (Exception e) {
            int retried = retryCountOf(message);
            if (retried < retryMax) {
                republishWithRetryCount(message, retried + 1);
                channel.basicAck(tag, false);   // 原消息确认，由重发消息继续重试
                metrics.recordMqRetry("retried");
                log.warn("交卷消息处理失败，第 {}/{} 次重试: tag={} 原因={}",
                        retried + 1, retryMax, tag, e.getMessage());
            } else {
                // 进死信前先计数：exhausted 与 entered 都要有，告警看 entered，劣化看 retried
                metrics.recordMqRetry("exhausted");
                metrics.countDlqEntered();
                channel.basicNack(tag, false, false);   // 重试耗尽 → 经 DLX 进死信队列，人工排查
                log.error("交卷消息重试耗尽，进入死信队列: tag={} retry={}", tag, retried, e);
            }
        }
    }

    /** 重发回原队列并递增重试计数（持久化投递，与原消息同路由）。 */
    private void republishWithRetryCount(Message message, int nextRetry) {
        message.getMessageProperties().setHeader(RabbitMqConfig.RETRY_HEADER, nextRetry);
        rabbitTemplate.send(RabbitMqConfig.SUBMIT_EXCHANGE, RabbitMqConfig.SUBMIT_ROUTING_KEY, message);
    }

    /** 读消息头里的请求链路 id；无头（存量消息）返回 null，调用方不得写入空值。 */
    private String requestIdOf(Message message) {
        Object requestId = message.getMessageProperties().getHeader(RabbitMqConfig.REQUEST_ID_HEADER);
        return requestId instanceof String s ? s : null;
    }

    private int retryCountOf(Message message) {
        Object count = message.getMessageProperties().getHeader(RabbitMqConfig.RETRY_HEADER);
        return count instanceof Number number ? number.intValue() : 0;
    }

    private SubmitMessage parse(Message message) {
        try {
            return objectMapper.readValue(message.getBody(), SubmitMessage.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("交卷消息反序列化失败", e);
        }
    }
}

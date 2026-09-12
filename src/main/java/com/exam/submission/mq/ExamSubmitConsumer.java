package com.exam.submission.mq;

import com.exam.submission.dto.SubmitMessage;
import com.exam.submission.service.ExamSubmissionService;
import com.exam.taking.config.RabbitMqConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
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
 */
@Slf4j
@Component
public class ExamSubmitConsumer {

    private final ExamSubmissionService submissionService;
    private final ObjectMapper objectMapper;
    private final RabbitTemplate rabbitTemplate;
    private final int retryMax;

    public ExamSubmitConsumer(ExamSubmissionService submissionService, ObjectMapper objectMapper,
                              RabbitTemplate rabbitTemplate,
                              @Value("${exam.taking.mq.retry-max:3}") int retryMax) {
        this.submissionService = submissionService;
        this.objectMapper = objectMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.retryMax = retryMax;
    }

    /** 批量消费入口：先整批落库，失败降级逐条处理。 */
    @RabbitListener(queues = RabbitMqConfig.SUBMIT_QUEUE, containerFactory = "batchContainerFactory")
    public void onBatch(List<Message> messages, Channel channel) throws IOException {
        try {
            List<SubmitMessage> parsed = new ArrayList<>(messages.size());
            for (Message message : messages) {
                parsed.add(parse(message));
            }
            ExamSubmissionService.FillStats stats = submissionService.fillAnswersBatch(parsed);
            for (Message message : messages) {
                channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
            }
            log.info("交卷批量落库: 批次={} 落库={} 幂等跳过={}", messages.size(), stats.filled(), stats.skipped());
            return;
        } catch (Exception e) {
            log.warn("交卷批量落库失败，降级逐条处理: 批次={} 原因={}", messages.size(), e.getMessage());
        }
        for (Message message : messages) {
            handleOne(message, channel);
        }
    }

    /** 逐条处理：成功即 ack；失败带计数重发（未超阈值）或 nack 进死信（重试耗尽）。 */
    private void handleOne(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        try {
            SubmitMessage msg = parse(message);
            ExamSubmissionService.FillStats stats = submissionService.fillAnswersBatch(List.of(msg));
            if (stats.filled() == 0) {
                // 0 行 = 重复投递（已落库，幂等跳过）或答卷缺失（重试无意义）：均不再重试
                log.warn("交卷消息落库 0 行（重复投递或答卷缺失）: submission={}", msg.getSubmissionId());
            }
            channel.basicAck(tag, false);
        } catch (Exception e) {
            int retried = retryCountOf(message);
            if (retried < retryMax) {
                republishWithRetryCount(message, retried + 1);
                channel.basicAck(tag, false);   // 原消息确认，由重发消息继续重试
                log.warn("交卷消息处理失败，第 {}/{} 次重试: tag={} 原因={}",
                        retried + 1, retryMax, tag, e.getMessage());
            } else {
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

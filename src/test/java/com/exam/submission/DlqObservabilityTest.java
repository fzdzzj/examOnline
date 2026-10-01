package com.exam.submission;

import com.exam.monitoring.metrics.BusinessMetrics;
import com.exam.submission.service.ExamSubmissionService;
import com.exam.submission.mq.ExamSubmitConsumer;
import com.exam.taking.config.RabbitMqConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 死信可观测性：用真实 SimpleMeterRegistry 读计数，断言重试耗尽路径埋点正确。
 */
@ExtendWith(MockitoExtension.class)
class DlqObservabilityTest {

    private static final String PAYLOAD =
            "{\"submissionId\":1,\"examId\":10,\"studentId\":100,\"submitType\":1,"
                    + "\"submitTime\":\"2026-09-12T10:00:00\",\"answers\":\"{}\"}";

    @Mock private ExamSubmissionService submissionService;
    @Mock private RabbitTemplate rabbitTemplate;

    private SimpleMeterRegistry registry;
    private BusinessMetrics metrics;
    private ExamSubmitConsumer consumer;
    private Channel channel;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        ObjectProvider<RabbitAdmin> noAdmin = new ObjectProvider<>() {
            @Override public RabbitAdmin getObject() { return null; }
            @Override public RabbitAdmin getObject(Object... args) { return null; }
        };
        metrics = new BusinessMetrics(registry, noAdmin);
        ObjectMapper om = new ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        consumer = new ExamSubmitConsumer(submissionService, om, rabbitTemplate, metrics, 3);
        channel = mock(Channel.class);
    }

    @Test
    @DisplayName("重试耗尽：basicNack + exam.mq.dlq.entered / exam.mq.retry{exhausted} 递增")
    void exhaustedIncrementsDlqCountersAndNacks() throws Exception {
        doThrow(new RuntimeException("db down")).when(submissionService).fillAnswersBatch(anyList());
        Message message = new Message(PAYLOAD.getBytes(), new MessageProperties());
        message.getMessageProperties().setDeliveryTag(99L);
        message.getMessageProperties().setHeader(RabbitMqConfig.RETRY_HEADER, 3);

        consumer.onBatch(List.of(message), channel);

        verify(channel).basicNack(eq(99L), eq(false), eq(false));
        assertEquals(1.0, registry.find("exam.mq.dlq.entered").counter().count(), 0.001);
        assertEquals(1.0, registry.find("exam.mq.retry").tag("outcome", "exhausted").counter().count(), 0.001);
        assertEquals(0.0, registry.find("exam.mq.retry").tag("outcome", "retried").counter() == null
                ? 0.0
                : registry.find("exam.mq.retry").tag("outcome", "retried").counter().count(), 0.001);
    }

    @Test
    @DisplayName("未超阈值重试：exam.mq.retry{retried} 递增，不进死信计数")
    void retriedIncrementsRetryCounterOnly() throws Exception {
        doThrow(new RuntimeException("db down")).when(submissionService).fillAnswersBatch(anyList());
        Message message = new Message(PAYLOAD.getBytes(), new MessageProperties());
        message.getMessageProperties().setDeliveryTag(7L);
        // 无 retry 头 → 0 < 3，走重发

        consumer.onBatch(List.of(message), channel);

        verify(channel).basicAck(eq(7L), eq(false));
        assertEquals(1.0, registry.find("exam.mq.retry").tag("outcome", "retried").counter().count(), 0.001);
        assertEquals(0.0, registry.find("exam.mq.dlq.entered").counter().count(), 0.001);
    }
}

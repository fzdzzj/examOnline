package com.exam.submission.mq;

import com.exam.submission.dto.SubmitMessage;
import com.exam.submission.service.ExamSubmissionService;
import com.exam.taking.config.RabbitMqConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 交卷消费者单元测试（spec「消息可靠落库」「重复投递幂等」「失败进死信」场景）：
 * 纯 Mockito 验证 批量 ack / 降级重试 / 死信 nack 的确认语义。
 */
@ExtendWith(MockitoExtension.class)
class ExamSubmitConsumerTest {

    private static final String PAYLOAD =
            "{\"submissionId\":1,\"examId\":10,\"studentId\":100,\"submitType\":1,"
                    + "\"submitTime\":\"2026-09-12T10:00:00\",\"answers\":\"{}\"}";

    @Mock
    private ExamSubmissionService submissionService;
    @Mock
    private RabbitTemplate rabbitTemplate;

    /** 与生产口径一致：Spring 的 ObjectMapper 注册了 JavaTimeModule（支持 LocalDateTime 往返）。 */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private ExamSubmitConsumer consumer;
    private Channel channel;

    @BeforeEach
    void setUp() {
        consumer = new ExamSubmitConsumer(submissionService, objectMapper, rabbitTemplate, 3);
        channel = mock(Channel.class);
    }

    private Message message(long deliveryTag) {
        Message message = new Message(PAYLOAD.getBytes(), new MessageProperties());
        message.getMessageProperties().setDeliveryTag(deliveryTag);
        return message;
    }

    /** 批量落库成功：逐条 ack，不重发不进死信。 */
    @Test
    void batchSuccessAcksAll() throws Exception {
        when(submissionService.fillAnswersBatch(anyList()))
                .thenReturn(new ExamSubmissionService.FillStats(2, 0));

        consumer.onBatch(List.of(message(1), message(2)), channel);

        verify(channel).basicAck(eq(1L), eq(false));
        verify(channel).basicAck(eq(2L), eq(false));
        verify(channel, never()).basicNack(anyLong(), eq(false), eq(false));
        verify(rabbitTemplate, never()).send(anyString(), anyString(), any(Message.class));
    }

    /** 批量失败降级逐条：单条成功照常 ack。 */
    @Test
    void batchFailureFallsBackPerMessage() throws Exception {
        when(submissionService.fillAnswersBatch(anyList()))
                .thenThrow(new RuntimeException("db down"))
                .thenReturn(new ExamSubmissionService.FillStats(1, 0));

        consumer.onBatch(List.of(message(7)), channel);

        verify(channel).basicAck(eq(7L), eq(false));
        verify(channel, never()).basicNack(anyLong(), eq(false), eq(false));
    }

    /** 单条失败未超阈值：带 x-retry-count 重发回原队列并 ack 原消息。 */
    @Test
    void failureBelowThresholdRepublishesWithRetryHeader() throws Exception {
        doThrow(new RuntimeException("db down")).when(submissionService).fillAnswersBatch(anyList());

        consumer.onBatch(List.of(message(9)), channel);

        org.mockito.ArgumentCaptor<Message> captor = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(eq(RabbitMqConfig.SUBMIT_EXCHANGE),
                eq(RabbitMqConfig.SUBMIT_ROUTING_KEY), captor.capture());
        assertEquals(1, ((Number) captor.getValue().getMessageProperties()
                .getHeader(RabbitMqConfig.RETRY_HEADER)).intValue());
        verify(channel).basicAck(eq(9L), eq(false));
        verify(channel, never()).basicNack(anyLong(), eq(false), eq(false));
    }

    /** 重试耗尽：basicNack 不重入队 → 经 DLX 进死信队列，不再重发。 */
    @Test
    void exhaustedRetriesGoToDeadLetter() throws Exception {
        Message exhausted = message(11);
        exhausted.getMessageProperties().setHeader(RabbitMqConfig.RETRY_HEADER, 3);
        doThrow(new RuntimeException("db down")).when(submissionService).fillAnswersBatch(anyList());

        consumer.onBatch(List.of(exhausted), channel);

        verify(channel).basicNack(eq(11L), eq(false), eq(false));
        verify(rabbitTemplate, never()).send(anyString(), anyString(), any(Message.class));
    }

    /** 消息载荷可正确反序列化（LocalDateTime/答案字段往返无损）。 */
    @Test
    void payloadRoundTrip() throws Exception {
        when(submissionService.fillAnswersBatch(anyList()))
                .thenAnswer(invocation -> {
                    SubmitMessage parsed = ((List<SubmitMessage>) invocation.getArgument(0)).get(0);
                    assertEquals(1L, parsed.getSubmissionId());
                    assertEquals(100L, parsed.getStudentId());
                    assertEquals(LocalDateTime.of(2026, 9, 12, 10, 0, 0), parsed.getSubmitTime());
                    return new ExamSubmissionService.FillStats(1, 0);
                });

        consumer.onBatch(List.of(message(1)), channel);
        verify(channel).basicAck(eq(1L), eq(false));
    }
}

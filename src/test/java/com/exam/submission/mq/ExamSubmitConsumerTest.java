package com.exam.submission.mq;

import com.exam.common.RequestIdFilter;
import com.exam.monitoring.metrics.BusinessMetrics;
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
import org.slf4j.MDC;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    @Mock
    private BusinessMetrics metrics;

    /** 与生产口径一致：Spring 的 ObjectMapper 注册了 JavaTimeModule（支持 LocalDateTime 往返）。 */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private ExamSubmitConsumer consumer;
    private Channel channel;

    @BeforeEach
    void setUp() {
        consumer = new ExamSubmitConsumer(submissionService, objectMapper, rabbitTemplate, metrics, 3);
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
        verify(metrics, never()).countSweepDuplicateDetected(anyString());
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
        verify(metrics, never()).countSweepDuplicateDetected(anyString());
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

    /** traceId 透传：消息头带 requestId 时，消费处理期间 MDC 可见（SlowSqlInterceptor/落库日志据此打点）。 */
    @Test
    void headerRequestIdVisibleInMdcDuringProcessing() throws Exception {
        Message m = message(1);
        m.getMessageProperties().setHeader(RabbitMqConfig.REQUEST_ID_HEADER, "req-trace-001");
        AtomicReference<String> seen = new AtomicReference<>();
        when(submissionService.fillAnswersBatch(anyList()))
                .thenAnswer(inv -> {
                    // 在消费线程内（MDC 已由批次入口恢复）断言可见
                    seen.set(MDC.get(RequestIdFilter.MDC_KEY));
                    return new ExamSubmissionService.FillStats(1, 0);
                });

        consumer.onBatch(List.of(m), channel);

        assertEquals("req-trace-001", seen.get());
        // 方法结束 finally 必清理：断言不残留，防批量容器线程复用串味
        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
    }

    /** 线程复用防串味：无 header 时不得写入（存量消息）；上一批的 requestId 残留必须被 finally 清理。 */
    @Test
    void noHeaderLeavesNoMdcResidual() throws Exception {
        // 模拟线程复用：线程上还挂着上一个批次的 requestId（最危险的串味场景）
        MDC.put(RequestIdFilter.MDC_KEY, "stale-prev-batch");
        Message m = message(1); // 无 x-request-id 头（存量消息）
        when(submissionService.fillAnswersBatch(anyList()))
                .thenAnswer(inv -> {
                    // 无 header 不得写入新的 requestId：上一批残留在处理期间保持不变（未被覆盖）
                    assertEquals("stale-prev-batch", MDC.get(RequestIdFilter.MDC_KEY));
                    return new ExamSubmissionService.FillStats(1, 0);
                });

        consumer.onBatch(List.of(m), channel);

        // 关键：finally 必清理，处理后不得残留上一批的 requestId（否则线程复用会让下一批日志指向错误请求）
        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
    }


    /** 整批 filled==0（幂等跳过）：计入 exam.sweep.duplicate_detected(task=sweep)，不是故障。 */
    @Test
    void batchIdempotentSkipCountsSweepDuplicate() throws Exception {
        when(submissionService.fillAnswersBatch(anyList()))
                .thenReturn(new ExamSubmissionService.FillStats(0, 1));

        consumer.onBatch(List.of(message(3)), channel);

        verify(channel).basicAck(eq(3L), eq(false));
        verify(metrics).countSweepDuplicateDetected("sweep");
        verify(channel, never()).basicNack(anyLong(), eq(false), eq(false));
    }

    /** 逐条降级路径 filled==0：同样计入 sweep 重复扫描信号。 */
    @Test
    void perMessageIdempotentSkipCountsSweepDuplicate() throws Exception {
        when(submissionService.fillAnswersBatch(anyList()))
                .thenThrow(new RuntimeException("batch failed"))
                .thenReturn(new ExamSubmissionService.FillStats(0, 1));

        consumer.onBatch(List.of(message(5)), channel);

        verify(channel).basicAck(eq(5L), eq(false));
        verify(metrics).countSweepDuplicateDetected("sweep");
    }
    /** 工厂并发/批量/prefetch 取值真实落到工厂字段上，与配置一致（防配置漂移：改错参数被压测才暴露）。
     *  Factory 无 getter，配置是私有字段；直接反射断言（容器对象无消息监听器无法创建，故不断言容器）。 */
    @Test
    void batchContainerFactoryAppliesConfiguredCapacity() throws Exception {
        RabbitMqConfig config = new RabbitMqConfig();
        SimpleRabbitListenerContainerFactory factory = config.batchContainerFactory(
                mock(ConnectionFactory.class),
                config.submitMessageConverter(new ObjectMapper()),
                100, 200, 1);

        assertEquals(1, ((Number) getField(factory, "concurrentConsumers")).intValue());
        assertEquals(1, ((Number) getField(factory, "maxConcurrentConsumers")).intValue());
        assertEquals(100, ((Number) getField(factory, "batchSize")).intValue());
        assertEquals(200, ((Number) getField(factory, "prefetchCount")).intValue());
        assertEquals(AcknowledgeMode.MANUAL, getField(factory, "acknowledgeMode"));
        assertEquals(Boolean.TRUE, getField(factory, "consumerBatchEnabled"));
        assertEquals(Boolean.TRUE, getField(factory, "batchListener"));
    }

    /** 生产端透传：模板级 beforePublishPostProcessors 把 MDC 的 requestId 写入消息头；MDC 为空不写（兼容存量消息）。 */
    @Test
    void requestIdPostProcessorWritesHeaderOnlyWhenMdcSet() throws Exception {
        MessagePostProcessor mpp = new RabbitMqConfig().submitRequestIdPostProcessor();

        MDC.put(RequestIdFilter.MDC_KEY, "req-prod-007");
        try {
            Message withHeader = new Message("payload".getBytes(), new MessageProperties());
            mpp.postProcessMessage(withHeader);
            assertEquals("req-prod-007",
                    withHeader.getMessageProperties().getHeader(RabbitMqConfig.REQUEST_ID_HEADER));
        } finally {
            MDC.remove(RequestIdFilter.MDC_KEY);
        }

        Message noMdc = new Message("payload".getBytes(), new MessageProperties());
        mpp.postProcessMessage(noMdc);
        assertNull(noMdc.getMessageProperties().getHeader(RabbitMqConfig.REQUEST_ID_HEADER));
    }

    /** 接线覆盖（补测试缺口）：集成测试里 RabbitTemplate 是 @MockitoBean，自定义器从未真正挂载过——
     *  仅断言 MPP 本身正确不足以证明"生产端真会写头"。此处用真实 RabbitTemplate 断言 customizer
     *  确把 MPP 加进 beforePublishPostProcessors（这是发布前写 x-request-id 的前提）。 */
    @Test
    void templateCustomizerWiresPostProcessorIntoRealTemplate() {
        RabbitMqConfig config = new RabbitMqConfig();
        MessagePostProcessor mpp = config.submitRequestIdPostProcessor();
        RabbitTemplate template = new RabbitTemplate(mock(ConnectionFactory.class));

        config.submitRequestIdTemplateCustomizer(mpp).customize(template);

        assertTrue(template.getBeforePublishPostProcessors().contains(mpp),
                "RabbitTemplateCustomizer 未把 requestId MPP 挂到模板上，生产端将不再写 x-request-id 头");
    }

    private static Object getField(Object target, String name) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name + " in " + target.getClass().getName());
    }
}

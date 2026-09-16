package com.exam.submission;

import com.exam.submission.entity.ExamDlqMessage;
import com.exam.submission.mapper.ExamDlqMessageMapper;
import com.exam.submission.service.DlqReplayService;
import com.exam.taking.config.RabbitMqConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 死信有界重投单元测试：mock RabbitTemplate/RabbitAdmin，断言重投头语义与 PARKED/clamp。
 */
@ExtendWith(MockitoExtension.class)
class DlqReplayServiceTest {

    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private RabbitAdmin rabbitAdmin;
    @Mock private ExamDlqMessageMapper mapper;
    @Mock private ObjectProvider<RabbitAdmin> rabbitAdminProvider;

    private DlqReplayService service;

    @BeforeEach
    void setUp() {
        when(rabbitAdminProvider.getIfAvailable()).thenReturn(rabbitAdmin);
        service = new DlqReplayService(rabbitTemplate, rabbitAdminProvider, mapper,
                new ObjectMapper(), 3, 100, 2000L);
    }

    private Message deadMessage(int retry, int replay, String requestId) {
        MessageProperties props = new MessageProperties();
        props.setHeader(RabbitMqConfig.RETRY_HEADER, retry);
        props.setHeader(DlqReplayService.REPLAY_HEADER, replay);
        props.setHeader(RabbitMqConfig.REQUEST_ID_HEADER, requestId);
        return new Message("{\"submissionId\":1}".getBytes(), props);
    }

    @Test
    @DisplayName("正常重投：x-retry-count 归零、x-replay-count+1、x-request-id 保留、落 REPLAYED")
    void replayResetsRetryIncrementsReplayKeepsRequestId() {
        Message msg = deadMessage(3, 1, "req-dlq-1");
        when(rabbitTemplate.receive(eq(RabbitMqConfig.SUBMIT_DLQ), anyLong())).thenReturn(msg).thenReturn(null);
        when(rabbitAdmin.getQueueInfo(RabbitMqConfig.SUBMIT_DLQ)).thenReturn(null);

        DlqReplayService.ReplayReport report = service.replayOnce(1);

        assertEquals(1, report.replayed());
        assertEquals(0, report.parked());
        ArgumentCaptor<ExamDlqMessage> row = ArgumentCaptor.forClass(ExamDlqMessage.class);
        verify(mapper).insert(row.capture());
        assertEquals(ExamDlqMessage.STATUS_REPLAYED, row.getValue().getStatus());
        assertEquals(3, row.getValue().getRetryCount());
        assertEquals(1, row.getValue().getReplayCount());

        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(eq(RabbitMqConfig.SUBMIT_EXCHANGE),
                eq(RabbitMqConfig.SUBMIT_ROUTING_KEY), sent.capture());
        assertEquals(0, ((Number) sent.getValue().getMessageProperties()
                .getHeader(RabbitMqConfig.RETRY_HEADER)).intValue());
        assertEquals(2, ((Number) sent.getValue().getMessageProperties()
                .getHeader(DlqReplayService.REPLAY_HEADER)).intValue());
        assertEquals("req-dlq-1", sent.getValue().getMessageProperties()
                .getHeader(RabbitMqConfig.REQUEST_ID_HEADER));
    }

    @Test
    @DisplayName("超限：不重投、落 PARKED")
    void overLimitParksWithoutSend() {
        Message msg = deadMessage(3, 3, "req-park");
        when(rabbitTemplate.receive(eq(RabbitMqConfig.SUBMIT_DLQ), anyLong())).thenReturn(msg).thenReturn(null);
        when(rabbitAdmin.getQueueInfo(RabbitMqConfig.SUBMIT_DLQ)).thenReturn(null);

        DlqReplayService.ReplayReport report = service.replayOnce(1);

        assertEquals(0, report.replayed());
        assertEquals(1, report.parked());
        ArgumentCaptor<ExamDlqMessage> row = ArgumentCaptor.forClass(ExamDlqMessage.class);
        verify(mapper).insert(row.capture());
        assertEquals(ExamDlqMessage.STATUS_PARKED, row.getValue().getStatus());
        verify(rabbitTemplate, never()).send(any(), any(), any(Message.class));
    }

    @Test
    @DisplayName("receive 返回 null：报告全 0 且无异常")
    void emptyQueueReportsZeros() {
        when(rabbitTemplate.receive(eq(RabbitMqConfig.SUBMIT_DLQ), anyLong())).thenReturn(null);
        when(rabbitAdmin.getQueueInfo(RabbitMqConfig.SUBMIT_DLQ)).thenReturn(null);

        DlqReplayService.ReplayReport report = service.replayOnce(5);

        assertEquals(0, report.replayed());
        assertEquals(0, report.parked());
        assertEquals(0, report.remaining());
        verify(mapper, never()).insert(any(ExamDlqMessage.class));
        verify(rabbitTemplate, never()).send(any(), any(), any(Message.class));
    }

    @Test
    @DisplayName("max 被 clamp 到 replay-max")
    void maxClampedToReplayMax() {
        // replay-max=100：请求 1000 时最多循环 100 次；此处让每次 receive 都空，验证只调 receive 100 次
        when(rabbitTemplate.receive(eq(RabbitMqConfig.SUBMIT_DLQ), anyLong())).thenReturn(null);
        when(rabbitAdmin.getQueueInfo(RabbitMqConfig.SUBMIT_DLQ)).thenReturn(null);

        service.replayOnce(1000);

        verify(rabbitTemplate, times(1)).receive(eq(RabbitMqConfig.SUBMIT_DLQ), anyLong());
        // 空队列时第一次 null 即 break，故 times=1；另用专用 service 验证 clamp 上限
        DlqReplayService tight = new DlqReplayService(rabbitTemplate, rabbitAdminProvider, mapper,
                new ObjectMapper(), 3, 2, 2000L);
        Message m1 = deadMessage(0, 0, "a");
        Message m2 = deadMessage(0, 0, "b");
        Message m3 = deadMessage(0, 0, "c");
        when(rabbitTemplate.receive(eq(RabbitMqConfig.SUBMIT_DLQ), anyLong()))
                .thenReturn(m1, m2, m3, null);
        DlqReplayService.ReplayReport r = tight.replayOnce(50);
        assertEquals(2, r.replayed());
        verify(mapper, times(2)).insert(any(ExamDlqMessage.class));
    }
}

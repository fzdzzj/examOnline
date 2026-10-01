package com.exam.submission.mq;

import com.exam.submission.dto.SubmitMessage;
import com.exam.support.RabbitTemplateInvokeStubs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * ExamSubmitSender 单测（fix-broker-confirm-and-dlq-roundtrip，收口遗留 #10）：
 *
 * <p>为什么此前没有单测：这条路径长期只被「mock 掉 RabbitTemplate 的集成测试」覆盖，
 * 而 mock 上 waitForConfirmsOrDie 是无声 no-op——作用域错误在测试里永远是绿的，
 * 直到真 dev 实例启动对账时抛 IllegalStateException 才暴露（遗留 #10 的根因）。
 * 本单测以"真实执行 invoke callback"的桩把发送与等待的调用关系钉死：
 * 发送必须与 confirm 等待在同一个 invoke 作用域内。
 *
 * <p>注意本单测依然不能证明真 broker 行为（mock 终究是 mock）——真环境结论以
 * {@code docs/} 下的真 dev 实例验证记录为准（spec-delta「mock 证据不得冒充实测」）。
 */
class ExamSubmitSenderTest {

    private static final long TIMEOUT_MS = 5000;

    private RabbitTemplate rabbitTemplate;
    private ExamSubmitSender sender;
    private SubmitMessage message;

    @BeforeEach
    void setUp() {
        rabbitTemplate = mock(RabbitTemplate.class);
        sender = new ExamSubmitSender(rabbitTemplate, TIMEOUT_MS);
        message = new SubmitMessage(500L, 1L, 42L, 1, LocalDateTime.now(), "{}");
        // 桩让 mock 的 invoke 真实执行 callback：callback 内的 convertAndSend / waitForConfirmsOrDie
        // 才会被调用，verify 才有意义
        RabbitTemplateInvokeStubs.runInvokeCallbacks(rabbitTemplate);
    }

    @Test
    @DisplayName("发送成功：invoke 作用域内先发消息再等 confirm，两者参数正确")
    void sendPublishesThenWaitsInsideInvokeScope() {
        sender.send(message);

        verify(rabbitTemplate).invoke(any(RabbitTemplate.OperationsCallback.class));
        verify(rabbitTemplate).convertAndSend(
                eq("exam.submit.exchange"), eq("exam.submit"), eq(message));
        verify(rabbitTemplate).waitForConfirmsOrDie(TIMEOUT_MS);
    }

    @Test
    @DisplayName("confirm 等待必须在 invoke 作用域内：先发布后等待、缺一不可")
    void confirmWaitOnlyInsideInvoke() {
        sender.send(message);

        // invoke 必须恰好一次：发送与等待整体在一个作用域内，而不是分别裸调
        verify(rabbitTemplate).invoke(any(RabbitTemplate.OperationsCallback.class));
        // 等待必须发生（防止有人删掉等待让 send 变成 fire-and-forget）
        verify(rabbitTemplate).waitForConfirmsOrDie(anyLong());
    }

    @Test
    @DisplayName("confirm 未通过（超时/nack）：包装为 AmqpException 抛出，携带 submissionId 且保留原因")
    void confirmFailureWrapsAsAmqpException() {
        AmqpException cause = new AmqpException("nack or timeout");
        doThrow(cause).when(rabbitTemplate).waitForConfirmsOrDie(anyLong());

        AmqpException thrown = assertThrows(AmqpException.class, () -> sender.send(message));

        assertTrue(thrown.getMessage().contains("500"),
                "异常消息应携带 submissionId 以便对账定位，实际=" + thrown.getMessage());
        assertEquals(cause, thrown.getCause(), "原始失败原因必须保留（调用方日志/指标依赖它）");
    }

    @Test
    @DisplayName("发布失败：同样包装为 AmqpException，携带 submissionId")
    void publishFailureWrapsAsAmqpException() {
        AmqpException cause = new AmqpException("connection refused");
        doThrow(cause).when(rabbitTemplate)
                .convertAndSend(anyString(), anyString(), any(Object.class));

        AmqpException thrown = assertThrows(AmqpException.class, () -> sender.send(message));

        assertTrue(thrown.getMessage().contains("500"), "异常消息应携带 submissionId");
        assertEquals(cause, thrown.getCause());
    }

    @Test
    @DisplayName("confirm 超时取自配置：构造注入的 timeout 原样传给等待调用")
    void confirmTimeoutComesFromConfig() {
        ExamSubmitSender customTimeoutSender = new ExamSubmitSender(rabbitTemplate, 250L);

        customTimeoutSender.send(message);

        verify(rabbitTemplate).waitForConfirmsOrDie(250L);
    }
}

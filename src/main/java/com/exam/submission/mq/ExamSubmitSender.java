package com.exam.submission.mq;

import com.exam.submission.dto.SubmitMessage;
import com.exam.taking.config.RabbitMqConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 交卷消息发送器（MQ 削峰的生产端）：
 * 消息持久化投递后同步等待 broker confirm（application.yml publisher-confirm-type=correlated），
 * 未确认（超时/nack/连接异常）抛出异常——调用方据此走"答案暂存草稿 + 对账补发"的自愈路径，
 * 保证已交卷的答案最终必落库（spec「消息可靠落库」场景）。
 */
@Slf4j
@Component
public class ExamSubmitSender {

    private final RabbitTemplate rabbitTemplate;
    private final long confirmTimeoutMs;

    public ExamSubmitSender(RabbitTemplate rabbitTemplate,
                            @Value("${exam.taking.submit.mq-confirm-timeout-ms:5000}") long confirmTimeoutMs) {
        this.rabbitTemplate = rabbitTemplate;
        this.confirmTimeoutMs = confirmTimeoutMs;
    }

    /** 发送交卷消息并等待 confirm；任何失败以运行时异常抛出。 */
    public void send(SubmitMessage message) {
        try {
            // requestId 头由 RabbitMqConfig 注册在共享模板上的 beforePublishPostProcessors 统一写入（为空不写）；
            // 为什么在模板级而非本方法内 4 参 convertAndSend 重载：冻结的集成测试验证 3 参调用，
            // 4 参重载在 mock 上被视为不同方法（详见 RabbitMqConfig.submitRequestIdPostProcessor 注释）。
            rabbitTemplate.convertAndSend(RabbitMqConfig.SUBMIT_EXCHANGE,
                    RabbitMqConfig.SUBMIT_ROUTING_KEY, message);
            // correlated confirm 模式：同步等待 broker 落队确认（等待的是本连接待确认集合，偏保守但可靠）
            rabbitTemplate.waitForConfirmsOrDie(confirmTimeoutMs);
        } catch (Exception e) {
            throw new AmqpException("交卷消息未被 broker 确认: submission=" + message.getSubmissionId(), e);
        }
        log.info("交卷消息已确认: submission={} exam={} student={}",
                message.getSubmissionId(), message.getExamId(), message.getStudentId());
    }
}

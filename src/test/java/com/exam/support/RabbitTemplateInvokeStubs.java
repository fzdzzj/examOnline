package com.exam.support;

import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * mock RabbitTemplate 的 invoke() 桩（fix-broker-confirm-and-dlq-roundtrip）。
 *
 * <p>为什么需要：{@code ExamSubmitSender} 修复后，「发送 + waitForConfirmsOrDie 等待」整体移入
 * {@code RabbitTemplate.invoke()} 作用域（作用域外的等待直接抛 IllegalStateException——真 broker
 * 下该缺陷曾让启动期补发对账从未成功，即遗留 #10）。Mockito 对 invoke 的默认行为是返回 null、
 * 不执行 callback，callback 内的 3 参 convertAndSend 就不会被 mock 记录，既有集成测试的
 * verify 断言会失配。本桩让 invoke 真实执行 callback，断言本身不变。
 *
 * <p>为什么 lenient：同一个测试类里不是每个用例都会触发 MQ 发送（如纯 401/400 用例），
 * 严格桩会在这些用例上报 UnnecessaryStubbing。
 *
 * <p>为什么不用 doAnswer：效果等价，lenient().when() 与既有测试的打桩风格一致。
 */
public final class RabbitTemplateInvokeStubs {

    private RabbitTemplateInvokeStubs() {
    }

    /** 对 mock RabbitTemplate 打桩：invoke(callback) 直接以该 mock 执行 callback 并返回其结果。 */
    public static void runInvokeCallbacks(RabbitTemplate rabbitTemplate) {
        lenient().when(rabbitTemplate.invoke(any(RabbitTemplate.OperationsCallback.class)))
                .thenAnswer(invocation -> invocation.getArgument(0, RabbitTemplate.OperationsCallback.class)
                        .doInRabbit(rabbitTemplate));
    }
}

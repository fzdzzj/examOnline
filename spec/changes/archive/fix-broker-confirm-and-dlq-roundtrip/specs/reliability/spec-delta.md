# 规范差异：reliability（发布确认作用域与死信真往返）

本文件包含对 `spec/specs/reliability/spec.md` 的规范变更（新增）。

## ADDED Requirements

### Requirement: 发布确认作用域与死信真往返

WHEN 交卷消息经 RabbitMQ 发布与消费,

系统 SHALL 在真 broker 下保证发布确认调用合法、启动期补发对账可完成、死信可往返，且 SHALL NOT 仅凭 mock 证据声称上述能力已验证。

#### Scenario: 真 broker 启动对账无异常

GIVEN 真 dev 实例（MySQL、RabbitMQ、Redis）启动

WHEN 启动期答案补发对账执行

THEN 无 IllegalStateException

AND 待补发消息实际送达消费侧

#### Scenario: confirm 调用作用域合法

GIVEN 任意需要 publisher confirm 的发布路径

WHEN 调用 waitForConfirmsOrDie 类 API

THEN 该调用处于 RabbitTemplate.invoke() 作用域内

AND 该性质有可在无真 broker 的 CI 中运行的护栏测试守住

#### Scenario: 死信真往返

GIVEN 真 broker 且发出一条必进死信的消息

WHEN 死信与重投流程走完

THEN 消息进入 DLQ 且指标可见

AND 重投后被消费或留档

AND 全程无 mock 替身

#### Scenario: mock 证据不得冒充实测

GIVEN 仅有 mock RabbitTemplate 的测试通过

WHEN 声称发布确认或死信链路能力

THEN 不得声称端到端已验证

AND 端到端结论只以真 broker 实测记录为准

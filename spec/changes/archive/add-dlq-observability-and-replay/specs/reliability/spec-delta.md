# 规范差异：reliability（死信消息的可见性与可恢复）

本文件包含对 `spec/specs/reliability/spec.md` 的规范变更（死信消息的可见性、有界重投与不丢原则，新增）。

## ADDED Requirements

### Requirement: 死信消息的可见性

WHEN 一条消息因重试耗尽进入死信队列,

系统 SHALL 使其可被观测与追溯，且 SHALL NOT 让死信队列的堆积在主队列指标上表现为"链路变好"。

#### Scenario: 死信深度可被采集

GIVEN 死信队列中有消息堆积

WHEN 指标采集端抓取

THEN 系统暴露死信队列当前深度

AND 在该队列不可查询时暴露"不可用"语义而非伪装成 0 积压

#### Scenario: 进入死信是可计数事件

GIVEN 一条消息重试耗尽进入死信

WHEN 该消息被投递到死信队列

THEN 系统递增"进入死信"计数

AND 该计数不因日志轮转而丢失

#### Scenario: 死信非空即告警

GIVEN 死信队列稳态深度应为 0

WHEN 死信队列出现消息

THEN 系统产生告警

AND 告警不等待"堆积到一定量"才触发

#### Scenario: 重试过程本身可观测

GIVEN 消息持续重试

WHEN 重试成功或重试耗尽

THEN 系统按结果分别计数

AND 可据此区分"健康"与"持续重试但侥幸成功"的链路劣化

### Requirement: 死信消息的有界重投

WHEN 运维需要重新投递死信消息,

系统 SHALL 提供有界、可审计、幂等安全的重投能力，且 SHALL NOT 以牺牲消息不丢为代价简化重投实现。

#### Scenario: 重投先留档

GIVEN 一条死信消息被取出

WHEN 系统准备重新投递

THEN 其原始报文与消息头先被持久化留档

AND 留档先于任何投递动作

#### Scenario: 重投是幂等安全的

GIVEN 一条死信消息被重复投递

WHEN 消费端处理它

THEN 业务效果只发生一次

AND 该性质由消费端幂等（仅在答案未落库时写入、状态机 CAS）保证

#### Scenario: 重投次数有上限

GIVEN 一条消息已多次重投仍失败

WHEN 其上界被达到

THEN 系统不再自动重投

AND 将其标记为待人工处置

AND 不形成"重投→再失败→再重投"的无限循环

#### Scenario: 单次重投有界

GIVEN 运维触发一次重投

WHEN 请求的批量上限超过系统允许值

THEN 实际处理量被收敛到系统上限

AND 不允许一次抽干整个死信队列

### Requirement: 死信队列不设过期与容量上限

WHEN 配置死信队列的保留策略,

系统 SHALL NOT 为其设置消息过期或最大长度，且 SHALL 以可见性与人工处置替代自动清理。

#### Scenario: 不以过期代替兜底

GIVEN 死信队列承载"最终兜底"职责

WHEN 配置其保留策略

THEN 不设置消息过期时间

AND 不设置最大长度丢弃策略

#### Scenario: 稳态深度为 0 的判据

GIVEN 死信队列的稳态深度应为 0

WHEN 其深度持续大于 0

THEN 视为交卷链路存在真实缺陷

AND 处理方式是查明原因而非清空队列

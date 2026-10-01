# 提案：交卷链路可证明性 —— traceId 透传 + 消费容量（阶段 10）

## Why

交卷链路是项目四大深水区之首，已做到「三重幂等 + MQ 削峰 + 对账补发 + 死信兜底」，但 **L3「可证明」这一层断在 MQ 边界上**。经代码核实，有两个具体缺口：

1. **traceId 未跨 MQ 边界**：`MDC.put(requestId)` 只出现在 `RequestIdFilter`（HTTP 线程）；`ExamSubmitSender.send()` 用 `convertAndSend(exchange, routingKey, message)` 投递，**不带任何 header**；`ExamSubmitConsumer` 也不恢复 MDC。而 `SlowSqlInterceptor:70` 读的恰恰是 `MDC.get(RequestIdFilter.MDC_KEY)`。
   - 后果：交卷请求一发出，落库侧的日志与慢 SQL **全部丢失 requestId**，无法从「某个学生交卷」追到「他的答案落库了没有」。所谓「0 丢单」目前只到 L2（对账扫描能发现并补发），缺 L3 的「日志可逐单追溯」。

2. **消费容量参数既没生效、也没有估算**：
   - `RabbitMqConfig.batchContainerFactory` 是用 `new SimpleRabbitListenerContainerFactory()` **手工构造**的，全项目 grep 不到 `concurrentConsumers` / `setConcurrentConsumers` ⇒ 它跑的是 Spring AMQP **默认并发 1**。
   - `application.yml` 里 `spring.rabbitmq.listener.simple.concurrency: ${RABBIT_CONCURRENCY:2}` 只作用于 Boot 自动装配的 `rabbitListenerContainerFactory`；交卷监听器显式指定了 `containerFactory = "batchContainerFactory"` ⇒ **这个 `2` 从未被交卷消费者使用**（此前项目备忘把它记成「5000 并发的硬瓶颈」，系误判，本提案一并更正）。
   - 同理 `RABBIT_PREFETCH` 也管不到批量工厂——工厂另取 `exam.taking.mq.prefetch`（当前值同为 200，但这是**两个旋钮**，改 env 不生效，属配置陷阱）。
   - 后果：唯一硬指标「批量落库 < 30s」**没有任何容量依据**，压测前不知道瓶颈在哪、该调哪个参数。

两者同属交卷 MQ 链路、写入边界高度重叠（`submission/mq` + `taking/config`），合并为一次变更，避免拆分后并发写 `RabbitMqConfig`。

**背景**：
- 项目已有 `RequestIdFilter`（`MDC_KEY = requestId`，头名 `X-Request-Id`）与 `BusinessMetrics`，透传是**接线**而非新建机制；
- 批量消费经 `batchContainerFactory`（`batch-size: 100`、`prefetch: 200`、手动 ack、`rewriteBatchedStatements`），容量模型必须围绕这套参数建。

**期望状态**：交卷请求的 requestId 一路透传到落库日志与慢 SQL；消费并发/批量参数**真正生效且可配**；给出可复算的容量模型与调参建议。

## What Changes

- **traceId 透传**：
  - `RabbitMqConfig` 新增常量 `REQUEST_ID_HEADER = "x-request-id"`；
  - **生产端接线位置（实施方案校正见文末）**：在 `RabbitMqConfig` 注册 `MessagePostProcessor`（把 `MDC.get(RequestIdFilter.MDC_KEY)` 写入 `x-request-id` 头，为空不写）并挂到 Boot 自动装配的 `RabbitTemplate` 上（`RabbitTemplateCustomizer#addBeforePublishPostProcessors`），而非在 `ExamSubmitSender` 内改用 4 参 `convertAndSend` 重载；
  - `ExamSubmitConsumer.onBatch` 入口按批次**首条**消息头恢复 MDC，`finally` 中 `MDC.remove()`；逐条降级处理时改用该条自己的 requestId。
- **消费容量显式化 + 可配**：
  - `batchContainerFactory` 显式 `setConcurrentConsumers(...)` / `setMaxConcurrentConsumers(...)`，参数化 `exam.taking.mq.concurrency`；
  - 默认值须**与改动前实际行为一致**（避免借"修配置"之名隐式提速），并在 `application.yml` 补该配置项；
  - 在工厂类注释写明「`spring.rabbitmq.listener.simple.*` 对手工构造的批量工厂无效」，消除两个旋钮的歧义。
- **容量模型（注释 + 文档）**：吞吐 ≈ 并发 × batchSize / 单批落库耗时；给出 5000 交卷场次的积压-时效推算表与调参建议（含与连接池上限的联动约束）。

## Impact

### 受影响的规范
- `spec/specs/observability/spec.md` - 新增（`ADDED`）：异步链路请求关联。
- `spec/specs/exam-taking/spec.md` - 新增（`ADDED`）：交卷落库容量与时延。

### 受影响的代码
- `submission/mq/ExamSubmitSender`、`submission/mq/ExamSubmitConsumer`、`taking/config/RabbitMqConfig`、`application.yml`、`submission/mq/ExamSubmitConsumerTest`

### 用户影响
- 无功能变化；故障排查从「只能靠对账」升级为「可按 requestId 逐单追溯」。

### API 变更
- 无。

### 需要迁移
- [ ] 数据库迁移（无新表）
- [x] 配置变更（新增 `exam.taking.mq.concurrency`）
- [ ] API 版本提升
- [x] 文档更新（本提案 + 规范 + 容量模型）

## 时间线评估

小-中：约 2-3 天（W12）。

## 风险

- **透传 header 增加消息体积** —— 缓解：一个 ≤64 字符字符串，可忽略。
- **MDC 串味** —— 批量容器线程会被复用，若忘记 `finally remove`，下一个批次会继承上一批的 requestId（比没有更糟，会指向错误请求）。缓解：强制 `finally` 清理 + 单测覆盖「无 header 时不残留」。
- **调大并发压垮 DB** —— 并发 ↑ ⇒ 落库连接占用 ↑，而 dev 主库池仅 20、从库 10（`application-dev.yml:9/36`）。缓解：容量模型必须把连接池上限作为约束写进结论，调参不只看 MQ。
- **调参不能空口** —— 本轮只给模型与建议取值，真实数字必须等压测（独立提案）验证，禁止用估算值冒充实测指标。

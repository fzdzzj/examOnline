# observability 规范

> 能力域：可观测性（阶段 8，W10）。
> 来源：`spec/changes/archive/add-performance-deepening` 合入（指标导出、自定义业务指标、指标与请求关联）+ `spec/changes/archive/add-slow-sql-and-rate-limit` 合入（慢 SQL 识别、慢 SQL 与请求关联）+ `spec/changes/archive/add-mq-trace-and-capacity` 合入（异步链路请求关联）。
> 实施注记：慢 SQL 阈值 key 为 `exam.monitor.slow-sql-threshold-ms`（默认 1000）；拆解思路是「指标定方向、日志定个案」——指标发现异常，再用 requestId 到日志里定位具体那一条。

## Requirements

### Requirement: 指标导出

WHEN 系统运行,

系统 SHALL 经 Prometheus 格式暴露指标端点，供外部采集。

#### Scenario: 指标端点可访问

GIVEN 应用已启动

WHEN 访问指标端点

THEN 返回 Prometheus 格式指标

AND 包含 JVM 与应用级指标

---

### Requirement: 自定义业务指标

WHEN 核心业务发生,

系统 SHALL 记录交卷 QPS、交卷成功率、交卷耗时、MQ 队列深度、防作弊事件计数。

#### Scenario: 交卷指标

GIVEN 学生交卷

WHEN 交卷发生

THEN 交卷计数与耗时被记录

AND 成功/失败分别计数

#### Scenario: 队列深度指标

GIVEN MQ 队列有消息

WHEN 采集指标

THEN 暴露当前队列积压深度

---

### Requirement: 指标与请求关联

WHEN 记录指标,

系统 SHALL 使业务指标可关联到请求（经 requestId），便于定位故障。

#### Scenario: 请求链路可追溯

GIVEN 一次慢请求

WHEN 排查

THEN 可经 requestId 关联指标与日志

AND 定位具体慢 SQL 或慢操作

---

### Requirement: 慢 SQL 识别

WHEN 一条 SQL 执行耗时超过配置阈值,

系统 SHALL 输出 WARN 日志，日志 SHALL 含 SQL、耗时、参数与关联 requestId。

#### Scenario: 慢 SQL 告警

GIVEN 一条 SQL 执行超过阈值（默认 1000ms）

WHEN 系统检测到超时

THEN 输出 WARN 日志

AND 日志含执行耗时、SQL 文本、参数与 requestId

#### Scenario: 正常 SQL 不告警

GIVEN 一条 SQL 在阈值内执行

WHEN 系统处理

THEN 不产生慢 SQL 日志

AND 不引入额外开销

---

### Requirement: 慢 SQL 与请求关联

WHEN 慢 SQL 发生时,

系统 SHALL 使日志的 requestId 与业务请求一致，便于从指标定位到具体请求个案。

#### Scenario: requestId 可追溯

GIVEN 一次慢请求包含慢 SQL

WHEN 排查

THEN 可经 requestId 关联指标方向与日志个案

AND 定位具体慢 SQL

---

### Requirement: 异步链路请求关联

WHEN 请求经消息队列异步处理,

系统 SHALL 使生产者线程的 requestId 透传到消费者线程，使消费端日志与慢 SQL 日志仍可关联到原始请求。

#### Scenario: 交卷请求可逐单追溯

GIVEN 学生发起交卷且该 HTTP 请求已生成 requestId

WHEN 交卷消息被消费者批量落库

THEN 落库日志携带同一 requestId

AND 该批次内的慢 SQL 日志同样携带该 requestId

#### Scenario: 无 requestId 消息不污染日志

GIVEN 消息头不含 requestId（存量消息或手工投递）

WHEN 消费者处理

THEN 消费端不写入上一批次残留的 requestId

AND 处理完成后 MDC 被清理

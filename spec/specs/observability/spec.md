# observability 规范

> 能力域：可观测性（阶段 8，W10；阶段 11，W13 补告警与面板；阶段 14 补死信指标与告警；阶段 16 补观测栈动态可验证性；2026-09-20 补锁竞争可观测与日志链路关联）。
> 来源：`spec/changes/archive/add-performance-deepening` 合入（指标导出、自定义业务指标、指标与请求关联）+ `spec/changes/archive/add-slow-sql-and-rate-limit` 合入（慢 SQL 识别、慢 SQL 与请求关联）+ `spec/changes/archive/add-mq-trace-and-capacity` 合入（异步链路请求关联）+ `spec/changes/archive/add-alerting-and-dashboards` 合入（指标驱动的告警、观测面板）+ `spec/changes/archive/add-dlq-observability-and-replay` 合入（死信队列的指标与告警覆盖）+ `spec/changes/archive/add-observability-runtime-evidence` 合入（观测栈动态可验证性）+ `spec/changes/archive/add-concurrency-monitoring` 合入（锁竞争可观测）+ `spec/changes/archive/add-distributed-tracing` 合入（日志与链路标识关联）。
> 实施注记：慢 SQL 阈值 key 为 `exam.monitor.slow-sql-threshold-ms`（默认 1000）；拆解思路是「指标定方向、日志定个案」——指标发现异常，再用 requestId 到日志里定位具体那一条。
> 观测栈注记：抓取配置与告警规则在 `docker/observability/`（独立编排片段，**不并入主 `docker-compose.yml`**）；规则只使用能从 `BusinessMetrics` 常量确定性推导的指标名，刻意不写 `hikaricp_connections_*`（dynamic-datasource 下未实测）。**静态正确性由 `AlertAssetsTest` 守住；动态行为证据见 `docs/observability-runtime-evidence.md`（Targets UP、9 条 loaded、5 条真实 firing / 4 条未点着并留 PromQL 反证、面板出图；禁止改阈值凑绿；不得声称 DLQ 端到端）。**
> 实施注记（阶段 14）：规则名 `MqDlqBacklog` / `MqSubmitRetryExhausted`；指标 `exam.mq.dlq.depth` / `exam.mq.retry` / `exam.mq.dlq.entered`；静态由 `AlertAssetsTest` 守住；告警能否响已由阶段 16 证据覆盖，真 broker 往返重投仍属遗留 #6。
> 实施注记（阶段 16）：运行证据在 `docs/observability-runtime-evidence.md`；firing：ExamOnlineDown / RateLimitDegraded / MqSubmitRetryExhausted / MqDlqBacklog / AntiCheatEventSpike；未点着：Http5xxRatioHigh / SubmitFailureRatioHigh / MqSubmitQueueBacklog / SubmitLatencyP99High。

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

---

### Requirement: 指标驱动的告警

WHEN 核心链路指标越过健康阈值,

系统 SHALL 以代码可维护的方式声明告警规则（规则纳入版本库、可随代码评审），使故障无需人工翻看指标端点即可被发现。

#### Scenario: 交卷失败率越阈值告警

GIVEN 交卷失败计数与成功计数持续上报

WHEN 失败占比超过配置阈值并持续一段时间

THEN 触发告警

AND 告警标注严重级别与可读摘要

#### Scenario: 队列积压告警

GIVEN 交卷 MQ 队列深度被持续采集

WHEN 积压超过阈值并持续一段时间

THEN 触发告警

AND 采集不可用（深度为哨兵负值）时不得误报

#### Scenario: 限流降级告警

GIVEN 限流器因 Redis 故障降级放行

WHEN 降级计数出现增量

THEN 触发告警

AND 告警可按接口维度定位

#### Scenario: 实例不可达告警

GIVEN 抓取目标为应用实例

WHEN 实例无法被抓取

THEN 触发告警

---

### Requirement: 观测面板

WHEN 需要了解系统整体健康,

系统 SHALL 以代码可维护的方式（随版本库分发）提供观测面板，使指标以可读视图呈现，而非要求人工拼查询。

#### Scenario: 面板随栈自动就绪

GIVEN 观测栈按仓库内配置启动

WHEN 打开观测面板

THEN 面板已自动加载、无需手工导入

AND 所引用的数据源可直接取数（面板与数据源标识一致）

#### Scenario: 覆盖核心链路

GIVEN 面板已加载

WHEN 查看总览

THEN 可看到交卷量与成功率、交卷时延分位、MQ 队列积压、限流降级、HTTP 5xx 与 JVM 资源

AND 可按应用维度筛选

---

### Requirement: 死信队列的指标与告警覆盖

WHEN 系统为异步链路配置指标与告警,

系统 SHALL 覆盖消息的**最终去处**（死信队列），而不仅覆盖其入队处，且 SHALL 保证新增告警所用的指标名可从代码常量确定性推导。

#### Scenario: 最终去处有指标

GIVEN 交卷消息经主队列进入死信队列

WHEN 指标采集端抓取

THEN 主队列与死信队列各有独立的深度指标

AND 消息离开主队列进入死信后，死信深度指标反映该事实

#### Scenario: 规则引用的指标名必须可推导

GIVEN 新增一条告警规则

WHEN 校验其表达式引用的指标名

THEN 每个自定义业务指标名都能在指标常量定义处找到

AND 该一致性由自动化测试守住

#### Scenario: 指标不可用时告警不误报

GIVEN 死信队列深度指标在队列不可查询时返回哨兵值

WHEN 告警规则求值

THEN 规则排除该哨兵值

AND 不因指标不可用而产生误报

#### Scenario: 观测面板覆盖死信

GIVEN 观测面板展示交卷异步链路

WHEN 查看面板

THEN 死信队列深度有独立展示格

AND 面板引用的数据源与数据源配置一致

---

### Requirement: 观测栈动态可验证性

WHEN 观测栈与应用同时运行,

系统 SHALL 使告警规则与总览面板能在**真实抓取**下被观察，且 SHALL 将观察结果作为可复查证据保留，且 SHALL NOT 通过改低阈值或改 PromQL 来制造 firing。

#### Scenario: 抓取目标可达

GIVEN 应用在宿主暴露 Prometheus 指标端点

AND 观测栈已启动

WHEN 查看 Prometheus 抓取目标

THEN job=exam-online 状态为 UP

#### Scenario: 规则已加载

GIVEN 告警规则文件已被 Prometheus 加载

WHEN 查看规则组

THEN 现有全部业务告警规则（含死信两条）均 loaded

AND 规则健康而非 no data 永久静默

#### Scenario: 能触发的规则至少 firing 一次

GIVEN 一条告警的触发条件可用运维动作或真实请求达成（且不修改规则阈值）

WHEN 条件持续满足其 for 窗口

THEN Prometheus 将该告警置于 firing

AND 该次 firing 被记录进运行证据

#### Scenario: 点不着的规则保持诚实

GIVEN 一条告警的阈值在本机健康流量下无法达到（如交卷 P99 远低于 2s）

WHEN 无法在不破坏规则语义的前提下使其 firing

THEN 不得修改 expr 或 for 来凑绿

AND 证据中记录即时 PromQL 结果与未 firing 的原因

#### Scenario: 总览面板出图

GIVEN Grafana 已配置 Prometheus 数据源

WHEN 打开 Exam Online 总览面板

THEN 面板查询成功

AND 至少系统存活与运行时相关格展示时间序列（无业务流量时可以为 0，但不能整页 No data）

#### Scenario: 证据可复查

GIVEN 完成一次动态验证

WHEN 查阅仓库中的运行证据

THEN 能看到每条规则 firing 或未点着的结论

AND 不得据此声称死信链路真 broker 端到端已验收

---

### Requirement: 锁竞争可观测

WHEN 交卷链路获取 Redis 分布式锁,

系统 SHALL 记录本次等待时长、锁获取成功次数与锁竞争失败次数，供外部采集端计算分位。

#### Scenario: 亚秒等待不得丢失

GIVEN SETNX 的正常等待本就是毫秒级

WHEN 上报一次耗时小于 1 秒的锁等待

THEN 该次等待以纳秒精度计入，累计值不得为 0

AND 禁止经"秒→long"的有损换算落点（会把亚秒全部截成 0）

#### Scenario: 分位数可读

GIVEN 验收口径为锁等待 P95 小于 100 毫秒

WHEN 注册该耗时指标

THEN 分桶必须覆盖毫秒到秒段（默认桶在 10ms 之后直接跳到 8s，不足以支撑该判定）

AND 缺该等分桶时不得声称"P95 已可判定"

#### Scenario: 竞争与成功分别计数

GIVEN 多实例并发抢同一把锁

WHEN 一次抢锁失败

THEN 竞争失败计数递增

AND 不叠加到获取成功计数上

---

### Requirement: 日志与链路标识关联

WHEN 请求进入 MVC 处理阶段,

系统 SHALL 把当前 OpenTelemetry Span 的 traceId 写入日志上下文并回写响应头，使日志可跳查链路。

#### Scenario: traceId 取自真实 SpanContext

GIVEN 一次经 starter 装配的 HTTP 请求

WHEN 写入日志上下文

THEN 该 id 等于当前 Span 的 traceId（32 位小写 hex）

AND 不得使用自行生成的随机值（那样拿去追踪后端查不到任何链路）

#### Scenario: 不重复开启服务端 Span

GIVEN starter 的过滤器已为该请求开启 server span

WHEN MVC 拦截器处理同一请求

THEN 拦截器只读取上下文，不再创建 Span

AND 不得留下未关闭的作用域泄漏给同线程的下一次请求

#### Scenario: 不回显客户端追踪头

GIVEN 客户端提交了自己的追踪头

WHEN 构造响应

THEN 响应头只承载服务端产出的 hex traceId

AND 额外头不得因回显客户端串而出现（与请求标识同类的响应头注入面）

---

### Requirement: 指标基数有界

WHEN 为计数器或直方图打标签,

系统 SHALL 只使用取值集合有限的维度（如结果状态），不得用无上限的业务标识作标签。

#### Scenario: 高基数字段被拒作标签

GIVEN 考试 ID 这类取值随业务量无限增长的字段

WHEN 把它作为指标标签

THEN 时间序列数量随业务量线性膨胀，内存与查询成本失控

AND 应改为按有限枚举（如 `status`）聚合，个体明细回到返回值或审计表查

---

> 合入注记（2026-09-20，`add-concurrency-monitoring`）：指标为 `exam.submit.lock.wait`（Timer，
> 显式 1ms–1s 分桶）/ `exam.submit.lock.acquisitions` / `exam.submit.lock.contentions`；
> 重复扫描计数 `exam.sweep.duplicate_detected` 早已由阶段 13 提供。**未合入部分**：
> 原提案的 Grafana 面板与"P95 > 200ms 告警"仍未做（机器上存在 `sport-verify-grafana` 容器，
> 当前 exited，属可解锁而非不可验证）。
> 合入注记（2026-09-20，`add-distributed-tracing`）：SDK 与 server span 由
> `opentelemetry-spring-boot-starter` 单一装配（曾自建 `OpenTelemetry` Bean 顶掉其自动装配，
> 导致应用无法启动，已删）；日志 pattern 增列 `[%X{traceId:-}]`，非 HTTP 线程无 span 时留空。
> **未合入部分**：从未对活着的 Jaeger 跑通端到端（测试 profile 置 `otel.traces.exporter=none`），
> 原提案的"服务依赖拓扑图""Span 层级逐层打点"未实现，故不作为需求写入。

# reliability 规范

> 能力域：可靠性（阶段 8，W10；限流器降级阶段 10；定时扫描多实例安全阶段 13；死信可见性与有界重投阶段 14；2026-09-20 补分页入参上限）。
> 来源：`spec/changes/archive/add-slow-sql-and-rate-limit` 合入（核心接口限流、分布式一致性、限流粒度）+ `spec/changes/archive/add-rate-limit-resilience` 合入（限流器降级、降级可观测）+ `spec/changes/archive/add-multi-instance-sweep-safety` 合入（定时扫描多实例安全、不引入调度锁的取舍）+ `spec/changes/archive/add-dlq-observability-and-replay` 合入（死信消息的可见性、有界重投、死信队列不设过期与容量上限）+ `spec/changes/archive/add-api-rate-limiting` 合入（分页入参上限；其全局限流器已撤回，见文末注记）+ `spec/changes/archive/fix-broker-confirm-and-dlq-roundtrip` 合入（发布确认作用域与死信真往返，提案⑦，2026-09-22）。
> 实施注记：超限返回 `ResponseCode.TOO_MANY_REQUESTS`（1008 → HTTP 429），由 `GlobalExceptionHandler` 统一转换。
> 实施注记：限流依赖（Redis）异常时默认 **fail-open 放行**（`exam.ratelimit.fail-open`，默认 true），并打 ERROR 日志 + 递增 `exam.ratelimit.degraded` 计数器；置 false 则异常上抛（fail-close）。
> 实施注记（阶段 13）：定时扫描正确性靠下游幂等（CAS + 唯一索引 + INSERT IGNORE + 消费端 casFillAnswers），**刻意不加分布式调度锁**；重复扫描指标 `exam.sweep.duplicate_detected`（tag `task`=`sweep`/`state-advance`，含消费者 `filled==0`）；交卷锁按 token 解锁（`RedisLockHelper` Lua compare-and-delete，`exam.taking.submit.lock-ttl-seconds` 默认 30）。
> 实施注记（阶段 14）：重投是加速手段，正确性仍靠消费端幂等 + 补发对账；`RabbitTemplate#receive` autoAck 取出与落档之间的毫秒级崩溃窗口可接受；**禁止常驻 DLQ 消费者**（会死循环或丢消息）。指标 `exam.mq.dlq.depth`（不可用返回 -1 哨兵）/ `exam.mq.retry`（tag outcome）/ `exam.mq.dlq.entered`；管理端点 `POST /api/admin/mq/dlq/replay`（ADMIN）。

## Requirements

### Requirement: 核心接口限流

WHEN 访问交卷、拉卷、抽题等核心接口,

系统 SHALL 按接口维度限流（Redis 令牌桶），超限 SHALL 返回 429。

#### Scenario: 限流生效

GIVEN 接口令牌桶已配置容量与速率

WHEN 请求速率超过令牌桶放行能力

THEN 超出请求返回 429

AND 放行请求正常处理

#### Scenario: 峰值可抗

GIVEN 5000 人同时拉卷或交卷

WHEN 系统限流

THEN 后端以配置速率平滑放行

AND 数据库与 MQ 不被瞬时峰值打垮

---

### Requirement: 分布式一致性

WHEN 多实例部署,

系统 SHALL 使限流桶在实例间共享（令牌桶计数存 Redis，Lua 脚本原子操作）。

#### Scenario: 多实例共享桶

GIVEN 两个应用实例服务同一接口

WHEN 两者限流

THEN 共享同一 Redis 令牌桶

AND 总放行速率等于配置值而非翻倍

#### Scenario: 原子操作

GIVEN 并发请求同时扣减令牌

WHEN Lua 脚本执行

THEN 令牌扣减原子完成

AND 不出现超发

---

### Requirement: 限流粒度

WHEN 配置限流,

系统 SHALL 按接口粒度（非用户粒度），各核心接口 SHALL 可独立设定容量与速率。

#### Scenario: 接口独立限流

GIVEN 交卷、拉卷、抽题三个接口

WHEN 配置限流

THEN 每个接口有独立的桶容量与放行速率

AND 互不影响

---

### Requirement: 限流器降级

WHEN 限流依赖（Redis）不可用,

系统 SHALL 以可配置的降级策略保持核心接口可用（默认 fail-open 放行），且 SHALL 区分「业务超限」与「限流器降级」两种情形。

#### Scenario: Redis 异常时放行

GIVEN 令牌桶因 Redis 异常无法判定

WHEN 请求命中带 @RateLimit 的核心接口

THEN 请求被放行

AND 记录降级 ERROR 日志

#### Scenario: 业务超限不降级

GIVEN 令牌桶判定桶已空

WHEN 请求命中核心接口

THEN 抛 TOO_MANY_REQUESTS（429）

AND 不记为降级

#### Scenario: 可切回 fail-close

GIVEN 配置为 fail-close

WHEN 令牌桶因 Redis 异常无法判定

THEN 异常上抛，请求失败

AND 行为与降级前一致

---

### Requirement: 降级可观测

WHEN 限流器发生降级,

系统 SHALL 记录降级计数指标，使降级状态可被外部告警发现。

#### Scenario: 降级指标可采集

GIVEN 限流器发生一次降级

WHEN 采集指标

THEN 降级计数器递增

AND 可按接口维度区分

---

### Requirement: 定时任务的并发与多实例安全

WHEN 同一套服务部署多个实例,

系统 SHALL 使定时扫描（考试状态推进、超时交卷兜底与答案补发对账）**在多实例并发执行下仍只产生一次业务效果**，且 SHALL 不依赖外部调度中间件来保证这一点。

#### Scenario: 并发扫描同一份超时答卷只交卷一次

GIVEN 一份已超时的进行中答卷

WHEN 两个实例的扫描同时处理它

THEN 只有一个实例完成状态迁移

AND 强制交卷只成功一次（答卷唯一、`submissionId` 唯一）

AND 同轮对账补发允许再投递，消费端幂等跳过

AND 答卷仍是唯一一条

#### Scenario: 并发扫描不重复落库

GIVEN 一条已交卷但答案未落库的答卷

WHEN 两个实例的扫描同时重新投递

THEN 答案只被写入一次

AND 重复投递被消费端幂等跳过

#### Scenario: 并发结束只标记一次缺考

GIVEN 一场考试在并发路径下被迁到已结束

WHEN 两条路径同时触发缺考标记

THEN 每个应考且无答卷的学生恰好一条缺考记录

AND 不产生重复缺考

#### Scenario: 并发推进同一场考试只迁移一次

GIVEN 一场到期未推进的考试

WHEN 两个实例同时推进

THEN 状态只迁移一次

AND 版本号只递增一次

#### Scenario: 重复扫描可被观测

GIVEN 多实例在重复处理同一批数据

WHEN 下游以幂等方式跳过这些重复处理

THEN 系统产生可采集的重复处理计数

AND 据此可判断是否需要引入调度锁，而非凭感觉决策

---

### Requirement: 不引入调度中间件与锁的取舍

WHEN 选择是否用分布式锁协调定时扫描,

系统 SHALL 以「正确性由幂等保证、锁仅用于省资源」为准则，且 SHALL NOT 让定时兜底能力依赖 Redis 等外部组件的可用性。

#### Scenario: 定时兜底不因锁而停摆

GIVEN 分布式协调组件（如 Redis）不可用

WHEN 定时扫描触发

THEN 扫描照常执行

AND 不因抢不到锁而整体跳过

#### Scenario: 幂等是正确性的唯一保证

GIVEN 调度锁失效或未启用

WHEN 多个实例并发扫描

THEN 业务效果仍只发生一次

AND 该性质由自动化测试证明

---

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

---

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

---

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

---

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

---

### Requirement: 分页入参上限

WHEN 请求分页列表接口,

系统 SHALL 拒绝超出上限的页码与页大小并返回 400，不因参数越界退化成 500 或放行超大查询。

#### Scenario: 超限页大小被拒

GIVEN 列表接口声明页大小上限为 100

WHEN 客户端请求 `size=1000`

THEN 返回 400 与字段级提示

AND 不触发对数据库的大页扫描

#### Scenario: 入参约束必须真的生效

GIVEN 控制器方法参数上写了 `@Min`/`@Max`

WHEN 校验该接口是否具备约束力

THEN 控制器需带类级 `@Validated`（否则注解只是装饰，请求照常以 200 放行）

AND 参数约束抛出的异常类型与 `@Valid @RequestBody` 不同，须有对应异常处理映射到 400

---

> 合入注记（2026-09-20，`add-api-rate-limiting`）：只合入「分页入参上限」一条。
> 该提案原拟的**全局 `/api/**` 限流器已实现后撤回**——它与既有 `@RateLimit` 体系重叠且更危险：
> 一刀切 100 QPS 会把交卷接口压到其自身 500 预算之下（恰是 5000 人交卷场景），
> 且绕开"先鉴权再限流"的既定顺序。429 的产生方仍是上文的 `@RateLimit` 令牌桶，未新增第二条路径。


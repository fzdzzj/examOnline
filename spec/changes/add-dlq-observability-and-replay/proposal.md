# 提案：死信队列的可观测与可恢复（阶段 14）

## Why

交卷 MQ 链路的**拓扑是正确的、但死信队列是个只进不出的黑洞**：它被声明、被绑定、被投递，然后**没有任何人看它、没有出口**。面试追问「消息进了死信然后呢」，目前答不上来。

### 已核实的事实（逐条可复算）

**1）拓扑完整，投递路径确实通。**

```java
// RabbitMqConfig:74-96
submitQueue()      = QueueBuilder.durable("exam.submit.queue").deadLetterExchange("exam.submit.dlx")
                                                        .deadLetterRoutingKey("exam.submit.dead").build()
submitDeadQueue()  = QueueBuilder.durable("exam.submit.dead.queue").build()
submitDeadBinding()= bind(submitDeadQueue()).to(submitDeadExchange()).with("exam.submit.dead")
```

**2）投递到死信的唯一入口已存在，且带 ERROR 日志。**

```java
// ExamSubmitConsumer:120-122
channel.basicNack(tag, false, false);   // 重试耗尽 → 经 DLX 进死信队列，人工排查
log.error("交卷消息重试耗尽，进入死信队列: tag={} retry={}", tag, retried, e);
```

**3）但"人工排查"这四个字没有任何落地物。**

- `@RabbitListener` 全仓库**只有一处**（`ExamSubmitConsumer.onBatch`，队列 = `SUBMIT_QUEUE`）→ `exam.submit.dead.queue` **无消费者**；
- `grep 'exam.submit.dead.queue' src/main` 只命中 `RabbitMqConfig` 的常量声明与 `deadQueue` bean 本身；
- 无任何指标引用它：`BusinessMetrics` 只有 6 个业务指标常量，其中 `exam.mq.submit.queue.depth` 的 Gauge 数据源是 `admin.getQueueInfo(SUBMIT_QUEUE)`——**只看主队列**；
- P3 的 7 条告警规则（`ExamOnlineDown` / `SubmitFailureRatioHigh` / `SubmitLatencyP99High` / `MqSubmitQueueBacklog` / `RateLimitDegraded` / `Http5xxRatioHigh` / `AntiCheatEventSpike`）**没有一条覆盖死信队列**。

**4）于是出现一个复合放大，而它恰好在"最需要知道"的时刻静默。**

重试是**立即重发、无退避**（`republishWithRetryCount` 直接 `rabbitTemplate.send` 回原队列，`retry-max` 默认 3）：

```java
// ExamSubmitConsumer:113-123 —— 三次重试在同一轮循环里连续发生，没有 delay
if (retried < retryMax) { republishWithRetryCount(message, retried + 1); channel.basicAck(tag, false); }
else { channel.basicNack(tag, false, false); }
```

若失败原因是 DB 短暂不可用（连接池耗尽 / 主从切换抖动），这**4 次尝试（首次 + 3 次重试）会在毫秒级内全部失败**，消息随即进死信。也就是说：**DLQ 会在故障发生的第 1 秒开始堆积，而"堆积"这件事在当前系统里完全不可见**——`MqSubmitQueueBacklog` 看的是主队列，消息离开主队列进死信后，主队列指标反而"变好看"了。这是**指标方向性错误**，比没有指标更危险。

**5）好消息：重投在业务上是幂等安全的，缺的只是入口与眼睛。**

- 消费端落库用 `casFillAnswers`（**仅在 `answers IS NULL` 时写**）；
- 后端兜底交卷走 `forceSubmitByBackend` 的**状态机 CAS**（`进行中→已交卷`，0 行即幂等返回）；
- 消息头**原样保留**在死信消息里：`x-retry-count`（可重置）、`x-request-id`（可按同一键反查原始 HTTP 请求 → 消费端日志 / 慢 SQL）。

所以「重投一条死信」不存在数据风险，可以放心做。反过来说：**做这件事的成本很低，不做这件事的代价是"可恢复"这一层永远是空谈。**

### 期望状态

1. 死信队列**深度可观测**（复用既有 Gauge 机制，零新依赖、零新组件）；
2. **"有消息进死信"是可计数的事件**，而不只是一行 ERROR 日志（日志会被轮转，计数不会）；
3. **死信非空即告警**——因为稳态下它应该恒为 0，任何非 0 都是真实缺陷，不是噪音；
4. 有**有界、可审计、幂等已证**的重投入口，且重投失败的残余风险被显式论证过；
5. **"不给死信设 TTL"这个取舍被写下来**，并说明为什么。

## What Changes

### 1. 死信深度 Gauge（复用主队列 Gauge 的同款机制）

- `BusinessMetrics` 新增 `exam.mq.dlq.depth`，数据源 `admin.getQueueInfo(SUBMIT_DLQ)`。
- 把既有的 `submitQueueDepthProvider()` 抽出参数化私有方法 `queueDepthOf(String queue)`，主队列与死信队列复用同一份实现（**不复制粘贴两段查队列逻辑**，否则下次又只改一处）。
- 语义与既有 Gauge 严格一致：**无 `RabbitAdmin` 或查询异常返回 `-1`（表示"不可用"而非"0 积压"）**——这个 `-1` 哨兵值有下游约定（告警表达式必须排掉它），必须沿用。
- **不引入 rabbitmq_exporter**：与 P3「只用能从代码常量确定性推导的指标名」一致。已有的 `RabbitAdmin` 足够。

### 2. 把"重试正在发生"和"进了死信"变成数字

- `BusinessMetrics` 新增：
  - `exam.mq.retry`（Counter，tag `outcome` = `retried` / `exhausted`）；
  - `exam.mq.dlq.entered`（Counter）。
- 埋点位置（`ExamSubmitConsumer.handleOne`）：重发成功记 `retried`；`basicNack` 前记 `exhausted` + `dlq.entered`。
- 为什么需要 `exam.mq.retry{outcome="retried"}`：只看最终结果（进没进死信）会漏掉"一直在重试但每次都侥幸成功"的链路劣化——那种情况下 DLQ 深度恒为 0，但系统已经在悬崖边上。**两个数字一起看才能区分"健康"与"勉强"**。

### 3. 告警规则 2 条 + 面板 1 格

- `MqDlqBacklog`：`exam_mq_dlq_depth > 0 and exam_mq_dlq_depth >= 0` for 5m。
  - **必须带 `>= 0 and`**：照抄既有 `MqSubmitQueueBacklog` 排除 `-1` 哨兵的手法。不排掉 `-1` 会在"无 RabbitMQ / 查询失败"时因 `-1 > 0` 为假而安全，但一旦将来改写表达式就可能误判——**沿用既有写法，保持项目内一致性**。
- `MqSubmitRetryExhausted`：`increase(exam_mq_dlq_entered_total[10m]) > 0` for 0m——**有消息进死信就报**（而不是等积压很多）。理由见上：稳态应为 0，非 0 即缺陷。
- Grafana 总览面板加一格「交卷死信队列深度」时序图。面板断言当前是 `panels.size() >= 8`，**加格安全**。
- ⚠ `AlertAssetsTest` 的 `EXPECTED_ALERTS` 是 `Set.of(...)` **全等断言**（`:109 assertEquals(EXPECTED_ALERTS, names, "告警规则集合与提案不一致")`）→ **必须同步加入这两个新规则名**，否则该测试立刻变红。这是本提案**强制触碰**的测试文件，不是可选项。
- 同时扩 `AlertAssetsTest`：断言新规则引用的指标名能在 `BusinessMetrics` 常量集中查到（新指标必须加进该测试的白名单，否则"规则引用了不存在的指标"这类问题会因 no data 永久静默——这正是 P3 定下的护栏）。

### 4. 重投入口：先留档、再重投（有界 + 可审计 + 残余风险显式化）

新增 `DlqReplayService.replayOnce(int max)` + `@RequireRole(ADMIN)` 的管理端点 `POST /api/admin/mq/dlq/replay?max=N`。

单条死信的处理顺序（**顺序就是设计**）：

1. 从 `SUBMIT_DLQ` `receive` 取一条（带 receive timeout，取不到即结束本轮）；
2. **立刻把原始报文 + 全部 headers 持久化到新表 `exam_dlq_messages`**（`payload` / `headers_json` / `retry_count` / `replay_count` / `status` / `created_time`）；
3. 若 `replay_count < exam.mq.dlq.max-replay`（默认 3）→ `status = REPLAYED`，重发到 `SUBMIT_EXCHANGE` / `SUBMIT_ROUTING_KEY`，并**重置 `x-retry-count=0`、递增 `x-replay-count`、保留 `x-request-id`**；
   否则 → `status = PARKED`，**不重投**，留给人工（避免"重投→再失败→再进死信→再重投"的无限循环）；
4. 返回 `ReplayReport {replayed, parked, remaining}`，`remaining` 取自 `admin.getQueueInfo(SUBMIT_DLQ).getMessageCount()`。

**边界与残余风险（诚实写明，这是本提案最该被追问的地方）：**

- `RabbitTemplate.receive()` 是 **autoAck 取走**（消息离开队列即被确认）。因此「取出」与「落档 INSERT」之间存在一个**毫秒级的崩溃窗口，此时这条死信会永久丢失**。
- **为什么这个窗口可接受**：死信重投是**加速手段，不是数据正确性的唯一保证**。一条交卷死信对应的答卷，同时还在被 `ExamSweepService` 的补发对账扫描（`status = 2 AND answers IS NULL`）持续兜底——**答案最终落库不依赖 DLQ 重投成功**。真正不可丢的东西（答卷、答案、成绩）都在 DB 里，且有独立兜底链路；DLQ 里放的是"下一条消息会再兜一次"的东西。
- **不接受的做法（明确写出来，避免后人"顺手优化"）**：为了消除这个窗口而引入一个常驻 `@RabbitListener` 消费 DLQ。那会立刻带来新问题：死信队列的消费者**必须 ack 每一条**，否则 `basicNack(requeue=true)` 会让同一条消息被立即重投给同一个消费者，形成 CPU 空转的死循环；而"超限不重投"的消息若用 `basicNack(requeue=false)` 丢掉，则 DLQ 反而成了丢数据的地方。要正确处理就得再引入"死信的死信"或 broker 侧 shovel——**复杂度远超收益**。管理端点触发的、有界的、带留档的一次性重投，是这个场景下的正确粒度。
- 端点带 `max` 参数但会被 clamp 到 `exam.mq.dlq.replay-max`（默认 100），**不允许一次把队列抽干**（避免长时间持锁 + 大批量突发打回主队列）。

### 5. 不给死信队列设 TTL / max-length（写下来的取舍）

- 不设 `x-message-ttl`：**给死信设 TTL 等于把"最终兜底"改造成"延迟丢弃"**，与"不丢消息、可恢复"的目标直接矛盾。
- 不设 `max-length`：超限时 broker 会丢弃旧消息（或按 `x-overflow` 拒绝新发布），两种行为都在**无声地丢数据**，性质与上一条相同。
- 收敛方式改为：**深度 Gauge + 非空即告警 + 有界重投 + 留档表**。即"让它可见、让它可处理"，而不是"让它自动消失"。
- 由此推出的运维约定写进注释：**死信队列的稳态深度应为 0**。持续非 0 说明交卷链路存在真实缺陷（而不是"队列有点积压而已"），必须查因而非清空。

## Impact

### 受影响的规范
- `spec/specs/reliability/spec.md` — 新增（`ADDED`）：死信消息的可见性、有界重投与不丢原则。
- `spec/specs/observability/spec.md` — 新增（`ADDED`）：死信队列的指标与告警覆盖。

### 受影响的文件（写入边界）
- 修改 `src/main/java/com/exam/monitoring/metrics/BusinessMetrics.java`（新增 1 Gauge + 2 Counter，抽 `queueDepthOf(queue)`）
- 修改 `src/main/java/com/exam/submission/mq/ExamSubmitConsumer.java`（**仅**在重发/`basicNack` 处加计数埋点，不改重试逻辑本身）
- 新增 `src/main/java/com/exam/submission/service/DlqReplayService.java`
- 新增 `src/main/java/com/exam/submission/controller/DlqAdminController.java`（`@RequireRole(RoleHierarchy.ADMIN)`）
- 修改 `src/main/resources/schema.sql`（新增 `exam_dlq_messages` 表）
- 新增 `docker/mysql/migrations/2026-W15-add-dlq-messages.sql`
- 修改 `docker/observability/prometheus/rules/exam-online-alerts.yml`（+2 规则）
- 修改 `docker/observability/grafana/dashboards/*.json`（+1 面板格）
- 修改 `src/test/java/com/exam/observability/AlertAssetsTest.java`（`EXPECTED_ALERTS` +2、指标白名单 +3、面板断言）
- 新增 `src/test/java/com/exam/submission/DlqReplayServiceTest.java`
- 新增 `src/test/java/com/exam/submission/DlqObservabilityTest.java`

**不要触碰**：`RabbitMqConfig` 的**拓扑声明**（DLQ 已声明且绑定正确，本提案只给它出口与眼睛；仅允许补类注释）、交卷主流程（`ExamSubmitService`）、`ExamSubmitConsumer` 的重试判定逻辑、`pom.xml`（**不引入任何新依赖**）、`application.yml` 的既有键、P6 的 `RedisLockHelper`。

### 需要迁移
- [x] 数据库迁移：**新增 `exam_dlq_messages` 表** → 新库由 `schema.sql` 建全；存量库手工跑 `docker/mysql/migrations/2026-W15-add-dlq-messages.sql`
- [ ] 配置变更：新增 `exam.mq.dlq.max-replay`（默认 3）/ `exam.mq.dlq.replay-max`（默认 100）/ `exam.mq.dlq.receive-timeout-ms`（默认 2000），均带默认值，不改既有键
- [x] 文档更新（本提案 + 两个能力域规范 + `RabbitMqConfig`/`ExamSubmitConsumer` 类注释）

## 时间线评估

中：约 1.5 天（W14-W15）。其中重投服务与告警/面板各占约半天。

## 风险

- **改 `AlertAssetsTest` 是强制的**（`EXPECTED_ALERTS` 全等断言）。若实施时忘了同步，测试立刻红——这反而是好事（护栏生效）。注意 P3 的变异验证手法同样适用：本提案新增的断言应做一次"注入错误确认如期失败"的验证，证明不是空转。
- **`receive()` 的 auto-ack 崩溃窗口**已在上文显式论述并给出可接受性论证。实施时**必须**把第 2 步（落档）紧贴在 `receive` 之后，不得插入任何可能抛异常或阻塞的调用。
- **无真 broker 的端到端验证**：本项目集成测试环境用 `@MockitoBean RabbitTemplate`（无真实 RabbitMQ），Docker 未运行。因此本提案能证明的是「重投逻辑正确（mock 下逐条断言）」「留档表写入正确（H2）」「指标/规则/面板资产静态正确（`AlertAssetsTest`）」，**不能**证明「真发一条坏消息 → 真进 DLQ → 真重投回来」。这条列为遗留，**不得声称端到端已验证**（与 README 遗留第 4 条同类）。
- **`max-replay` 默认 3 与 `retry-max` 默认 3 的关系**：两者是不同层次的计数（同一队列内重试 vs 跨重投轮次），不要混用同一个 header。实施时用 `x-retry-count` 给消费者、`x-replay-count` 给重投服务，**互不覆盖**。
- **重投会不会把消息重投成"新的一轮 3 次重试"**：会，这是设计意图（重投的意义就是"再给一次机会"）。上限由 `x-replay-count` 卡住，最多 3 轮 × 4 次尝试 = 12 次，之后 `PARKED` 等人工。这个数字写进注释，避免"到底会重试几次"成为另一个说不清的点。

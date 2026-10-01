# 子 agent 提示词 —— `add-dlq-observability-and-replay`（阶段 14）

> 用法：整份复制发给子 agent。它是自包含的，不依赖任何先前对话。

---

## 任务

在 `D:\code\examOnline` 这个 Java 17 + Spring Boot 3.5.5 单体项目上，实施已审批的变更提案 `spec/changes/add-dlq-observability-and-replay/`。

**先读这三个文件，它们是本任务的唯一权威需求来源：**
- `spec/changes/add-dlq-observability-and-replay/proposal.md`（为什么做、改什么、边界与残余风险）
- `spec/changes/add-dlq-observability-and-replay/tasks.json`（4 个阶段、23 个 step，逐个做）
- `spec/changes/add-dlq-observability-and-replay/specs/observability/spec-delta.md` + `specs/reliability/spec-delta.md`（验收标准）

**提案的核心命题（一句话）**：交卷 MQ 的**死信队列拓扑是正确的，但它是个只进不出的黑洞**——无消费者、无指标、无告警、无重投；P3 的 7 条告警规则一条都没覆盖它，而 `MqSubmitQueueBacklog` 只看主队列，消息进死信后主队列指标反而"变好看"，**这是指标方向性错误**。本任务 = 给死信装上眼睛（指标/告警/面板）+ 一个出口（有界、可审计、幂等已证的重投）。

---

## 必须先核实的既有事实（提案已核实，实施时请再确认一遍）

- 拓扑：`exam.submit.exchange` → `exam.submit.queue`（`x-dead-letter-exchange` = `exam.submit.dlx`）→ `exam.submit.dead.queue`，定义在 `src/main/java/com/exam/taking/config/RabbitMqConfig.java`（`SUBMIT_DLQ` / `SUBMIT_DLX` / `SUBMIT_DEAD_ROUTING_KEY` 常量）。
- 进死信的唯一入口：`src/main/java/com/exam/submission/mq/ExamSubmitConsumer.java` 的 `handleOne` 里 `channel.basicNack(tag, false, false)`（重试耗尽分支）；重试阈值 `exam.taking.mq.retry-max` 默认 3，重试头 `x-retry-count`（`RabbitMqConfig.RETRY_HEADER`）。
- 既有指标常量在 `src/main/java/com/exam/monitoring/metrics/BusinessMetrics.java`：只有 6 个业务指标，其中 `exam.mq.submit.queue.depth` 是 Gauge，数据源 `admin.getQueueInfo(SUBMIT_QUEUE)`，查询失败返回 `-1`。
- 告警规则文件：`docker/observability/prometheus/rules/exam-online-alerts.yml`，现有 7 条：`ExamOnlineDown` / `SubmitFailureRatioHigh` / `SubmitLatencyP99High` / `MqSubmitQueueBacklog` / `RateLimitDegraded` / `Http5xxRatioHigh` / `AntiCheatEventSpike`。
- 静态校验测试：`src/test/java/com/exam/observability/AlertAssetsTest.java`，其 `EXPECTED_ALERTS` 是一个 `Set.of(...)`，在 `:109` 用 `assertEquals(EXPECTED_ALERTS, names, ...)` 做**集合全等断言**。
- 幂等基础（重投的安全性来源）：消费端 `casFillAnswers` 仅在 `answers IS NULL` 时写；后端兜底交卷走 `forceSubmitByBackend` 的状态机 CAS。

---

## 必须遵守的项目硬约定（违反任何一条都算未完成）

1. **集成测试禁止用 `@Sql` 自建表**。测试库建表只以 `src/main/resources/schema.sql` 为唯一来源（`application-test.yml` 已配 `mode: always` + `continue-on-error: false`）。写进了 `spec/README.md` 工作流第 5 条。
2. **不新增任何依赖**。`pom.xml` 不得出现 rabbitmq_exporter / rabbitmq_shovel / spring-retry / 任何新库。查询队列深度用已有的 `RabbitAdmin`。
3. **告警规则只允许引用能从 `BusinessMetrics` 常量确定性推导的指标名**（P3 定下的约定）。新指标必须先加进 `BusinessMetrics`，并同步进 `AlertAssetsTest` 的指标白名单。
4. **Gauge 的 `-1` 是"不可用"哨兵，不是"0 积压"**。新增深度 Gauge 必须沿用这个语义，告警表达式必须排除 `-1`（照抄 `MqSubmitQueueBacklog` 的写法）。
5. **不要改 `RabbitMqConfig` 的拓扑声明**。DLQ 已声明且绑定正确，本任务只给它出口与眼睛（仅允许补类注释）。
6. **不要改 `ExamSubmitConsumer` 的重试判定逻辑**，只在重发/`basicNack` 处加计数埋点。
7. **`@Transactional(rollbackFor = Exception.class)`** 是统一写法，新增事务方法照此写。
8. **不要用 `Thread.sleep` 凑时序**；并发场景用 `CountDownLatch`。
9. 强一致读（答卷详情/成绩）**不加 `@DS("slave")`**，走主库。
10. **实体字段与建表定义必须双向一致**（本项目硬约定）：MyBatis-Plus 按实体字段生成 INSERT，**实体有、表里没有的列会让写入在任何环境都失败**——阶段 12 就挖出过这种缺陷（`score_review` 缺 `created_time` 而实体有 `@TableField(fill = INSERT) createdTime`，导致复核申请接口从未成功执行过一次）。本任务新增 `exam_dlq_messages` 表：若你为它建实体并带 `createdTime` 字段（全局 `MetaObjectHandler` 会填充），建表语句里**必须**同时有 `created_time`。

---

## 写入边界（严格遵守）

**允许新增**：
- `src/main/java/com/exam/submission/service/DlqReplayService.java`
- `src/main/java/com/exam/submission/controller/DlqAdminController.java`
- `docker/mysql/migrations/2026-W15-add-dlq-messages.sql`
- `src/test/java/com/exam/submission/DlqReplayServiceTest.java`
- `src/test/java/com/exam/submission/DlqObservabilityTest.java`

**允许修改**：
- `src/main/java/com/exam/monitoring/metrics/BusinessMetrics.java`
- `src/main/java/com/exam/submission/mq/ExamSubmitConsumer.java`（仅埋点）
- `src/main/resources/schema.sql`（仅新增 `exam_dlq_messages` 表）
- `docker/observability/prometheus/rules/exam-online-alerts.yml`（+2 规则）
- `docker/observability/grafana/dashboards/` 下的总览面板 JSON（+1 格）
- `src/test/java/com/exam/observability/AlertAssetsTest.java`（**必须改**，见下）
- `src/main/java/com/exam/taking/config/RabbitMqConfig.java`（**仅类注释**）

**绝对不要触碰**：`pom.xml`、`ExamSubmitService`、`ExamSubmitConsumer` 的重试判定逻辑、`ExamSweepService`、`ExamStateMachineService`、`application.yml` 的既有键、其他任何文件。

---

## 构建与测试命令（本机环境特殊，必须用这条）

本机 `JAVA_HOME` 未设置，PATH 里 `java` 是 1.8 而 `javac` 是 21，Git Bash 的 `mvn` 脚本跑不起来（Plexus classworlds Launcher 路径错）。**必须直调 launcher**：

```bash
cd /d/code/examOnline
'D:\develop\jdk177\bin\java.exe' -classpath 'D:\develop\Maven\apache-maven-3.9.4\boot\plexus-classworlds-2.7.0.jar' -Dclassworlds.conf='D:\develop\Maven\apache-maven-3.9.4\bin\m2.conf' -Dmaven.home='D:\develop\Maven\apache-maven-3.9.4' -Dmaven.multiModuleProjectDirectory='D:\code\examOnline' org.codehaus.plexus.classworlds.launcher.Launcher -o test
```

单类跑：结尾换成 `-o test -Dtest=AlertAssetsTest -DfailIfNoTests=false`。
`-o`（离线）必须带，`.mvn/maven.config` 会自动附加 `-s maven-settings.xml`。

**开工第一件事**：先跑一次全量测试，**记下基线数字**（tests run / failures / errors）。

---

## 实施要点

**阶段 1 — 让死信可见**
- `BusinessMetrics`：把既有 `submitQueueDepthProvider()` 抽成参数化私有方法 `queueDepthOf(String queue)`，主队列与死信队列**复用同一份实现**（不要复制两段查队列逻辑）。新增 `exam.mq.dlq.depth` Gauge，数据源 `admin.getQueueInfo(RabbitMqConfig.SUBMIT_DLQ)`。
- 新增 `exam.mq.retry`（Counter，tag `outcome` = `retried` / `exhausted`）和 `exam.mq.dlq.entered`（Counter），提供 `recordMqRetry(String outcome)` / `countDlqEntered()`，仿既有 `countAntiCheatEvent` 写法。
- `ExamSubmitConsumer.handleOne`：重发成功记 `retried`；`basicNack` 之前记 `exhausted` + `countDlqEntered()`。
- 为什么也要 `outcome=retried`：只看最终结果会漏掉"一直在重试但每次都侥幸成功"的链路劣化（那种情况 DLQ 深度恒为 0，但系统已在悬崖边）。

**阶段 2 — 告警与面板（必做，且必须同步测试）**
- `MqDlqBacklog`：`expr` 形如 `(exam_mq_dlq_depth > 0 and exam_mq_dlq_depth >= 0)`，`for: 5m`。**`>= 0 and` 必须带**（排除 -1 哨兵，照抄 `MqSubmitQueueBacklog`）。
- `MqSubmitRetryExhausted`：`expr` 形如 `increase(exam_mq_dlq_entered_total[10m]) > 0`，`for: 0m`（有消息进死信就报；稳态应为 0，非 0 即真实缺陷）。
- labels/annotations 补齐，风格与既有 7 条一致。
- Grafana 总览面板加一格「交卷死信队列深度」时序图，数据源 uid 必须与 provisioning 里的（`prometheus`）一致，否则面板空白。面板断言是 `panels.size() >= 8`，加格安全。
- **`AlertAssetsTest` 是强制修改项**：`EXPECTED_ALERTS` 是 `Set.of(...)` 全等断言，必须加入上面的两个规则名，否则该测试立刻变红；同时把三个新指标名加入指标白名单。
- **变异验证（必须做并回报）**：`cp` 备份到 `target/`（gitignored）→ 分别在规则 YAML 与面板 JSON 各注入一处指标名拼写错误 → 跑 `AlertAssetsTest` 确认**如期失败** → `cp` 还原并 `grep` 确认无残留。作用：证明断言不是空转，且两路解析（YAML + JSON）都真的生效。

**阶段 3 — 有界重投（顺序就是设计）**
- `schema.sql` 新增表 `exam_dlq_messages`：`id` / `queue` / `payload` LONGTEXT / `headers_json` TEXT / `retry_count` / `replay_count` / `status` VARCHAR(16)（`REPLAYED` / `PARKED`）/ `error_message` / `created_time`，`KEY idx_dlq_status_time (status, created_time)`。同步写 `docker/mysql/migrations/2026-W15-add-dlq-messages.sql`（存量库手工执行，MySQL 8 报 Duplicate 可忽略）。
- `DlqReplayService.replayOnce(int max)` 单条死信的处理顺序：
  1. 从 `SUBMIT_DLQ` `receive` 一条（带 receive timeout，取不到即结束本轮）；
  2. **紧贴其后立刻落档** `exam_dlq_messages`——**两步之间不得插入任何可能抛异常或阻塞的调用**（这是本设计唯一的安全边界）；
  3. 若 `replay_count < exam.mq.dlq.max-replay`（默认 3）→ `status = REPLAYED`，重发到 `SUBMIT_EXCHANGE` / `SUBMIT_ROUTING_KEY`，并**重置 `x-retry-count=0`、递增 `x-replay-count`、保留 `x-request-id`**；
     否则 → `status = PARKED`，**不重投**（避免"重投→再失败→再进死信→再重投"的无限循环）；
  4. 返回 `ReplayReport {replayed, parked, remaining}`，`remaining` 取自 `admin.getQueueInfo(SUBMIT_DLQ).getMessageCount()`。
- `max` 参数 clamp 到 `exam.mq.dlq.replay-max`（默认 100），**不允许一次抽干队列**。
- `DlqAdminController`：类级 `@RequireRole(RoleHierarchy.ADMIN)`，`POST /api/admin/mq/dlq/replay?max=N`。
- **注释必须写明残余风险与为什么可接受**（这是本任务最该被追问的地方）：`RabbitTemplate.receive()` 是 **autoAck 取走**，"取出→落档 INSERT"之间有**毫秒级崩溃窗口，该条死信会永久丢失**；但死信重投是**加速手段而非数据正确性的唯一保证**——对应答卷同时仍被 `ExamSweepService` 的补发对账扫描（`status = 2 AND answers IS NULL`）兜底，答案最终落库不依赖 DLQ 重投成功。
- **注释同时写明不接受的做法**：为消除该窗口而引入常驻 `@RabbitListener` 消费 DLQ——死信消费者必须 ack 每一条，否则 `basicNack(requeue=true)` 会让同一条消息被立即重投给同一消费者形成 CPU 空转死循环；而"超限不重投"的消息用 `basicNack(requeue=false)` 丢掉又让 DLQ 成了丢数据的地方。正确处理需再引入"死信的死信"或 broker 侧 shovel，**复杂度远超收益**。
- **注释写明 `x-retry-count` 与 `x-replay-count` 是两层计数**（队列内重试 vs 跨重投轮次），不得混用；重投上限 = 3 轮 × (1+3) 次尝试 = 12 次后 `PARKED`。
- 新增配置键（均带默认值，不改既有键）：`exam.mq.dlq.max-replay`（3）、`exam.mq.dlq.replay-max`（100）、`exam.mq.dlq.receive-timeout-ms`（2000）。

**阶段 4 — 回归与诚实登记**
- `DlqObservabilityTest`：mock `submissionService` 抛异常驱动 `handleOne` 走重试路径，断言 `basicNack` 被调用且 `exam.mq.dlq.entered` / `exam.mq.retry{outcome=exhausted}` 递增——**用 `SimpleMeterRegistry` 读真实数字，不要 mock 指标**。
- `DlqReplayServiceTest`：mock `RabbitTemplate` / `RabbitAdmin`，逐条断言"正常重投（`x-retry-count` 归零、`x-replay-count` +1、`x-request-id` 保留、落 `REPLAYED`）"/"超限不重投（落 `PARKED`）"/"`receive` 返回 null 时报告全 0 且无异常"/"`max` 被 clamp"。
- 全量回归全绿，与基线对齐。
- **在 `spec/README.md` 的遗留事项追加一条**：死信队列的**真 broker 往返未验证**（本机 Docker 未运行、集成测试用 `@MockitoBean RabbitTemplate`）。**不得声称死信链路端到端已验证。**

---

## 回报格式（请严格按此回报）

1. **基线**：开工前 `tests run / failures / errors` 三个数字。
2. **改动清单**：每个文件一行，说明改了什么。
3. **新增用例清单**：用例名 + 它证明了什么（一句话）。
4. **变异验证结果**：两次注入分别失败在哪一行/什么信息，还原后 `grep` 的确认输出。
5. **收尾数字**：全量 `tests run / failures / errors` + 新增用例数。
6. **逐条对照 `tasks.json`**：每个 step 是"已完成"还是"未完成/替代做法"，未完成的**必须说明原因**，不要勾满。
7. **意外发现**：任何与提案描述不符的事实，如实写。
8. **不要自称"已验证"**：只能报告你实际跑过的命令与输出数字。特别是"真进 DLQ / 真重投回来"**没有**被验证，要明确说出来。

**禁止**：为了让测试变绿而放宽断言、注释掉用例、加 `@Disabled`，或修改 `schema.sql` 去迁就测试。

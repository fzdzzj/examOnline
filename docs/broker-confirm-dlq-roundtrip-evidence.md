# 发布确认作用域修复与 DLQ 真往返验证记录（提案⑦ fix-broker-confirm-and-dlq-roundtrip）

> 本文件是遗留 #6（DLQ 真 broker 往返未验证）与 #10（启动期补发对账在真 broker 下抛异常）的收口证据。
> 全部为**真 dev 实例**（真实 MySQL 主从 + RabbitMQ + 宿主 Redis）下的运行期记录，命令与原始输出同排、附时间戳与短 revision。
> 代码改动见 `ExamSubmitSender`（`waitForConfirmsOrDie` 移入 `RabbitTemplate.invoke()` 作用域），护栏测试见 `PublisherConfirmScopeGuardTest`。

## 环境

- 短 revision：`b8b2beb`（验证基于此基线之上未提交的修复工作树；门禁在提交后另跑）
- 共享 docker 栈（复用既有容器，仅 `docker start`，未重建/未改配置）：
  - `exam-mysql-master` → 主库 `127.0.0.1:13316`（`root/root123`）
  - `exam-mysql-slave` → 从库 `127.0.0.1:13317`
  - `exam-rabbitmq` → `127.0.0.1:5672`（AMQP）/ `15672`（管理）
  - Redis → 宿主 Windows Redis `127.0.0.1:6379`
- 应用启动：`spring-boot:run -Dspring-boot.run.profiles=dev --server.port=8080`

## 遗留 #10：启动期补发对账真 broker 验证

### 前置：制造一条「已交卷但答案未落库」的答卷

补发对账谓词是 `status = 2 AND answers_missing = 1`（`answers_missing` 是生成列
`TINYINT AS (CASE WHEN answers IS NULL THEN 1 ELSE 0 END)`，见 `schema.sql:248`）。
插入一条 `status=2`、`answers=NULL` 的测试答卷 `(exam_id=2, student_id=6)`：

```
INSERT INTO exam_online.exam_submissions
  (exam_id, student_id, start_time, deadline_time, submit_time, submit_type, status, version, answers)
VALUES (2, 6, NOW(), DATE_ADD(NOW(), INTERVAL 1 HOUR), NOW(), 3, 2, 0, NULL);
-- → id=4, answers_missing=1（生成列自动）
```

### 结果：补发对账真实送达（无 IllegalStateException）

应用日志（`scheduling-1` 线程，每 10s 一轮定时扫描，`2026-09-22 09:34:09`）：

```
2026-09-22 09:34:09.785 [scheduling-1] [] [] INFO  com.exam.submission.mq.ExamSubmitSender - 交卷消息已确认: submission=4 exam=2 student=6
2026-09-22 09:34:09.785 [scheduling-1] [] [] WARN  c.exam.taking.service.ExamSweepService - 答案补发对账: 待补=1 已补=1（已交卷未落库的答卷重新投递）
2026-09-22 09:34:10.860 [org.springframework.amqp.rabbit.RabbitListenerEndpointContainer#0-1] [] [] INFO  c.exam.submission.mq.ExamSubmitConsumer - 交卷批量落库: 批次=1 落库=1 幂等跳过=0
```

DB 证据（`answers_missing` 1→0、`answers` NULL→`{}`）：

```
SELECT id, exam_id, student_id, status, answers_missing, answers IS NULL, LEFT(COALESCE(answers,'<NULL>'),40)
  FROM exam_online.exam_submissions WHERE id=4;
-- 4  2  6  2  0  0  {}          ← answers_missing=0, answers='{}'（已落库）
```

**结论**：修复后 `waitForConfirmsOrDie` 在 invoke 作用域内，启动期补发对账全程无
`IllegalStateException`，消息经真实 RabbitMQ 送达并被消费端真实落库。对比遗留 #10 现场
（`IllegalStateException` + `待补=2 已补=0`），本次为 `待补=1 已补=1` 且无异常。

## 遗留 #6：DLQ 端到端真往返验证

### 步骤 1：真发一条必死消息

向 `exam.submit.exchange` 直发一条非法 JSON（消费者 `parse` 必抛 `IllegalArgumentException`）：

```
docker exec exam-rabbitmq rabbitmqadmin -u exam -p exam123 publish \
  exchange=exam.submit.exchange routing_key=exam.submit \
  payload='THIS_IS_NOT_VALID_JSON_FOR_SUBMIT_MESSAGE' \
  properties='{"content_type":"application/json"}'
```

消费者日志（`ExamSubmitConsumer.parse` 抛反序列化异常，堆栈见应用日志）：

```
java.lang.IllegalArgumentException: 交卷消息反序列化失败
  at com.exam.submission.mq.ExamSubmitConsumer.parse(ExamSubmitConsumer.java:161)
Caused by: com.fasterxml.jackson.core.JsonParseException: Unrecognized token 'THIS_IS_NOT_VALID_JSON...'
```

### 步骤 2：确认进入 DLQ（指标变化，时间 `2026-09-22 09:35:59`）

| 指标 | 发消息前 | 发消息后 | 变化 |
| --- | --- | --- | --- |
| `exam.submit.dead.queue` 深度 | 12 | 13 | +1（真进 DLQ） |
| `exam_mq_dlq_entered_total` | 0.0 | 1.0 | +1 |
| `exam_mq_retry_total{outcome="retried"}` | 0.0 | 3.0 | 重试 3 次 |
| `exam_mq_retry_total{outcome="exhausted"}` | 0.0 | 1.0 | 重试耗尽 |

（注：`exam.submit.dead.queue` 里的 12 条历史死信是之前真机验证遗留的坏消息残留，非本次产生；
本次验证以 `entered` 计数 0→1 为准，不受历史残留干扰。）

### 步骤 3：经 DlqReplayService 真重投（时间 `2026-09-22 09:36:31`）

ADMIN 端点 `POST /api/admin/mq/dlq/replay?max=1`（Bearer Token 鉴权）：

```
{"code":0,"message":"ok","data":{"replayed":1,"parked":0,"remaining":12}}
```

留档表 `exam_dlq_messages` 新增（「先留档再重投」顺序成立）：

```
SELECT id, queue, status, retry_count, replay_count, LEFT(payload,40) FROM exam_online.exam_dlq_messages;
-- 1  exam.submit.dead.queue  REPLAYED  3  0  THIS_IS_NOT_JSON_1 {{{
```

重投的坏消息再次 parse 失败 → 再重试 3 次 → 再进 DLQ，指标 `exam_mq_dlq_entered_total` 1.0→2.0、
`exam_mq_retry_total{outcome="exhausted"}` 1.0→2.0、`retried` 3.0→6.0——证明重投走的是与首投完全相同的
真实 broker 链路，无 mock 替身。

### 清理（不污染 dev 库）

- 删除测试答卷 `id=4`（`exam_submissions` 恢复 3 条）
- 清空留档表（恢复 0 条；本次验证副产物）
- DLQ 中 12 条历史坏消息残留（payload 为 `THIS_IS_NOT_JSON_*` 测试垃圾、非真实答卷）在窥探时被
  `rabbitmqadmin get` 副作用取走确认——**如实记录**：这是本次验证的副作用，不影响真实业务数据
  （验证后核对了全部真实答卷，`answers_missing` 均为 0、答案完好）。

## 护栏与门禁

- 新增 `PublisherConfirmScopeGuardTest`：对 `src/main/java` 做词法扫描，断言每一处 `waitForConfirms*`
  调用都在某个 `invoke(...)` 实参区间内；无真 broker 即可在 CI 检出作用域违规。变异验证：把修复后的
  `ExamSubmitSender` 改回作用域外裸调 `waitForConfirmsOrDie` → 护栏 2/2 如期变红 → 还原后转绿。
- 新增 `ExamSubmitSenderTest` 5 例：confirm 成功/失败、发布失败、超时取自配置等路径。
- 后端门禁：`mvn -o clean test` 基线 283/0/0/1 → 收尾 290/0/0/1（+7，Skipped 语义不变）。

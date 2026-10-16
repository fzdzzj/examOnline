# 阶段 2 证据留证：两处批量写入路径优化（optimize-backend-batch-write-paths）

本目录记录阶段 2 的完整证据链：后端单测**先红后绿**、三项变异 **m1/m2/m3 击红-复原-回绿**、
全量后端门禁实测，以及关键红线核对。

- 分支：`feature/optimize-backend-batch-write-paths`
- 基线 revision：`3b70e11`（阶段 0&1 立项笔）
- 受控白名单：仅 `src/main/java/com/exam/message/**` 与 `src/test/java/com/exam/message/**`；
  **零 DDL**（`schema.sql` 零 diff）、`docker/`、`openapi.yaml`、`pom.xml`、`frontend/` 均未触碰。
- 命令均在仓库根执行，留证日志为完整控制台输出的裁剪版（仅保留判定性行与结果汇总）。

---

## 一、后端单测先红后绿

### 红（实现前）

新增 / 改写的用例：
- 新增 `src/test/java/com/exam/message/service/ExamLifecycleNotificationServiceTest.java`（5 例）：
  批量分批 ≤500、成绩发布同口径、批失败降级不抛出、空收件人零写入、单收件人仍走单条 insert；
- `ReservationRemindServiceTest` 新增 `pendingReservationsFetchedOnceAcrossExams`（去 N+1：三场候选考试只批查一次）。

将两处 service 临时回退为原逐条 / 逐场实现后运行：
`mvnw.cmd -o clean test -Dtest=ExamLifecycleNotificationServiceTest,ReservationRemindServiceTest`
→ `Tests run: 9, Failures: 3, Errors: 1, Skipped: 0` **BUILD FAILURE**
- `ExamLifecycleNotificationServiceTest`：`examPublishedBatchesInChunksOfAtMost500`、
  `scorePublishedAlsoBathesAndKeepsRecipients` 击红（`Wanted but not invoked: insertBatch`）、
  `batchFailureDegradesSilentlyAndDoesNotPropagate` 击红（stub 的 `insertBatch` 不被调用 → 未投递）。
- `ReservationRemindServiceTest.pendingReservationsFetchedOnceAcrossExams`：
  `expected: <3> but was: <9>`（旧实现逐场 selectList，按 mock 返回 3 场 × 3 行 = 9 次投递）。
- 日志：`red-before-impl.log`

### 绿（实现后）

恢复实现后重跑同一命令 → `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0` **BUILD SUCCESS**。
- 日志：`green-after-impl.log`

---

## 二、变异击红留证（m1 / m2 / m3）

每项均执行：注入违规 → 跑定向用例确认变红 → 复原 → 确认回绿。

### m1 —— 批量写入破坏旁路降级 / 阻断主事务

- 注入点：`ExamLifecycleNotificationService.notifyExamPublished` 的
  `catch (Exception e)` → `catch (IllegalArgumentException e)`，使注入的 `IllegalStateException`
  不再被旁路降级捕获、向上冒泡回滚考试发布主事务。
- 击红：`mvnw.cmd -o test -Dtest=ExamLifecycleNotificationIntegrationTest#notificationFailureDoesNotBreakExamPublish`
  → `Tests run: 1, Failures: 1`；断言 `Response status expected:<200> but was:<500>`
  —— 降级语义一旦破坏，通知异常即击穿主事务。
  - 日志：`m1-degrade-red.log`
- 复原 + 回绿：`mvnw.cmd -o clean test`（同用例）→ `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0` **BUILD SUCCESS**。
  - 日志：`m1-degrade-green.log`

结论：旁路降级硬约定有集成用例覆盖，破坏即红。

### m2 —— 分批上限失效

- 注入点：`INSERT_BATCH_SIZE = 500` → `100000`，使 1200 名学生不再分批（单条巨型 INSERT）。
- 击红：`mvnw.cmd -o clean test -Dtest=ExamLifecycleNotificationServiceTest#examPublishedBatchesInChunksOfAtMost500`
  → `Tests run: 1, Failures: 1`；`Wanted 3 times: insertBatch(...) / But was 1 time`
  —— 单批 >500 被 500 分批断言击中。
  - 日志：`m2-batchcap-red.log`
- 复原 + 回绿：同命令 → `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0` **BUILD SUCCESS**。
  - 日志：`m2-batchcap-green.log`

结论：每批 ≤500 有单测覆盖，分批上限失效即红。

补充（列清单与 `schema.sql` 一致性）：不单独做列清单变异——列清单一旦与
`schema.sql` 的 `user_messages` 不一致，真实 DB 写入立即报 SQL 错并被旁路降级吞掉，
`examPublishNotifiesClassStudents`（真实 H2 链路，断言收件人各 1 条）即红。
该用例在全量门禁中为绿，构成列清单只读对齐的构造性证据（见 `full-gate.log`）。

### m3 —— 提醒批查改动波及 deliverIfAbsent 行级幂等链

- 注入点：`ReservationRemindService.deliverIfAbsent` 的 `if (claimed != 1)` → `if (false)`，
  使 `markRemindedIfNull` 影响行数=0 时仍写消息（行级幂等闸失效）。
- 击红：`mvnw.cmd -o clean test -Dtest=ReservationRemindServiceTest#deliverSkipsWhenMarkNotClaimed`
  → `Tests run: 1, Failures: 1`；`expected: <0> but was: <1>`
  —— 幂等链被破坏即被既有幂等用例击中。
  - 日志：`m3-idempotency-red.log`
- 复原 + 回绿：同命令 → `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0` **BUILD SUCCESS**。
  - 日志：`m3-idempotency-green.log`

结论：`deliverIfAbsent` 的 `markRemindedIfNull` 单行原子 claim 语义有测试覆盖，改动波及即红。

---

## 三、全量后端门禁实测

- 命令：`mvnw.cmd clean test`（仓库根）
- 结果（原始输出）：`Tests run: 565, Failures: 0, Errors: 0, Skipped: 1`，`BUILD SUCCESS`。
- 日志：`full-gate.log`
- 基线对照：`.github/ci/gate-baseline.json` 当前 `backendTests = 559`，本笔实测 **565**（净 +6：
  新增通知批量单测 5 例 + 提醒批查单测 1 例）。
  **按阶段分工，`gate-baseline.json` 的上调属阶段 3 收口笔，本阶段不修改该文件。**

---

## 四、红线核对

| 红线 | 落实 | 证据 |
|---|---|---|
| `insertBatch` 显式全列，与 `schema.sql` `user_messages` 逐列一致（禁 BaseMapper 推导） | `INSERT INTO user_messages (user_id, exam_id, title, content, read_flag, created_time)`，与建表定义去自增 `id` 后逐列对应；`@Insert` 脚本显式列，无实体推导 | `UserMessageMapper.java` diff；`examPublishNotifiesClassStudents` 真实 H2 链路绿（`full-gate.log`） |
| 每批 ≤500 | `insertInBatches` 按 `INSERT_BATCH_SIZE=500` 切片 | m2 击红 / `examPublishedBatchesInChunksOfAtMost500` |
| 旁路降级 try-catch 语义逐字不变（批失败=warn 降级不阻断主事务） | 三处 `try { ... } catch (Exception e) { log.warn ... }` 结构原样保留，仅把循环体换成批量组装 + 分批写入 | m1 击红 / `notificationFailureDoesNotBreakExamPublish` |
| 日志条数=学生数 | `log.info(... studentIds.size() ...)` 口径未改（非批数） | 源码 diff；`full-gate.log` 中「考试发布通知投递 2 条」 |
| `remind()` 批查一次 IN 取回按 examId 分组 | 单次 `selectList(in(examId, examIds).isNull(remindedTime))` + 内存 `Map<Long,List>` 分组 | `pendingReservationsFetchedOnceAcrossExams`（times(1)）/ m3 |
| `deliverIfAbsent` 行级幂等（`markRemindedIfNull` claim）逐字不动 | `deliverIfAbsent` 方法体与 `@Transactional` 边界零改动 | m3 击红；`git diff` 确认该方法无改动 |
| 测试严禁真实 `@Sql` 注解 | 新增/改写用例均走 Mockito 或继承 `IntegrationTestBase`（MockMvc+JPA/H2），无 `@Sql` | `grep -rn '@Sql' src/test` 唯一命中为既有类注释 |

### 未触碰面

- `schema.sql`：零 diff（零 DDL）；`docker/`、`openapi.yaml`、`pom.xml`、`frontend/`：零 diff。
- `notifyScoreReviewHandled`（单收件人）：保持单条 `insert` 不动（`singleRecipientReviewHandledStillUsesSingleInsert`）。
- `@Scheduled` 扫描间隔、启动补偿扫表、`@Transactional` 边界：零改动。

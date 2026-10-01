# 子 agent 提示词（第 2 轮续做）—— `add-multi-instance-sweep-safety`（阶段 13）

> 用法：整份复制发给子 agent。自包含，不依赖先前对话。
> 本轮性质：第 1 轮代码已在工作区（**未提交**），只补一处被写入边界挡住的埋点。

---

## 现状（指导 agent 已独立核 diff，不要回退第 1 轮）

工作分支：`feature/add-performance-deepening-readwrite`
HEAD 仍是 `7df48ac`。第 1 轮改动在工作区，**不要 commit、不要 reset、不要改 spec/**。

第 1 轮已核实保留：

- `RedisLockHelper` + `ExamSubmitService` 按 token 解锁 + `CacheMutexLoader` 改调助手
- `MultiInstanceSweepSafetyTest` 4 个用例；surefire `4 / 0 / 0`
- `concurrentEndMarksAbsenceOnce` 是 `forceEnd` + `autoAdvance`
- `exam.sweep.duplicate_detected` 已有；`state-advance` 埋在 `casAdvanceQuietly` CAS 0 行；`sweep` 目前只埋在 `ExamSweepService.forceSubmitOverdue` 的 catch
- `concurrentSweepForcesSubmitOnce` 对 MQ 条数用 `1..3` + `submissionId` 唯一——**本轮不要改这条断言**（sweep = 强制交卷 + 补发，提示词原「只 1 条消息」口径已证伪）

第 1 轮缺口（本轮唯一目标）：

`ExamSubmitConsumer` 的幂等跳过没埋点。`onBatch` 成功路径只打日志；`handleOne` 在 `stats.filled() == 0` 只 warn。多实例重复扫描的主信号是「补发消息被 casFillAnswers 跳过」，不在 forceSubmit 的 catch（loser 经常不抛异常）。不补这一处，`task=sweep` 会长期≈0，观测命题不成立。

---

## 硬约束

- 最多修复尝试 **1 次**。红了修一次；再红就停，把命令与输出写进回报。
- **不要跑全量测试**。只跑下面点名的类。
- **不要 commit**。
- 不要改锁、不要改 4 个并发用例的核心不变量、不要改 `schema.sql` / `pom.xml` / `docker/` / `docs/` / `spec/**`。

### 写入边界

允许修改：

- `src/main/java/com/exam/submission/mq/ExamSubmitConsumer.java`
- `src/test/java/com/exam/submission/mq/ExamSubmitConsumerTest.java`（构造多一个 `BusinessMetrics`，否则编不过）
- `src/test/java/com/exam/taking/MultiInstanceSweepSafetyTest.java`（只给 `concurrentSweepRepublishesWithoutDuplicating` 加计数增量断言）

绝对不要触碰其他文件。必须越界就停，写进回报。

---

## 你要做的事

### 1. 消费者埋点

`ExamSubmitConsumer` 构造注入已有的 `BusinessMetrics`（不要 new）。

两处都要调 `metrics.countSweepDuplicateDetected("sweep")`，注释写为什么（重复投递被消费端幂等跳过 = 多实例/补发扫描的可见信号；不是故障）：

1. **`onBatch` 整批成功路径**：`fillAnswersBatch` 之后，若 `stats.filled() == 0`（整批都是幂等跳过或答卷缺失）。`FillStats(2,0)` 这种真写入**不得**计数。
2. **`handleOne` 已有 `stats.filled() == 0` 分支**：同一调用。逐条降级时重复投递走这里，不埋则批次失败场景漏数。

不要改 ack/nack/重试/死信，不要改 `ExamSubmissionService`。

### 2. 单测

`ExamSubmitConsumerTest`：

- `new ExamSubmitConsumer(..., 3)` 补上 `BusinessMetrics` mock。
- 既有用例（批量 ack / 降级 / 重试 / 死信）必须仍绿，且真写入时 **verify never** `countSweepDuplicateDetected`。
- 新增 1 个用例：`fillAnswersBatch` 返回 `FillStats(0, 1)`（或 skipped>0 且 filled=0），断言 `countSweepDuplicateDetected("sweep")` 被调用。

### 3. 集成断言（增量，禁止绝对值）

`concurrentSweepRepublishesWithoutDuplicating` 在驱动 `onBatch` **之前**记下 `task=sweep` 计数，**之后**断言变大。

H2/Spring 上下文跨用例共享，**禁止** `assertEquals(1, count)`。用 `MeterRegistry`/`BusinessMetrics` 读 `exam.sweep.duplicate_detected` tag `task=sweep` 的 Counter。本用例会多次 `onBatch`：第一条常 `filled>0`，后续重复投递应 `filled==0` 从而加一。

### 4. 必跑命令

直调 launcher（本机不要 `mvn`）：

```bash
cd /d/code/examOnline
'D:\develop\jdk177\bin\java.exe' -classpath 'D:\develop\Maven\apache-maven-3.9.4\boot\plexus-classworlds-2.7.0.jar' -Dclassworlds.conf='D:\develop\Maven\apache-maven-3.9.4\bin\m2.conf' -Dmaven.home='D:\develop\Maven\apache-maven-3.9.4' -Dmaven.multiModuleProjectDirectory='D:\code\examOnline' org.codehaus.plexus.classworlds.launcher.Launcher -o test -Dtest=ExamSubmitConsumerTest,MultiInstanceSweepSafetyTest -DfailIfNoTests=false
```

必须贴实际结果行。目标：两家全绿；`MultiInstanceSweepSafetyTest` 仍是 4 用例。

---

## 回报（结构化）

1. 改动文件与关键行
2. 两处埋点是否都在（onBatch / handleOne）
3. 实际测试结果行
4. 意外发现
5. 未做的事

**禁止**：放宽 4 个并发用例的核心断言、加 `@Disabled`、改 `schema.sql`、commit、勾 `tasks.json`。

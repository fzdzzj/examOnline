# 提案：数据生命周期与保留策略（阶段 15）

## Why

项目有 24 张表，其中**三张是"只增不减"的诊断/幂等辅助表**，从建表那天起没有任何清理路径。这不是"暂时没做"，而是**设计上从未被考虑**——`@Scheduled` 全仓库只有两个（考试状态推进、超时交卷兜底），**没有任何一个与数据清理相关**。

### 已核实的事实（逐条可复算）

**1）三张无界增长的辅助表，且增长斜率与被削峰的量成正比。**

| 表 | 写入时机 | 增长量级 | `created_time` 索引 |
|---|---|---|---|
| `exam_behavior_logs` | 每次切屏/失焦/草稿冲突事件一条 | **最大**：一场 5000 人的考试 × 人均几十次切屏 = 十万级/场 | 无 |
| `exam_submit_dedups` | 每次交卷请求一条（幂等第一道闸） | 每生每场一条 | 无 |
| `score_audit_logs` | 每次成绩发布/撤回一条 | 每场几条 | 无 |

**2）关键取证：现有索引都无法服务"按时间全局删除"。**

```
exam_behavior_logs  → KEY idx_behavior_exam_student (exam_id, student_id)
                      KEY idx_behavior_exam_time    (exam_id, event_time)   ← 左前缀是 exam_id
exam_submit_dedups  → CONSTRAINT uk_submit_dedup UNIQUE (exam_id, student_id)  ← 仅此一个
score_audit_logs    → KEY idx_score_audit_exam (exam_id)
```

三张表的 `created_time` **都没有索引**；复合索引的最左前缀都是 `exam_id`。因此一句朴素的 `DELETE FROM exam_behavior_logs WHERE created_time < ?` 会是**全表扫描**，而且删的是最大的那张表——**这是"看起来最简单、实际最糟"的写法**。

**3）反面约束：不能给这三张表做"全局 TTL 一刀切"。**

- `exam_submit_dedups` 是**三重幂等的第一道持久化闸**。删掉一条 dedup 行，就等于把该学生的"快速幂等返回"路径撤掉（数据正确性仍由 `uk_exam_student` + 状态机 CAS 兜住，但这道闸的语义被削弱）。
- `exam_behavior_logs` 是**防作弊争议的证据**。删早了不是"清理垃圾"，是销毁证据。
- 所以清理的**正确口径不是"数据多老"，而是"它所属的考试早已终结"**。

**4）"考试何时终结"这件事，本仓库没有精确字段——但有一个安全可用、单调的代理。**

`exams` 表有 `start_time` / `end_time`（时间窗）、`status`、`updated_time`、`force_end`，**没有 `ended_time`**。

- 不能用 `updated_time`：任何更新（发布成绩、改标题）都会刷新它，**它表达的不是"结束时刻"**；
- 应当用 `end_time`（时间窗终点）作为清理判据：`end_time < now - N 天` 的考试**必然已经彻底结束**（与是否 force-end、是否已发布无关），这是单调、不会回退的；
- 代价（诚实写明）：被 `force-end` 提前结束的考试，其 `end_time` 仍在未来，所以它的辅助数据会**比必要的时间更晚**被清理。**误差方向是安全的**（晚删，不早删），这是有意选择而非疏漏。

**5）另一条已经存在但没人管的边界**：`exam_submissions` / `exam_absence` / `subjective_grades` / `score_review` 这些是**业务事实**（成绩可追溯、复核窗口、补考规则都要用），不属于清理对象。**本提案只碰"诊断性 / 幂等性辅助数据"，不碰业务事实**——这条边界必须被写下来，否则将来有人"顺手"把答卷也清了。

### 期望状态

1. 存在一条**按考试生命周期**驱动的保留策略，覆盖上述辅助表；
2. **默认不删除任何东西**：功能默认关闭；即使开启，默认也只报告候选量（dry-run）；
3. 删除**有界**：每批行数、每次运行的考试数都有上限，不产生长事务与长锁；
4. **只用一个已存在的索引**完成删除（不需要新建索引、不需要 schema 迁移）；
5. 候选量与删除量**都是数字**，且"当前策略会删多少"可以在真正删之前先算出来。

## What Changes

### 1. `RetentionService.purgeOnce()`（纯方法 + 薄 `@Scheduled` 包装）

- **先查候选考试**：`SELECT id FROM exams WHERE status >= 2 AND end_time < #{cutoff} LIMIT #{maxExams}`。
  - `status >= 2` 即「已结束 / 已批改 / 已发布」（枚举 0未开始 1进行中 2已结束 3已批改 4已发布）——**进行中的考试（status=1）一律不碰**，哪怕它的 `end_time` 已过（这是一种异常状态，交给人处理，不由清理任务替它决定）；
  - `cutoff = now - exam.retention.retention-days`（默认 **180** 天）。
- **逐个考试清理三张表**，每张表按 `exam_id` 分批删（`DELETE ... WHERE exam_id = ? LIMIT ?` 循环到 0 行或达到批数上限）：
  - **只用 `exam_id` 作为删除条件**，从而利用 `idx_behavior_exam_time` / `uk_submit_dedup` / `idx_score_audit_exam` 的**最左前缀**——**零新增索引、零 schema 迁移**；
  - 每表每批 `exam.retention.batch-size`（默认 1000）行，每次运行每表最多 `exam.retention.max-batches-per-run`（默认 20）批。
- `@Scheduled(cron = "${exam.retention.cron:0 30 3 * * *}")` 只做一件事：**调 `purgeOnce()`**。业务逻辑不写在定时方法里，这样测试可以直接调方法、不必等 cron（也与 `ExamSweepService` 的既有结构一致）。
- **任一张表的删除抛异常不中断整个任务**：记 ERROR + 计数，继续下一个考试（清理是"能清多少算多少"的维护任务，不应因为一场考试的数据异常而整体停摆）。

### 2. 两道安全闸：默认关闭 + 默认只报告

- `exam.retention.enabled`（默认 **`false`**）：定时任务是否运行。
- `exam.retention.dry-run`（默认 **`true`**）：即使启用，也只统计候选量、不执行删除。
- **启动时若 `enabled=true && dry-run=false`，打一条 WARN 日志**（含保留天数与批次上限），让"这个实例会真的删数据"这件事**不可能被静默开启**。
- 设计取舍写在注释里：这是运维决策而非业务需求，**默认必须是"什么都不做"**；而且"先 dry-run 看一遍数字再决定"本身就是本提案提供的能力。

### 3. 候选量可观测（dry-run 也有产出）

- `BusinessMetrics` 新增 `exam.retention.rows`（Counter，tag `table` + `action`）：
  - `action=candidate`：命中的候选行数（**dry-run 下唯一会递增的 action**）；
  - `action=deleted`：实际删除行数（`dry-run=true` 时恒不递增）。
- `purgeOnce()` 返回 `RetentionReport`（每表 candidates/deleted 两个数 + 处理的考试数 + 是否 dry-run），日志按 INFO 打一行汇总。
- 作用：**"当前策略会删多少"在真正删除之前就能被看到并被测试断言**——这是"删除不可逆"这件事唯一能拿出的安全措施。

### 4. 明确不清理的东西（写进规范与注释）

- **不清理业务事实**：`users` / `roles` / `permissions` / `questions` / `papers` / `paper_snapshots` / `exams` / `exam_snapshots` / `exam_submissions` / `subjective_grades` / `exam_absence` / `exam_candidates` / `score_review` / `classes` / `user_class`。
- 理由分类写明：成绩与答卷有**可追溯义务**（复核窗口、补考取分规则都要回看）、快照是**只读历史**、关系表是**当前归属**（清掉会直接改变应考名单口径，属于业务破坏）。
- 条件纳入：若 `add-dlq-observability-and-replay` 已归档（新增 `exam_dlq_messages` 表），把该表一并纳入三张表之外的清理目标；实施前先确认该表存在（**不引用不存在的表**）。

### 5. 磁盘回收不在本提案范围（写进文档）

- MySQL InnoDB 的 `DELETE` **只把页标记为可复用，不把空间归还操作系统**；文件大小不会变小。
- 真正回收需要 `OPTIMIZE TABLE` 或 `ALTER TABLE ... ENGINE=InnoDB`（**离线重写整表、期间锁表**），在在线考试系统上属于高风险窗口操作。
- 因此本提案的目标是**控制行数与查询代价**（避免全表扫描、避免索引膨胀），**不是**"腾出磁盘"。这一点明确写进 README 遗留，**不得声称"清理后磁盘释放"**。

## Impact

### 受影响的规范
- `spec/specs/data-access/spec.md` — 新增（`ADDED`）：数据保留策略与清理边界（按考试生命周期、默认不删、有界删除、业务事实不清理）。

### 受影响的文件（写入边界）
- 新增 `src/main/java/com/exam/monitoring/retention/RetentionService.java`（`purgeOnce()` + 薄 `@Scheduled`）
- 新增 `src/main/java/com/exam/monitoring/retention/RetentionReport.java`（record）
- 修改 `src/main/java/com/exam/monitoring/metrics/BusinessMetrics.java`（新增 `exam.retention.rows`）
- 修改 Mapper（已核实实际路径）：
  - `src/main/java/com/exam/exam/mapper/ExamMapper.java` — 新增 `selectRetentionCandidateExamIds(cutoff, limit)`
  - `src/main/java/com/exam/submission/mapper/ExamBehaviorLogMapper.java` — 新增 `countByExamId` / `deleteByExamIdBatch`
  - `src/main/java/com/exam/submission/mapper/ExamSubmitDedupMapper.java` — 新增 `countByExamId` / `deleteByExamIdBatch`
  - `src/main/java/com/exam/score/mapper/ScoreAuditLogMapper.java` — 新增 `countByExamId` / `deleteByExamIdBatch`
- 新增 `src/test/java/com/exam/monitoring/retention/DataRetentionIntegrationTest.java`
- 修改 `src/main/resources/application.yml`（新增 `exam.retention.*` 键，均带默认值）

**不要触碰**：`schema.sql`（**本提案不加表、不加索引、不加列**）、`pom.xml`、`docker/mysql/migrations/`（无迁移）、交卷/判分/防作弊的任何业务写入路径、`ExamSweepService`、`ExamStateMachineService`、`BusinessMetrics` 的既有指标。

### 需要迁移
- [ ] 数据库迁移（**无**——这是本提案的核心设计成果：只用既有索引，因此不需要任何 DDL）
- [x] 配置变更：新增 `exam.retention.enabled` / `dry-run` / `retention-days` / `batch-size` / `max-batches-per-run` / `cron`，均带默认值，不改既有键
- [x] 文档更新（本提案 + data-access 规范 + `RetentionService` 类注释）

## 时间线评估

中：约 1 天（W15）。写一条带 `LIMIT` 的分批删除循环 + 一个真跑 H2 的集成测试即可，无迁移、无前端、无协议改动。

## 风险

- **删除不可逆**（最高风险）。缓解：三重闸门——`enabled` 默认 false、`dry-run` 默认 true、真实删除时打 WARN；且有测试断言"dry-run 下一行都不删"。
- **测试必须真跑表**（不能用 `@Sql` 自建，项目硬约定）：本提案的用例依赖 `schema.sql` 建出的 `exam_behavior_logs` / `exam_submit_dedups` / `score_audit_logs` / `exams` 四张表，走 `IntegrationTestBase`（H2 + `schema.sql` 唯一来源）。**如果这几张表在 `schema.sql` 里其实建不出来，本用例会直接红**——这正是我们要的护栏（与"删掉 `@Sql` 后测试仍绿"是同一套判据）。
- **误删业务数据的风险来自"删错表"而非"删错时间"**。缓解：把"不清理业务事实"作为独立规范需求写下来，把"本任务只碰三张辅助表"写进类注释；并用测试断言「进行中考试（status=1）的辅助数据一行未删」。
- **`end_time` 作为终结代理的误差方向**已论证为安全（晚删不早删），且 `force-end` 场景的补偿路径是"人工或后续变更引入 `ended_time`"，本提案不引入新列（引入列 = 迁移 = 与"零 DDL"的设计成果冲突，不值当）。
- **多实例并发跑清理任务**（与 P6 同源问题）：清理删的是"已终结考试"的辅助数据，重复执行的后果是**第二次删到 0 行**（幂等），不会误删——但会有重复扫描开销。**本提案不引入调度锁**（与 P6 的结论保持一致：不引中间件、锁须 fail-open），并写进注释。

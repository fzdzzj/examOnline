# 子 agent 提示词（归档收口）—— `add-multi-instance-sweep-safety`（阶段 13）

> 用法：整份复制发给子 agent。自包含，不依赖先前对话。
> 本轮性质：**纯文档收口 + 你必须自己 commit**。代码已在 `47b1a08`，不要再改 Java / SQL / 测试。

---

## 角色与硬约束

### 写入边界（只允许这些路径）

允许写：

- `spec/specs/reliability/spec.md`
- `spec/README.md`
- `spec/changes/add-multi-instance-sweep-safety/tasks.json`（回勾；目录随后整体搬走）
- `spec/changes/add-multi-instance-sweep-safety/` → `spec/changes/archive/add-multi-instance-sweep-safety/`（`git mv` 整目录，含全部 `agent-prompt*.md`）

绝对不要触碰：

- `src/**`
- `pom.xml`、`schema.sql`、`application*.yml`、`docker/`、`docs/`
- `spec/specs/` 下除 `reliability/spec.md` 以外的文件
- `spec/changes/add-dlq-observability-and-replay/`、`add-data-retention/`
- `spec/changes/archive/` 里已有的 15 个变更

### 次数 / 回报 / 提交

- 最多尝试 **1 次**。对不上就停，不要猜着勾满。
- **不要跑 Maven / 不要跑测试**。回勾靠 grep/read。
- **本轮只有 1 个 `docs(spec)` commit**，不要空 feat。
- **你必须自己 commit**（指导 agent 不再代交）。提交后立刻 `git rev-parse HEAD`。若 HEAD 变 unborn：sha 在 `.git/logs/HEAD`，**不要** `git update-ref`；用

```
mkdir -p .git/refs/heads/feature
printf '%s\n' <sha> > .git/refs/heads/feature/add-performance-deepening-readwrite
git rev-parse HEAD
```

---

## 现状（已核实，不要重复劳动）

- 分支：`feature/add-performance-deepening-readwrite`
- HEAD：`47b1a085d9ebba91dcccb4af7136e8eb6ed16717`
  `feat(reliability): 交卷锁按 token 解锁，并证明并发扫描只生效一次`
- 工作区还剩提示词未进 feat：`agent-prompt.md`（已改）、`agent-prompt-continue.md`、本文件 `agent-prompt-archive.md`。归档时把 `agent-prompt*.md` 全部 `git add` 再 `git mv`。
- `tasks.json` 仍全是 `completed: false`。

回勾前你自己再 grep 一眼（回报写位置，不要抄本段）：

| 项 | 已核实现象 |
|---|---|
| 锁 | `RedisLockHelper` 有 Lua compare-and-delete；`ExamSubmitService` 有局部变量 `token`，`finally` 调 `lockHelper.unlock` |
| 并发证据 | `MultiInstanceSweepSafetyTest` 4 个 `@Test`；`concurrentEndMarksAbsenceOnce` 为 `forceEnd` + `autoAdvance` |
| MQ 口径 | `concurrentSweepForcesSubmitOnce` 断言 `1..3` 条 + `submissionId` 唯一（sweep=强制交卷+补发，不是「MQ 只 1 条」） |
| 观测 | `exam.sweep.duplicate_detected`；`casAdvanceQuietly` CAS 0 → `state-advance`；`ExamSweepService` catch → `sweep`；**`ExamSubmitConsumer` `filled==0`（onBatch + handleOne）→ `sweep`** |
| 策略注释 | 两 Service 类注释含「刻意不加调度锁」+ fail-open 可选路径 |
| 依赖 | `pom.xml` 无 shedlock/quartz/redisson |

---

## 你要完成的事（按顺序）

### 1. 回勾 `tasks.json`

每个 step 自己 grep/read，属实才 `completed: true`；task 的 `passes` 仅在其全部 step 属实时改 true。

硬规则：

- 不得凭提示词印象勾满。看不清 → 留 `false` 并写原因。
- **阶段 2「MQ 只发 1 条」那一步**：先把 step 原文改成实施期事实（`submissionId` 唯一，同 `(examId,studentId)` 消息 1..3 条，因 sweep=强制交卷+同轮补发），再勾 true。不要假装原句成立。
- **额外修复**：在阶段 3 `steps` 追加一条已完成 step（不要开阶段 5）：`ExamSubmitConsumer` 的 `onBatch`/`handleOne` 在 `filled==0` 时 `countSweepDuplicateDetected("sweep")`，由 `ExamSubmitConsumerTest` 与 `concurrentSweepRepublishesWithoutDuplicating` 增量断言覆盖。这是第 2 轮扩边界补上的，必须记下来。

### 2. 合入 `spec/specs/reliability/spec.md`

源：`spec/changes/add-multi-instance-sweep-safety/specs/reliability/spec-delta.md`

- **原样保留**既有限流 / 分布式一致性 / 限流粒度 / 限流器降级 / 降级可观测。
- **追加** delta 两条 ADDED。但合并时必须改这一句：
  - delta「只投递一次交卷消息」与代码不符。改成：强制交卷只成功一次（答卷唯一、`submissionId` 唯一）；同轮对账补发允许再投递，消费端幂等跳过。
  - 其余 scenario（不重复落库、缺考一行、状态只迁一次、重复扫描可观测、不加调度锁）按 delta 合入。
- 更新文首：来源追加 `spec/changes/archive/add-multi-instance-sweep-safety`（阶段 13）；实施注记写清：正确性靠下游幂等而非调度锁；重复扫描指标 `exam.sweep.duplicate_detected`（tag `task`=`sweep`/`state-advance`）；交卷锁按 token 解锁。
- 保持 EARS。不要改 `observability` 或其他域 spec。

### 3. 更新 `spec/README.md`（只改与 13 收口相关的句子）

1. 进行中表删掉阶段 13，只留 14/15。不要再写「13、14、15 均待实施」。
2. 已合入规范：`reliability` 来源追加 `add-multi-instance-sweep-safety`，阶段写 `8.1、10、13`。
3. 已归档表增加阶段 13 一行；计数 15→16。摘要含：token 解锁 + 两线程并发证据 + 刻意不加调度锁 + `exam.sweep.duplicate_detected`（含消费者 `filled==0`）。
4. 阶段映射：阶段 13 `已提案，待实施` → `已归档`。
5. 能力地图 `reliability` 范围可补「定时扫描多实例幂等、交卷锁按持有者解锁」，不要新开能力域。
6. **不要动遗留 #1 压测、#4 观测栈动态验证、#5 补考成绩未接线、#6 DLQ 真 broker、#7 磁盘、#8 ended_time。** 阶段 13 不收这些。

### 4. `git add` 提示词后 `git mv`

先把变更目录里全部 `agent-prompt*.md`（含 continue / archive）`git add`，再：

```
git mv spec/changes/add-multi-instance-sweep-safety spec/changes/archive/add-multi-instance-sweep-safety
```

确认：`spec/changes/` 只剩 14/15 + `archive/`；archive 目录含 proposal、tasks.json、spec-delta、全部 agent-prompt*.md。

### 5. 一次 commit（你来做）

```
docs(spec): 归档 add-multi-instance-sweep-safety 并合入 reliability 规范
```

正文写清：合入了哪两条 Requirement、MQ 场景按实施期改写、消费者 filled==0 记入 tasks、遗留 #6 仍保留、代码已在 `47b1a08`。

---

## 验收对照（回报逐条 yes/no）

- [ ] reliability spec 含两条 ADDED；限流原需求仍在；「只投递一次交卷消息」已改写
- [ ] tasks.json 每个 true 有代码位置；消费者埋点已记 step；无阶段 5
- [ ] README：进行中无 13、归档 16 个、阶段 13 已归档、遗留 #6 仍在
- [ ] 进行中目录无 `add-multi-instance-sweep-safety`；archive 下完整
- [ ] 未改 `src/**` / 14/15 提案
- [ ] 只有 1 个 `docs(spec)` commit；`git rev-parse HEAD` 有值

## 不要做

- 不跑测试、不把 14/15 提前归档、不改 `docs/指导Agent交接文档.md`
- 不把「补考成绩规则」或 DLQ 真 broker 标成已完成

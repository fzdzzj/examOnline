# 提案：整场判分时一次取出主观批改行（batch-grading-subjective-upserts）

> 状态：已归档（2026-09-25）。验收边界=ObjectiveGradingServiceTest 6/0/0 + GradingScoreIntegrationTest 3/0/0（合计 9/0/0/0，隔离单测+既有集成测试，实现提交 `10aba79`，命令、原始输出与 revision 见 tasks.json 证据字段）；这不是交卷 P99 修复；未跑共享 dev 真判分。只减少整场判分对 subjective_grades 的逐题查询次数，不改判分语义。

## Why

`ExamGradingService.runExamGrading` 只读一次试卷快照，再对每份已交卷调用 `ObjectiveGradingService.gradeSafely`。简答题在 `upsertSubjectiveRow` 里按「答卷 × 题目」各做一次 `selectOne`，再 `update` 或 `insert`。这是代码里能数清的 N+1。交卷入口、消费端 JDBC batch、成绩汇总一次取主观分都没有这个形态。

这不是交卷延迟剖面。没有证据说它是交卷 P99 的主体。它只是教师触发的整场判分上，调用次数已经数清、且可以在不改分数语义的前提下降下来的一类。

**当前状态**：有简答题时，整场判分对 `subjective_grades` 的查询次数等于「已交答卷份数 × 简答题数」。单份重判同样按题各查一次。

**期望状态**：同一场判分最多一次取出这些答卷的已有主观批改行；单份重判最多一次取出该份答卷的已有行。客观分、初判提示分、失败隔离、教师终分/评语/version 不被覆盖。

## What Changes

- 整场判分在进入逐份写入前，按本场答卷 ID 一次查询已有 `subjective_grades`。循环内不再按「答卷 + 题目」`selectOne`。
- 单份重判对该份答卷一次取出已有行，不再按题各查。
- 刷新已有行时只更新学生答案与初判提示分（及题号），SHALL NOT 覆盖 `score`、`comment`、`grader_id`、`graded_time`、`version`。
- 无简答题或无答卷时不发这张表的查询。
- 保持既有失败隔离：一份异常只标记该份，不中断整场，也不把整场判分包进一个大事务。
- 用隔离单测证明查询次数与「重判不覆盖终分」。不改 JVM、线程池、交卷路径、schema。

## Impact

### 受影响的规范
- `spec/specs/grading/spec.md` — 在「简答批改」下增加「整场判分主观行一次取出」场景。教师批改留痕与并发 CAS 场景保持不变。

### 受影响的代码
- `src/main/java/com/exam/grading/service/ObjectiveGradingService.java`
- `src/main/java/com/exam/grading/service/ExamGradingService.java`（若编排层需要把预取结果传入）
- `src/test/java/com/exam/grading/service/ObjectiveGradingServiceTest.java`（新增；当前无此类单测）
- 允许只为批量写入新增 Mapper 方法；不得改表结构。

### 用户影响
- 教师看到的客观分、初判提示分、已批终分应与现在一致。整场判分仍一份失败不影响其他答卷。

### API 变更
- 无。

### 需要迁移
- [ ] 数据库迁移
- [ ] API 版本提升
- [ ] 用户沟通
- [ ] 文档更新

## 时间线评估

小。

## 风险

- 一次取出后重判把教师终分覆盖掉。缓解：测试必须覆盖「已有终分的行只刷新提示分」。
- 为了批写把整场判分包进一个大事务，放大锁竞争。缓解：禁止整场 `@Transactional`；失败隔离语义不得放宽。
- 被当成交卷性能修复。缓解：提案和证据都写明这不是交卷链路。

# 提案：收口偶发集成测试（fix-flaky-integration-baselines）

> 状态：待审批。不改产品行为，除非测试证明产品分支不稳定。

## Why

`spec/README.md` 遗留 #14、#15 仍开放：`DataRetentionIntegrationTest.purgeRemovesOnlyTerminalOldExams` 曾出现期望行数与实际不符，单类重跑又绿；`ExamTakingIntegrationTest.enterGateAndCountdown` 曾在教师注册步骤得到非预期状态。两次都没有稳定复现步骤，也没有改产品代码。

把「再跑一次就绿」当成基线，会让后面的优化分不清是产品回归还是测试抖动。

**当前状态**：两条遗留只记录了现象和「复跑变绿」。

**期望状态**：每条要么有可重复的失败和对应修复，要么写明在什么条件下不能复现、因此保持观察而不是当缺陷。

## What Changes

- 只调查这两个测试。不顺手重构 `ScoreService` 或交卷路径。
- 先在当前 HEAD 上重复运行，记录失败或连续通过。连续通过不得写成「已修复」。
- 若能复现：修测试隔离或产品缺陷中被证据指到的那一处，并留下先红后绿。
- 若不能复现：在遗留条目写明尝试过的命令和次数，保持开放，不勾成完成。

## Impact

### 受影响的规范
- `spec/specs/reliability/spec.md` — ADDED「集成测试的偶发失败必须可复现或明确保持观察」。

### 受影响的代码
- 仅当复现后需要：`src/test/java/com/exam/monitoring/retention/DataRetentionIntegrationTest.java`、`src/test/java/com/exam/taking/ExamTakingIntegrationTest.java`，以及证据指向的生产代码。禁止 `@Sql` 自建表。

### 用户影响
- 无，除非复现证明产品行为错误。

### API 变更
- 无。

### 需要迁移
- [ ] 数据库迁移
- [ ] API 版本提升
- [ ] 用户沟通
- [x] 文档更新（只更新遗留条目或本变更证据）

## 时间线评估

小。调查本身应短；修产品只在复现之后。

## 风险

- 为了变绿而放宽断言。缓解：先红后绿必须对得上同一个失败信息；不能把期望改成「有时 4 有时 8」。
- 全量测试很慢。缓解：先重复失败过的测试类，再决定要不要全量。

# 变异验证与先红留证（阶段 2）

- 变更：`fix-exam-state-machine-transaction`
- 被测类：`src/main/java/com/exam/exam/service/ExamStateMachineService.java`
- 判据用例：`src/test/java/com/exam/exam/service/ExamStateMachineTransactionTest.java`（3 用例）
- 单元测试：`src/test/java/com/exam/exam/service/ExamStateMachineServiceTest.java`（7 用例）
- 门禁命令（仓库根）：`mvnw.cmd -Dtest='ExamStateMachineServiceTest,ExamStateMachineTransactionTest' -DfailIfNoSpecifiedTests=false test`

## 0. 先红证明（修复前 → 修复后）

修复前 `autoAdvance()` 只有注解式 `@Transactional`，且**只被本类 `@Scheduled` / `@EventListener`
入口自调用**（`this.autoAdvance()`）——绕代理，注解在生产定时路径不生效。

- 先红：把新用例加入测试树后、修复前运行，`markAbsenceFailureRollsBackWholeRound…` 确定性失败：
  `整轮一个事务：…当轮全部 CAS 必须一起回滚；考试 2 未保持进行中 ==> expected: <1> but was: <2>`
  （故障场之前的 CAS 已各自提交，落成 ENDED）。该红随后由下面的变异 **m1** 逐字复现并留证，
  故 m1 的实验日志同时就是"先红"证据。
- 转绿：应用编程式事务修复后，两个测试类合计 10 用例全绿（见 `green-run.log`）。

修复前的纯单测 `ExamStateMachineServiceTest` 全程绿——它直接调用 `autoAdvance()`，
恰好模拟"经代理调用"的形态，因此从未暴露本缺陷。这正是"先红用例必须经定时入口"的理由。

## 1. 变异 m1：退回自调用、无事务形态（= 修复前缺陷形态）

- 变异：`transactionTemplate.execute(...)` → `runWithoutTransaction(...)`（辅助方法直接 `body.apply(null)`）。
- 差异留证：`m1.diff`
- 命令与输出：`m1-run.log`

```
[ERROR] Tests run: 3, Failures: 1, Errors: 0, Skipped: 0
[ERROR]   ExamStateMachineTransactionTest.markAbsenceFailureRollsBackWholeRoundThenRetryCompensates:147
          整轮一个事务：任一场 markAbsence 故障时，当轮全部 CAS 必须一起回滚；考试 2 未保持进行中
          ==> expected: <1> but was: <2>
```

**结论：击红。** 证明"当轮 CAS 与 markAbsence 同事务、失败即整轮回滚"这条断言的判据有效，
并同时复现了修复前的缺陷（先红证明）。

## 2. 变异 m2：吞掉 markAbsence 故障（不把异常逃出事务回调）

- 变异：`absenceService.markAbsence(...)` → `try { ... } catch (RuntimeException e) { log.warn(...) }`。
- 差异留证：`m2.diff`
- 命令与输出：`m2-run.log`

```
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0     ← m2 存活（未被杀）
```

**结论：未被杀，如实登记。** 实测说明：在本仓 `AbsenceService.markAbsence` 自带
`@Transactional(rollbackFor = Exception.class)`，被吞掉的异常仍会把参与中的事务标为
rollback-only，`TransactionTemplate` 的 `commit()` 随后抛 `UnexpectedRollbackException`，
整轮**仍然回滚**（状态与 version 断言照常成立），只是失败不再以原始异常形态逃出。
即：m2 改变的是**异常可见性**，不是回滚语义；现有三条用例的判据不覆盖"异常类型/可见性"，
故 m2 存活。m2 的正面价值：它确认"不吞故障"这条纪律只能靠用例 2 的
`assertNotNull(runSweepSavingFailure(), …)` 形式间接体现，不能声称被击红。

## 3. 变异 m3：缓存清除落到回滚路径上（放进 CAS 循环内）

- 变异：在 `toEnd` 循环 `if (rows > 0) {` 之后插入 `clearExamDetailCache();`
  （等价于把清除移出"提交之后"的位置）。
- 差异留证：`m3.diff`
- 命令与输出：`m3-run.log`

```
[ERROR] Tests run: 3, Failures: 1, Errors: 0, Skipped: 0
[ERROR]   ExamStateMachineTransactionTest.rolledBackRoundLeavesExamDetailCacheUntouched:112
          整轮回滚未提交任何状态流转，缓存清除应留在提交之后——不得清空 examDetail 缓存
          ==> expected: <true> but was: <false>
```

**结论：击红。** 证明"整轮回滚时不得清空 examDetail 缓存"这条断言的判据有效。

### 3.1 一条必须如实说明的边界（原派工 m3 形态不可用）

原派工设想"把 `clearExamDetailCache` 移回 `execute` 回调内"即可击红。实测**不成立**，原因是
本仓的 `RedisCacheManager`（实测 `cacheManager.getClass().getName()` =
`org.springframework.data.redis.cache.RedisCacheManager`，**未**被事务感知代理包装）
不参与 DB 事务：`clear()` 立即提交、DB 回滚无法复原。

- 实验：在同一 `TransactionTemplate` 内先 `put` 再 `clear` 然后抛异常回滚，断言
  `redis.hasKey(key)` —— 回滚后键**仍不存在**（clear 已提交）。
- 因此"清除在提交前/提交后"这一时序差异在测试中**不可观测**：把它放进 `execute` 回调，
  三条用例全绿（该形态即首轮 m3，已废弃）。
- 可观测的是"清除是否落在**回滚路径**上"（本例 m3 形态）。故测试用例的 javadoc 已改写为
  只声明后者，并明确写出"不声称能区分提交前/提交后"。

## 4. 复原确认

- 三个变异均以 `.scratch-mutation/fixed.java`（已验证绿的版本）为唯一来源生成，实验后原样复原。
- 复原核对命令：`git diff --numstat src/main/java/com/exam/exam/service/ExamStateMachineService.java`
  → `59  26`（与已验证绿版本一致）。
- 全量自测：`mvnw.cmd clean test`（见 `../tasks.json` 任务 2 证据）。

## 5. 未留证的中间产物

诊断用的临时测试类（`Scratch*Test.java`）与中间日志均在收尾时删除，不进入版本库；
本节保留的是可复核的判据、命令与结果。

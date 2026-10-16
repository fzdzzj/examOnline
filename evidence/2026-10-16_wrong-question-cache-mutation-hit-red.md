# 变异击红验证证据（optimize-wrong-question-derivation-cost 阶段 2）

变更目录：`spec/changes/optimize-wrong-question-derivation-cost`
实现分支：`feature/optimize-wrong-question-derivation-cost`

本文件记录两条指定的变异验证（proposal 第三节 + spec-delta performance Scenario）：
1. **去掉版本戳校验 → 采信脏值 → 击红**
2. **去掉缓存回填 → 未命中场错题丢失 → 击红**

变异方法：对 `src/main/java/com/exam/score/support/WrongQuestionJudgementCache.java` 做一次性侵入，
运行对应单测看红，随后撤销变异并复跑绿以确认恢复。变异后的编译/测试产物见 surefire 失败输出（下方摘录关键断言）。

## 版本戳侦察定案（任务 2 首任务）

- 判分事实源可用版本信号列（零 DDL 禁加列）：
  - `exam_submissions.updated_time`（已存在，`DEFAULT CURRENT_TIMESTAMP`）；
  - `subjective_grades.updated_time`（已存在，`DEFAULT CURRENT_TIMESTAMP`）；
  - `subjective_grades.version`（乐观锁列，每次 `casSaveScore` 自增）。
- 批改/复核写点确认 touch submission：
  - `SubjectiveGradingService.submitGrade` → `casSaveScore`（自增 version + 刷 updated_time）
    → `refreshSubmissionSubjectiveScore`（显式 `.set(updated_time, now)` 刷答卷 updated_time）。
- **定案：版本戳方案可行**，缓存 key 携带 `concat(submission.updated_time, MAX(subjective.updated_time), MAX(subjective.version))`，
  事实源变更后信号变化 → key 变化 → 天然失效。**零 DDL、零写路径侵入**，无需退化到显式失效钩子，
  **不回写 proposal 白名单**（不扩 grading 包）。

## 派生点点接缓存范围说明（复核确认项）

- 已接缓存（布尔判定消费端）：`StudentPracticeService.getPracticePool` / `getStudentWrongQuestionIds` /
  `isWrongQuestionOf`（`submitPractice` 经其接入）。
- `WrongQuestionCache` 内容红线（proposal 第三节）：仅 score/max/type，**不含答案文本/评语/判分依据明细**。
- `StudentWrongQuestionService.listWrongQuestions` **保持完整装配**：其显示契约消费
  `myAnswer（答案文本）`、`scoreDetail（comment 或 detail，即评语/判分依据）`、`graded`——这些字段
  正被内容红线禁止入缓存。若用 score/max/type 缓存服务该点，会在缓存命中时丢失判分依据/学生答案，
  构成显示回归（与「导出/明细路径仍走完整口径」同源）。故本点仍走 `getPersonalReportWithoutRank`（完整装配），
  已经享有双 parse 收敛收益。该判定待复核确认（对应 proposal 第三节「由复核确认范围」的同类约束）。

---

## 变异 1：去掉版本戳校验 → 采信脏值

**变异点**：`WrongQuestionJudgementCache.key(examId, studentId, sig)` 忽略 `sig`，改用固定 key
（`...:vFIXED`）——等价于「版本信号变更后仍采信旧缓存（脏值）」。

**击红用例**：`WrongQuestionJudgementCacheTest#versionStampChange_invalidatesCache`
（该用例断言判分事实源信号变化后 key 必须变化、旧缓存自然失效）。

**失败摘录（surefire）**：
```
com.exam.score.support.WrongQuestionJudgementCacheTest.versionStampChange_invalidatesCache -- FAILURE!
org.opentest4j.AssertionFailedError: 版本信号变化后缓存 key 必须变化（旧缓存自然失效）
  ==> expected: not equal but was: <exam:cache:wrongQuestionJudgement:1001:42:vFIXED>
[ERROR] Tests run: 1, Failures: 1, Errors: 0
[INFO] BUILD FAILURE
```

**结论**：去掉版本戳校验后，版本变更的脏值会被缓存采信，测试精确击红 ✔

---

## 变异 2：去掉缓存回填 → 未命中场错题丢失

**变异点**：`WrongQuestionJudgementCache.get(...)` 未命中分支不再 invoke `loader` 回填，直接返回 `null`。

**击红用例**：
- `WrongQuestionJudgementCacheTest#miss_backfillsAndStores`（断言未命中回填并落键）
- `WrongQuestionDerivationCacheSemanticsTest#missPath_assemblesExactlyOnceForBackfill`
  （断言未命中恰好一次装配回填）

**失败摘录（surefire）**：
```
WrongQuestionJudgementCacheTest.miss_backfillsAndStores:103
  expected: <{201=ScoredQuestion[score=3.0, max=5.0, type=SINGLE]}> but was: <null>
WrongQuestionDerivationCacheSemanticsTest.missPath_assemblesExactlyOnceForBackfill:142
  NullPointer Cannot invoke "java.util.Map.get(Object)" because "result" is null
[ERROR] Tests run: 2, Failures: 1, Errors: 1
[INFO] BUILD FAILURE
```

**结论**：去掉回填后，未命中场错题判定丢失（返回 null），消费端错题集合受破坏，测试击红 ✔

---

## 恢复确认

两次变异均已撤销，`WrongQuestionJudgementCache.java` 恢复正确语义（key 携带版本信号、
未命中按现算回填、Redis 故障退化为现算）。全量门禁见 `mvnw.cmd clean test` 实测输出（proposal 青
任务证据），其中 16 个受影响 score 单测 + 9 个错题派生集成用例全部通过，确认变异撤销后恢复绿。
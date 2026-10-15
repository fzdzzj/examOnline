# m3 变异击红留证 — Enter 提交必须复用 createGradingFlow 且冲突不移动焦点

注入：`SubjectiveGradingPanel.vue` `onScoreEnter` 改为**无论成功失败/冲突，都把焦点移到下一行**
（`focusScoreInput(nextRowSubmissionId(row) ?? row.submissionId)`），即失败/冲突时也应跳行。

判定目标（红线用例）：`src/components/postexam/__tests__/SubjectiveGradingPanel.spec.ts`
- 「提交失败（普通错误）时焦点停留当前行，不前进到下一行」
- 「乐观锁冲突时焦点停留当前行，且复用 flow 冲突语义（不自动重试、不算成功）」

执行：`npx vitest run src/components/postexam/__tests__/SubjectiveGradingPanel.spec.ts`

击红：

```
❯ src/components/postexam/__tests__/SubjectiveGradingPanel.spec.ts (7 tests | 2 failed)
    ✓ Enter 提交复用 createGradingFlow：走 saveSubjectiveScore（带 expectedVersion），不绕过 flow
    ✓ 提交成功后焦点自动移至下一行分数框（按当前行序 1001 → 1002）
    × 提交失败（普通错误）时焦点停留当前行，不前进到下一行
    × 乐观锁冲突时焦点停留当前行，且复用 flow 冲突语义（不自动重试、不算成功）
Test Files  1 failed (1)
     Tests  2 failed | 5 passed (7)
AssertionError: expected <input ...> to be <input ...> // Object.is equality
```

失败断言 `expected <input …> to be <input …>`（第 2/3 行分数框被聚焦而不应聚焦）：失败与冲突路径焦点被
错误地移到了下一行分数框，而不是停留当前行——教师不必处理当前行错误就被推向下一行。红线用例（
`Enter 提交必须复用 createGradingFlow 且冲突不移动焦点`）抓住该变异。`saveSubjectiveScore` 复用 flow
的红线用例保持通过（提交仍经 flow）。

复原后回绿（全部 7 例通过）。
意义：失败/冲突后焦点停留当前行，是「教师必须先处理当前行错误」的安全性保证；变异让焦点跳行，
把未处理错误的学生静默带过，红线用例能抓住。
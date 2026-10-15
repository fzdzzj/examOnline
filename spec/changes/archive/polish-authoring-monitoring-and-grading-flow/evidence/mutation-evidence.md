# 变异验证总览 — polish-authoring-monitoring-and-grading-flow 阶段 2

判定目标：三条红线各注入一次「必然使该红线用例击红」的变异，复原后恢复全绿。
每处「红 → 复原 → 绿」的独立留证见 `m1-mutation-red.md` / `m2-mutation-red.md` / `m3-mutation-red.md`。

- m1（复制模式误走 update）：注入 `QuestionForm.onSubmit` 新增复制分支调用 `update()`，
  红线用例「复制模式不是编辑：提交走 create1 且绝不调用 update」「复制保存是「新建」」击红
  （`expected "vi.fn()" to be called 1 times, but got 0 times`）。复原后回绿。
- m2（轮询倒计时写字面量）：注入 `ExamMonitorPanel` 倒计时改写字面量 `5000`，
  红线用例「倒计时初始为 MONITOR_POLLING_INTERVAL_MS/1000 秒」击红
  （`expected '下次刷新 5000s' to contain '10s'`）。复原后回绿。
- m3（Enter 冲突/失败移动焦点）：注入 `SubjectiveGradingPanel.onScoreEnter` 失败/冲突也跳下一行，
  红线用例「提交失败/乐观锁冲突时焦点停留当前行」击红
  （`expected <input …> to be <input …>` 第 2 行被错误聚焦）。复原后回绿。

## 复原后回绿证据

复原三处注入后，三段特性红线用例全量通过：

```
> npx vitest run src/components/question/__tests__/QuestionFormCopy.spec.ts \
    src/components/exam/__tests__/ExamMonitorPanel.spec.ts \
    src/components/postexam/__tests__/SubjectiveGradingPanel.spec.ts

 Test Files  3 passed (3)
      Tests  26 passed (26)
```

结论：三条红线（复制必须走 create1、倒计时必须引用常量、Enter 冲突不改焦点）均非装饰性判据——
各自注入变异即被对应红线用例当场击红，复原后回绿。
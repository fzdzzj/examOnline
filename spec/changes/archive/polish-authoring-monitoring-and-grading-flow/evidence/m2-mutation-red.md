# m2 变异击红留证 — 轮询倒计时必须引用 MONITOR_POLLING_INTERVAL_MS（禁字面量）

注入：`ExamMonitorPanel.vue` 把倒计时间隔从 `MONITOR_POLLING_INTERVAL_MS` 改写字面量 `5000`
（`const countdownSeconds = ref(5000)` 与 `startCountdown` 内 `countdownSeconds.value = 5000`）。

判定目标（红线用例）：`src/components/exam/__tests__/ExamMonitorPanel.spec.ts`
- 「倒计时初始为 MONITOR_POLLING_INTERVAL_MS/1000 秒，显示「下次刷新 Ns」」

执行：`npx vitest run src/components/exam/__tests__/ExamMonitorPanel.spec.ts`

击红：

```
❯ src/components/exam/__tests__/ExamMonitorPanel.spec.ts (15 tests | 1 failed)
    × 倒计时初始为 MONITOR_POLLING_INTERVAL_MS/1000 秒，显示「下次刷新 Ns」
Test Files  1 failed (1)
     Tests  1 failed | 14 passed (15)
AssertionError: expected '下次刷新 5000s' to contain '10s'
```

失败断言 `expected '下次刷新 5000s' to contain '10s'`：倒计时从常量派生改为硬编码字面量 `5000`，
与 `MONITOR_POLLING_INTERVAL_MS`（10s）脱钩。行为级红线确认「倒计时必须引用常量、不得写字面量」。

复原后回绿（全部 15 例通过）。
意义：倒计时与轮询间隔从同一常量派生，是「页面不写字面量」词法护栏的可观测体现；
写死字面量即与后端记录的后端建议间隔脱钩，被红线用例抓住。
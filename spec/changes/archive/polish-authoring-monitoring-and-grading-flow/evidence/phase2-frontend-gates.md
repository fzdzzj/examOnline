# 阶段 2 前端三门禁全量自测 — polish-authoring-monitoring-and-grading-flow

范围：仅 `frontend/src/**`（受控白名单），零 DDL、零后端改动、零契约变更。
当前基线（repo 已入库 `gate-baseline.json`）：`frontendTests=831`；本变更新增 17 条单测（基线在阶段 3 实测后上调）。

## 1) vitest 全量单测

执行：`npx vitest run`（frontend）

```
 Test Files  113 passed (113)
      Tests  848 passed (848)
```

新增特性用例（26 条，已含于 848）：
- `QuestionFormCopy.spec.ts`（4）：复制另存深拷贝回填 + 提交走 create1 不走 update + 复制非编辑 + 编辑回归。
- `ExamMonitorPanel.spec.ts`（15，+6 新增）：搜索/卡片过滤/立即刷新/倒计时引用常量 + 词法护栏。
- `SubjectiveGradingPanel.spec.ts`（7）：Enter 复用 createGradingFlow 成功跳行、失败/冲突停留、快捷给分、评语模板。

## 2) type-check

执行：`npx vitest run` 之外，`npm run type-check:check` → `TYPE-CHECK CLEAN`（无 `error TS`）。

## 3) lint

执行：`npm run lint:check` → `LINT CLEAN`。

## 结论

三条红线（复制必须走 create1、倒计时必须引用 MONITOR_POLLING_INTERVAL_MS、Enter 冲突/失败不移动焦点）
均以「红线用例 + 变异击红 + 复原回绿」闭环留证（见 `evidence/m1/m2/m3-mutation-red.md` 与 `mutation-evidence.md`）。
前置后端/契约零改动判定复核通过后，本阶段自测可停止待复核。
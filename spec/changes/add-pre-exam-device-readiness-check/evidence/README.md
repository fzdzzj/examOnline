# 阶段 2 证据（add-pre-exam-device-readiness-check）

> 纪律要求：vitest 新测试先红后绿（enabled 门控移除→红、离线豁免移除→红、二次确认跳过→红、
> 留存移除→重跑红）；变异击红-复原-回绿留证；vitest 全量跑留证（只增不减）；白名单外零 diff。
> 基线：feature/add-pre-exam-device-readiness-check @ bc805cc（工作树干净）。

## 一、基线

- `red-first-new-tests.log`：先红证据——三个新测试文件（hook/component/page）在实现缺失时
  全部无法通过（`Test Files 3 failed`，缺 `@/hooks/useDeviceReadiness` 模块）。
- 基线全量跑：874 总数（871 passed + 3 failed，`vitest-baseline-phase2.log`，本地未入库日志）。
  3 个失败均为**基线既有的无关超时**（teacher 侧 SubjectiveGradingPanel / paperBlueprintDraw /
  questionImportExport，Test timed out in 5000ms），与本变更零交集（本变更只触碰 student 侧文件）。
- gate-baseline.json frontendTests=874 与基线跑出的 874 总数一致（阶段 2 不动基线，收口笔阶段 3 上调）。

## 二、先红后绿

| 阶段 | 运行 | 结果 |
| --- | --- | --- |
| 先红 | `red-first-new-tests.log` | 3 files failed（实现缺失，全红） |
| 后绿 | `green-new-tests.log` | 6 files / 48 tests passed（新 34 + 既有 exam 页 14） |

既有 exam 页测试（offlineExamReentry / studentTakingJump / antiCheatGuard）在门控接线后
全部保持绿——离线豁免、预置快照路径不受自检面板影响。

## 三、变异击红-复原-回绿（四条）

| # | 变异点 | 击红锚点 | 红证据 | 回绿证据 |
| --- | --- | --- | --- | --- |
| 1 | enabled 门控移除（`enabled: base.enabled`） | 挂载即起表：`expected [42] to have a length of +0` | mutation-1-enabled-gate-red.log | mutation-1-revert-green.log |
| 2 | 离线整场豁免移除（`readinessGatePassed` 去掉 offlineExempt） | 离线直放行消失：`expected [] to deeply equal [42]` | mutation-2-offline-exempt-red.log | mutation-2-revert-green.log |
| 3 | 强警示二次确认跳过（onEnterClick 直接 emit） | 确认面板不出现：`expected false to be true`（2 用例红） | mutation-3-double-confirm-red.log | mutation-3-revert-green.log |
| 4 | 会话级留存读取移除（watch 不再 loadReadinessReport） | 刷新重挂载重复自检：`expected true to be false` | mutation-4-retention-red.log | mutation-4-revert-green.log |

## 四、全量跑（只增不减）

- `vitest-full-final.log`：本变更收尾的 vitest 全量输出（最终代码形态，prettier --fix 之后重跑）。
- 用例数：基线 874 → 变更后 908 = 874 + 34 新用例（hook 17 + component 9 + page 8 = 34），
  **只增不减**（907 passed + 1 failed）。
- 仅剩的 1 个失败是基线既有的 flaky 超时 `SubjectiveGradingPanel.spec.ts`
  （Test timed out in 5000ms，teacher 侧，与本次变更零交集）：基线同文件同用例同样失败，
  本收尾跑中另外 2 个基线超时（paperBlueprintDraw / questionImportExport）已自行转绿；
  该文件孤立运行 7/7 通过——判为全量并行负载下的既有 flaky，非本变更引入。
- gate-baseline 判据 `passed >= baseline`：907 >= 874 满足（阶段 3 收口笔再按实测上调 baseline）。

## 五、白名单外零 diff

`git status --porcelain` 变更清单（全部命中 proposal 第三节白名单）：

```
 M frontend/src/pages/(dashboard)/student/exams/[id].page.vue   （enabled 门控接线，白名单第 6 条）
?? frontend/src/components/student/DeviceReadinessCheckModal.vue （新增，第 1 条）
?? frontend/src/components/student/__tests__/DeviceReadinessCheckModal.spec.ts （新增测试，第 2 条）
?? frontend/src/hooks/useDeviceReadiness.ts                     （新增，第 3 条）
?? frontend/src/hooks/__tests__/useDeviceReadiness.spec.ts       （新增测试，第 4 条）
?? frontend/src/pages/(dashboard)/student/exams/__tests__/deviceReadinessGate.spec.ts （新增测试，第 6 条）
?? spec/changes/add-pre-exam-device-readiness-check/evidence/    （第 7 条）
```

后端 `src/main` / `src/test`、`pom.xml`、`openapi.yaml`、`schema.sql`、`docker/mysql/migrations/`：
零触碰（git status 无命中）。

## 六、实施要点自检

- hook：RTT 同源 HEAD 采样丢首样本取中位数（`measureRttMedian`/`medianOf`）、userAgent 内核
  白名单（`detectKernel`，Edge→Firefox→Chrome→Safari 顺序判定）、fullscreenEnabled/localStorage
  特性检测（`probeLocalStorage`）、异常兜底「已完成 + 全部警示」（run 永不 reject）。
- component：逐项进度（进行中/待执行）与结果徽标（通过/警示/强警示）、检查完成前「进入考试」
  禁用、强警示「仍要进入」二次确认（确认前不 emit enter）、无 critical 直接放行。
- page：enter useQuery enabled 叠加 `readinessGatePassed && enterRequested`（自检未过不起表）、
  `navigator.onLine === false` 整场豁免（面板不出现 + enter 直放行）、sessionStorage 会话级留存
  （刷新/返回再进不重复自检）、examId 切换（路由复用组件）视为新考试重新门控。

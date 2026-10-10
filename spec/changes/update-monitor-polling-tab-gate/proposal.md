# 变更提案：监考总览轮询浏览器页签门控（update-monitor-polling-tab-gate）

## 背景与动机

在教师端考试详情页，监考总览数据（`GET /api/exams/{examId}/monitor/overview`）目前配置为 10s 准实时轮询（`refetchInterval: 10000`）。
在归档变更 `gate-monitor-polling-by-tab` 中，已实现了**页面内页签门控**（仅 `monitor` 与 `roster` 页签启用，其余页签 `enabled=false`）。
然而，当教师将浏览器切换到其他页签或最小化浏览器窗口（`document.visibilityState === 'hidden'`）时：
1. 轮询配置未按浏览器可见性做显式门控，后台可能持续空转产生无谓查询；
2. 现有配置硬编码了 `refetchOnWindowFocus: false`（防失败时弹重试）；当教师重新切回该浏览器页签（恢复可见）时，vue-query 不会立即拉取最新数据，教师必须被迫等待长达 10s 的下一个定时器周期才能看到更新，存在数据新鲜度滞后；
3. 需要引入显式的浏览器页签可见性门控 Hook（`usePageVisibility`），在页签非激活（hidden）时暂停轮询，并在恢复可见（visible）时立即主动拉取一次最新监考数据并恢复正常 10s 轮询。

## 阶段 0 · 调研核实结论

1. **定位监考总览轮询实现与现状**：
   - 监考总览查询在 `pages/(dashboard)/teacher/exams/[id].page.vue` 中通过 `createMonitorQueryOptions`（`hooks/useExamMonitor.ts`）构建选项并传给 `useQuery`；
   - 当前 `refetchInterval` 为常量 `MONITOR_POLLING_INTERVAL_MS`（10000ms），`enabled` 仅组合了 `examId` 合法性与页面内消费页签谓词 `isConsumerTabActive()`；
   - 当前在浏览器页签隐藏时未做显式门控；且因 `refetchOnWindowFocus: false`，切回页签时不会立即刷新。

2. **核实实时推送通道（WebSocket/SSE）**：
   - 代码库全文 grep 结果：监考总览与教师端考务**不存在任何 WebSocket 或 SSE（EventSource）链路**；
   - `ExamMonitorPanel.vue`、`constants/monitor.ts` 与 `useExamMonitor.ts` 文件头注释均明确声明"本仓库 v3 范围没有 WebSocket 推送，只声称准实时轮询"；
   - 门控仅作用于 HTTP 轮询查询，不破坏任何推送链路。

3. **确认监考页现有 visibilitychange 用途与隔离关系**：
   - 全仓搜索 `visibilitychange`：当前仅在**学生在线作答 anti-cheat 反作弊切屏检测**（`useBehaviorReport.ts`、`useBehaviorOfflineQueue.spec.ts`、`behaviorEvents.ts`）中使用；
   - 教师监考总览页现有代码零使用 `visibilitychange`；
   - **隔离关系**：学生端切屏检测用于采集作弊信号上报后端，而监考总览门控仅用于驱动本地轮询调度。两者所属角色域、页面路径与 Hook 完全物理隔离，本变更严禁触碰学生作答与反作弊相关代码。

4. **能力现状确认**：
   - 归档目录 `spec/changes/archive/` 下仅有 `gate-monitor-polling-by-tab`（页面内 tab 门控），查无浏览器页签可见性（`visibilityState`）同类案，确认无重复立项。

## 改动内容（纯前端，全部在 `frontend/src`）

1. **新增 Hook：`frontend/src/hooks/usePageVisibility.ts`**
   - 监听 `document` 的 `visibilitychange` 事件，暴露响应式 `isVisible: Ref<boolean>` 与 `visibilityState: Ref<DocumentVisibilityState>`；
   - 支持 `onVisible`（hidden → visible 恢复触发）与 `onHidden`（visible → hidden 隐藏触发）回调；
   - 支持单测依赖注入（`deps.addListener` / `deps.removeListener` / `deps.getVisibilityState`），并在 `onScopeDispose` 时安全卸载；
   - 默认适配原生 `document.visibilityState` 与 `document.addEventListener`。

2. **改造 Hook：`frontend/src/hooks/useExamMonitor.ts`**
   - `MonitorQueryTabGate` 扩展可选谓词 `isPageVisible?: () => boolean`（缺省 `() => true`，保持向后兼容）；
   - `refetchInterval` 改为：`isPageVisible() ? MONITOR_POLLING_INTERVAL_MS : false`；
   - `MonitorQueryOptions` 的 `refetchInterval` 类型扩充为 `number | false`；
   - 文件头追加不变式 5（浏览器页签可见性门控）。

3. **接入页面：`frontend/src/pages/(dashboard)/teacher/exams/[id].page.vue`**
   - 引入 `usePageVisibility`，配置 `onVisible` 回调：当且仅当处于消费页签且 `examId` 合法时，立即调用 `refetchMonitor()`；
   - `createMonitorQueryOptions` 注入 `{ isConsumerTabActive: ..., isPageVisible: () => isPageVisible.value }`；
   - 解构 `useQuery` 返回的 `refetch: refetchMonitor`，实现恢复可见时立即拉取一次。

4. **单元测试与词法护栏**：
   - `frontend/src/hooks/__tests__/usePageVisibility.spec.ts`：覆盖初始状态、隐藏暂停、恢复触发回调、依赖注销等；
   - `frontend/src/hooks/__tests__/useExamMonitor.spec.ts`：扩展 `isPageVisible` 对 `refetchInterval` 的门控断言，更新页面词法护栏；
   - `frontend/src/pages/(dashboard)/teacher/exams/__tests__/monitorTabVisibilityGate.spec.ts`：监考页可见性门控链路集成测试（初始可见、隐藏暂停、恢复可见立即拉取与轮询恢复、非消费页签隔离）。

## 不变式（红线）

1. **反作弊切屏域零触碰**：`useBehaviorReport.ts`、`behaviorEvents.ts` 及学生作答页一字不动；
2. **纯前端变更**：不触碰 `src/main/`、`src/test/`、`pom.xml`、`openapi.yaml`、`package.json` 依赖项；
3. **轮询基准不变**：轮询间隔仍为 10s（`MONITOR_POLLING_INTERVAL_MS`），文案仍为"准实时轮询，每 10s 刷新"；
4. **后端接口与数据口径不变**：仍由后端 `GET /api/exams/{examId}/monitor/overview` 返回，前端不合计不推算。

## 验收与停止条件

1. **前端三门禁**：
   - `npm.cmd run lint:check` 退出码 0；
   - `npm.cmd run type-check:check` 退出码 0（含完整 vue-tsc banner）；
   - `npm.cmd run test` 退出码 0，用例总数净增，无原有测试失败。
2. **后端门禁复核**：
   - `.\mvnw.cmd clean test` 保持 384/0/0/1 BUILD SUCCESS。
3. **变异校验（至少两项）**：
   - 变异 ①：撤门控（`isPageVisible` 恒返回 10000 间隔）→ 页签隐藏暂停轮询断言变红；
   - 变异 ②：撤恢复立即拉取（移除 `onVisible` 中的 `refetchMonitor`）→ 恢复可见立即拉取断言变红。

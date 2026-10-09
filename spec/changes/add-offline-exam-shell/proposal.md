# 提案：断网刷新重入不断考与离线保护壳（add-offline-exam-shell，创新点5 · Phase 1）

## 1. Why & 背景

根据 `docs/examOnline需求规格说明书.md` §3.1 核心创新点 5「断网断电保护（IndexedDB 本地草稿 + 断网续答 + 交卷断线保护）」：
- **阶段 22 现状**：已实现 IndexedDB 本地草稿缓存 + 30s 定时保存 + 30s 输入防抖 + 网络恢复（`online` 事件）自动 flush + 保守合并 + 交卷防重配合与手动重试。
- **本卡目标**：在既有断线保护之上，补齐「断网刷新重入不断考 + 断网显性 UI + 切屏事件离线兜底」，作为创新点 5 的一期工程落地。
- **设计红线（明确不做，严禁越界）**：
  1. **离线进入考试不做**：初次进入考试必须联网从服务端获取个人试卷快照（`POST /api/exam-taking/exams/{examId}/enter`），服务端时间戳与幂等快照是合规底线；
  2. **绕过服务端交卷时间窗不做**：倒计时归零由服务端时间裁决，超时收卷由后端定时扫描（`ExamSweepService`）兜底，前端不本地延时交卷；
  3. **离线反作弊不做（如实定位监考盲区）**：人脸识别、设备指纹、全屏锁定等复杂反作弊不在一期范围；断网期间仅记录切屏/失焦并暂存本地队列，网络恢复后补报，如实定位监考盲区；
  4. **API 一律 network-only 禁缓存**：所有 `/api/**` 请求禁止 SW 缓存，避免数据陈旧与并发脏读；SW 仅作为静态壳缓存。

---

## 2. 阶段 0 · 调研核实结论

### 2.1 切屏事件上报现状与兜底裁决
- **现状核查**（`useBehaviorReport.ts` + `behaviorEvents.ts`）：
  1. 正常回归上报时：收到 `visible` / `focus` 信号，纯函数状态机重置 `pending` 为 `null`，并调用 `send`。若此时断网，`send` 捕获异常返回 `false`，但由于内存状态机已重置，该离开事件与其时长 `durationMs` 直接丢失（即发即弃）。
  2. 兜底 flush（交卷前 / suspended / beforeunload）：`flush()` 在 `send` 失败时仅还原内存中 `episode = before`；一旦学生在断网期间刷新页面（F5 / Ctrl+R），内存上下文彻底销毁，暂存事件丢失。
  3. 丢失面评估：断网下切屏并返回、断网下切屏并刷新，切屏事件 100% 丢失。
- **裁决**：切屏离线兜底正式纳入本期！在 IndexedDB 中持久化未成功上报的切屏事件队列（`exam-online-student-drafts` 数据库的 `behavior_events` 仓库），网络恢复时与草稿 flush 同时机补报；增加去重护栏（相同 `durationMs` 与事件类型不重复上报），守住既有「一次离开归并为一条，不得翻倍」红线。

### 2.2 考试作答页构建产物结构与 SW 缓存目标
- **路由机制**：`frontend/src/router/index.ts` 采用 `createWebHashHistory()`（Hash 路由，`#/student/exams/:id`）。浏览器底层 HTTP 导航请求恒为根路径 `/` 或 `/index.html`，客户端依据 hash 动态加载组件。
- **构建产物**：入口 `index.html` 引用 `main.ts`；动态导入生成 `_id_.page-[hash].js` 及公共依赖 chunk（全带 contenthash 指纹）。
- **SW 缓存目标与策略**：
  1. `install` 阶段：预缓存（precaching）入口 HTML（`/` 与 `/index.html`）；
  2. `activate` 阶段：清理旧版本缓存（`staleCachesToDelete(activeVersion, existingCaches)`），并调用 `clients.claim()`；
  3. `fetch` 拦截阶段：
     - **API 请求（`/api/**`）**：严格放行 `fetch(request)`，network-only，**绝对禁止缓存**；
     - **导航请求（HTML request，`mode === 'navigate'` 或 `accept` 包含 `text/html`）**：优先网络，离线时回退到缓存的入口 HTML；
     - **同源静态资源（JS/CSS/SVG/字体等指纹资源）**：runtime 缓存（Cache First / Network Fallback）。命中且离线时由缓存返回；
     - **非同源或非 GET 请求**：直接放行，不缓存。

### 2.3 vite.config.ts 插件链与 SW 冲突核实
- 现有插件：`VueRouter`, `vue()`, `TailwindVitePlugin()`。
- 无 `vite-plugin-pwa`，无 `workbox`，零插件冲突。
- `sw.js` 作为手写源文件置于 `frontend/public/sw.js`，Vite 构建原样复制至 `dist/sw.js`，开发环境可在 `/sw.js` 直达。

---

## 3. 详细方案设计

### 3.1 阶段 2 · 断网显性 UI（先红后绿）
- 作答页离线状态条：`OfflineStatusBanner.vue`（或置于作答页顶部显性卡片）；
- 双检测触发：`!navigator.onLine` OR 草稿同步失败（`autoSave.status.value === 'unsynced'`）；
- 文案纪律：禁用 `offline exam` / `offline answer`（离线考试 / 离线作答），采用「离线保护中，答案已本地保存」/「网络已断开，离线保护中，答案已保存在本机，恢复网络后将自动同步」；
- 恢复联动：网络恢复（`online` 事件）后，状态条自动消失，同时触发既有 `autoSave.flush()` 与切屏补报；
- Vitest 用例：断网状态条显式渲染、恢复后消失、文案合规护栏断言。

### 3.2 阶段 3 · Service Worker 离线壳（先红后绿）
- **设计红线：纯函数模块化**（`swCore.ts`）：
  - `shouldCache(url, request)`：仅同源静态资源缓存，API / 跨域 / 非 GET 一律 false；
  - `isNavigationRequest(request)`：导航请求识别；
  - `staleCachesToDelete(activeVersion, existingCaches)`：旧版本清理过滤纯函数；
  - vitest 100% 单测覆盖纯函数决策。
- **注册与卸载策略**：
  - 仅在考试作答页挂载时注册（路由级精确）；
  - 路由切换离开作答页（如返回列表）时执行 `unregister()`，避免影响登录或其他页；
  - 页面刷新（F5）仍在作答页，不触发路由切换注销，SW 保留并拦截导航请求，返回缓存的入口 HTML；
  - 刷新重入后，既有 IndexedDB 草稿恢复链路（`draftStorage.load`）接管答案恢复。

### 3.2.1 设计取舍登记：sw.js 与 swCore.ts 双实现及其同步责任（修复笔 · add-offline-exam-shell）

- **取舍**：离线壳的缓存决策存在两份实现——`frontend/public/sw.js`（手写 Service Worker，运行于
  ServiceWorkerGlobalScope，无法在 Vitest 中直接执行）与 `frontend/src/utils/swCore.ts`（可单测纯函数，
  供注册逻辑引用与 100% 单测覆盖）。二者 `STATIC_EXTENSIONS` 与 `shouldCache` 口径必须一致；
  本轮由抽查裁决指出并修复了「sw.js fetch 兜底分支缓存范围比 swCore.shouldCache 宽」的漂移
  （sw.js 原先对任意同源 GET 均缓存回写，未收敛到静态资源判定）。
- **为何不做 DRY 合并**：sw.js 作为独立 SW 源文件被 Vite 原样复制，无法从 TS 模块导入运行时代码；
  强行共享会引入构建期注入复杂度并破坏「手写源文件零依赖」的可移植性。故保留双实现为有意取舍。
- **同步责任由护栏显式承担**：双实现的同步不靠口头约束，而由词法护栏
  `frontend/src/utils/__tests__/swGuard.spec.ts` 承担——护栏读取 sw.js 源文本并断言四条缓存红线
  （①`/api/` network-only 放行分支；②旧缓存清理含 `startsWith(CACHE_PREFIX)` 且排除当前 `CACHE_NAME`；
  ③非 GET 请求放行；④预缓存入口列表存在）。sw.js 内 `STATIC_EXTENSIONS` 自含一份并注释注明
  「必须与 swCore.ts 保持一致、由护栏守护」；`swCore.spec.ts` 维持纯函数单测，二者职责不重复。
  （对齐既有词法护栏先例 `PublisherConfirmScopeGuardTest` 的「源码扫描而非 mock 单测」做法。）

### 3.3 阶段 4 · 切屏事件离线兜底（先红后绿）
- IndexedDB 持久化切屏事件队列（`behaviorStorage.ts` / 扩展至 `draftStorage.ts`）；
- 上报失败或离线时暂存入库；网络恢复（`online` 事件）及草稿 flush 时出队补报；
- 去重与归并护栏：相同 `durationMs` 与类型不重复上报，确保既有「切屏归并为一条上报，不得翻倍」红线不被破坏。

---

## 4. 验收判据

1. **测试驱动（先红后绿）**：
   - 断网显性 UI 组件与作答页集成单测先红后绿；
   - SW 纯函数核心决策单测先红后绿；
   - SW 注册/注销 mock 单测先红后绿；
   - 切屏事件离线队列与补报去重单测先红后绿。
2. **变异校验留证（至少两项）**：
   - 变异项 1：撤销离线状态条断言，验证测试变红，复原变绿；
   - 变异项 2：撤销 SW 过期版本清理逻辑，验证测试变红，复原变绿。
3. **全量门禁与基线一致**：
   - 前端三项：`lint:check`、`type-check:check`、`test` 全绿，测试文件数与用例数净增；
   - 后端基线：`mvnw.cmd clean test` 保持 382/0/0/1，零影响。

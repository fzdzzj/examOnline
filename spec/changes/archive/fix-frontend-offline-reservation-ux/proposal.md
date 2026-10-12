# fix-frontend-offline-reservation-ux 提案（学生考试列表离线判定响应化与预约操作门控 + 消息中心查询失败显性化）

> 立项依据：第三轮前端只读巡查台账 `docs/frontend-improvement-candidates-r3.md` 候选 U-8 / U-9 / U-10（台账为不入库纪律文件，行号基于 `5750fff`；本卡开工基线与锚点须现场复核，见 tasks.json 任务 1 K5）。指导主 agent 已裁决三候选打包一案（同属「离线与预约面反馈完备性」），纯前端变更，**本卡零后端改动**。前置：无硬依赖；仍受「同一工作树不得并行跑两个子 agent」约束（开工时以 `spec/changes/` 非 archive 目录现场盘点为准）。

## Why

1. **U-8 列表页离线判定非响应式 + 离线态操作无门控**：`pages/(dashboard)/student/exams/index.page.vue:127-129` 的 `isOffline` computed 只读 `navigator.onLine`、无任何响应式依赖，取值冻结在首次求值时刻——会话中途断网时 `isFallbackActive`（:131-133，= error && isOffline && 缓存非空）永不激活，离线降级（offline-reentry 核心交付）恰在该场景被旁路；对照作答页 `[id].page.vue` 已用 `useOfflineStatus`（监听 online/offline 事件）同源正确实现。同页 `onReserve` / `onCancelReservation`（:169-190）与 `components/student/StudentExamList.vue:43-76` 预约/取消按钮无离线门控，离线点击会发真实请求。既有 `offlineExamList.spec.ts` 五用例均自断网态起测（首求值即 true），测不出「中途断网」时序。
2. **U-9 MessageCenter 查询失败未消费 error**：`components/MessageCenter.vue:64-77` 两查询（unread-count / list）均未解构 `error`、全组件无错误分支，失败时 `data` 为 undefined → 列表落「暂无消息」空态（与确实无消息不可区分）、未读角标静默 0；属 `add-exam-reservation` 新面遗漏，与 U-1/U-6 已收口的「失败≠空态」口径不一致。标已读（:90-96）无失败反馈。
3. **U-10 取消预约无知情确认**：`components/student/StudentExamList.vue:51-59` 取消预约直发 `emit('cancel')`，无 Popconfirm/Modal；后端 spec 口径取消=物理删除、重约=重插，风险低于不可逆删除，但违背本仓库「危险操作先确认」（Popconfirm）惯例。失败反馈已如实（页面 catch → message.error），此项不改。

## What Changes

- **U-8（index.page.vue + StudentExamList.vue）**：列表页 `isOffline` 改由 `useOfflineStatus` 驱动（与作答页同源；列表页无草稿心跳，`autoSaveStatus` 传恒非 `unsynced` 的中性值，仅网络通道生效），会话中途断网后刷新失败即激活离线降级；离线（含降级态）时「预约」「取消预约」按钮 `:disabled`（prop 下沉至 StudentExamList，与既有窗口/预约态门控叠加），恢复在线自动复原。**「进入」按钮一律不得因离线禁用**（作答页离线重入是设计场景）。
- **U-9（MessageCenter.vue）**：两查询补 `error` 解构；失败以错误 Alert 显性呈现后端 message（形态对齐 StudentExamList 顶部 error Alert 的 U-1 范式）：列表查询失败时列表区与空态不渲染；未读数查询失败时角标不出现（错误已显性呈现）且列表成功照常渲染；成功空数组保留既有业务空态；标已读补 try/catch + message.error（成功路径失效联动零改动）。**维持既有非轮询形态**（onMounted invalidate + 手动刷新 + 标已读后 invalidate），不得改成轮询、不自动重试。
- **U-10（StudentExamList.vue）**：取消预约按钮包 Popconfirm（title 点明「取消后需重新预约」，参照 `teacher/tags/index.page.vue` Popconfirm 惯例），`@confirm` 才 `emit('cancel')`；确认后既有取消链路（成功 message.success / 失败 message.error 透出后端原因）零改动。
- **用例只增不减**：新增「在线加载→中途断网→刷新失败→降级激活」时序用例（变异回退为 `navigator.onLine` 直查须击红）、门控用例（禁用预约/取消 + 进入不禁用 + 恢复在线复原）、取消确认用例、MessageCenter 失败三态与空态用例。

## 红线与不得触碰项

- 零后端、零契约、零表变更：`src/`、`pom.xml`、`schema.sql`、`openapi.yaml` 一律零触碰；`.github` 仅允许 `.github/ci/gate-baseline.json` 的 frontendTests 随实施笔同笔上调（backendTests 与 openapiPaths 持平，以开工实测对账）。
- 「进入」按钮不得因离线禁用；不触碰离线重入四条红线（离线进入考试不做、绕过服务端交卷时间窗不做、离线反作弊不做、API 请求 network-only 禁缓存）。
- 断言两禁形态禁用（同行 if(+.exists() 守卫、.exists()||双通），词法护栏 `decorativeAssertionGuard.spec.ts` 常驻；断言直断真实 data-test。
- 既有行为零回归：列表页在线路径、作答页、`offlineExamList.spec.ts` 既有五用例、`MessageCenter.spec.ts` 既有三用例断言零改动（useQuery mock 补 error ref 一类基建适配允许，如实登记）；用例总数只增不减。
- 不碰共享 dev / Docker / 真实浏览器；纯静态 + 单测可证。

## Impact

- 运行时影响：仅前端三文件与测试文件；成功路径行为零变化，失败/离线路径由静默变显性。
- 门禁：后端 surefire 预期与 gate-baseline backendTests 持平（开工实测对账，当前 423/0/0/1）；前端 vitest 基线 88 文件 / 622 例，净增数 = 新增用例数 N，gate-baseline.json frontendTests 同笔上调 622 → 622+N；openapiPaths 78 持平。
- 规范：frontend 能力域末尾 ADDED 两条 Requirement（「学生考试列表离线判定响应性与预约操作门控」4 Scenario +「消息中心查询失败显性化」5 Scenario，共 9 Scenario），随收口笔逐字合入 `spec/specs/frontend/spec.md`；`spec/README.md` 登记随收口笔（变更存续期不改 README，参照 `fix-frontend-exam-page-error-states` 形态）。

## 复核分级（立项时显式声明）

本卡为**前端业务代码变更 → 分级②**：复核子 agent 全量六项本地独立复跑（git 拓扑 / 红线域 diff / 双端门禁 / 关键代码抽查 / spec 场景映射 / 证据链核对 + 失真对照），**独立复现主线保留**（U-8 非响应式回退等关键变异须独立复现）；派发轮次采用「先复核后收口」：执行侧停在实施笔本地提交（不 push、不合并、不归档），复核 PASS 后另行派收口。

## 验收与停止条件

- **验收**：9 Scenario 红后绿；关键变异击红-复原-回绿（U-8 非响应式回退 / 撤离线门控 / 撤 error 消费 / 撤 Popconfirm，至少四条）；实施笔后双端门禁复跑留证且计数对账（前端 622+N 净增=N、后端持平、paths 78 持平）；零后端改动凭据（`git diff --name-only <开工基线> HEAD -- src pom.xml schema.sql openapi.yaml` 为空）；收口笔零代码改动（仅 spec 合入、变更归档、README 登记三类）。
- **停止条件**：①与本卡无关的既有测试失败（环境抖动按既有惯例重跑一次留证，仍红则停）；②发现三候选之外的既有 spec Scenario 违规或接口缺口 → 停报另立；③门禁红或计数与预期不符；④K5 锚点复核与台账不一致（疑似基线漂移）。

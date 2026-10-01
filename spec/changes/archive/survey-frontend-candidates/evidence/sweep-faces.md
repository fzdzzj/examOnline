# 六面巡查：文件清单与巡查方法摘记（survey-frontend-candidates）

> 巡查时 revision：`bd5c645`（分支 `feature/update-monitor-overview-submission-projection`，2026-10-01）。
> 全量对象：`frontend/src` 共 **46 个 `.vue`** + `api/`、`components/`、`constants/`、`hooks/`、`pages/`、`router/`、`store/`、`utils/` 相关 `.ts`。
> 方法：只读通读 + grep 锚点；**未启动 dev server、未跑构建、未碰 Docker/共享 dev/任何真实运行环境**。
> 生成客户端 `frontend/src/api/axios/**`（`gen:api` 产物）不逐行审计，与本仓库既有「SSE 生成代码已判干净」结论一致。
> 计数核对：`Glob frontend/src/**/*.{vue,ts}` 结果中 `.vue` 逐条计数 = 46，与 proposal 一致。

## 面 1：API 调用模式（api/ + pages/）

- 过掉文件：`api/apiClient.ts`、`api/queryClient.ts`、`api/sessionRefresh.ts`、`api/config.ts`、`api/errorMap.ts`、`api/types.ts`；`pages/(dashboard)/teacher/{exams/[id],exams/index,exams/create,grading/index,scores/index,questions/index,papers/index,papers/create,papers/[id],tags/index,classes/index,reviews/index,absences/index,makeups/index}.page.vue`、`pages/(dashboard)/student/{exams/index,exams/[id],scores/index}.page.vue`、`pages/(dashboard)/index.page.vue`、auth 六页。
- 怎么查：逐页读 `useQuery`/请求调用，记录每页请求数与串并行；grep `useQuery|refetchInterval|enabled:|size:|page:` 定位取数范围与轮询。
- 命中候选：候选 1（监考总览轮询不按页签门控）、候选 2（成绩页考试下拉仅第一页）、候选 3（批改页满页累积翻页）、候选 4（题库批量删除串行）、候选 5（组卷批量入卷串行）。
- 判干净：`apiClient` 注入 Bearer + 解信封 + 401 单飞 Refresh（成熟，不立项）；`queryClient` 全局 `staleTime`/`retry` 精简。

## 面 2：生命周期与清理（hooks/ + pages/）

- 过掉文件：全部 9 个 hooks（`useAutoSaveDraft`、`useBehaviorReport`、`useServerCountdown`、`useExamMonitor`、`useSubmitExam`、`useStudentTaking`、`useClassRoster`、`useRandomDraw`、`useGradingFlow`）+ 各页面 setup。
- 怎么查：grep `setInterval|setTimeout|addEventListener|onScopeDispose|onUnmounted|watch\(`，逐个确认清理路径与竞态守卫。
- 判干净：`forgot-password.page.vue:130-132` `onUnmounted` 清 interval；`useAutoSaveDraft`/`useBehaviorReport`/`useServerCountdown` 均 `onScopeDispose` 清理；无未清理的 SSE（生成 SSE 客户端未被业务使用）；路由快切竞态由 `seededFor`（学生作答页 `:300-316`）等守卫覆盖。
- 命中候选：候选 6（`useServerCountdown` deep watch 整卷，属面 3 成本、面 2 生命周期交汇）。

## 面 3：渲染与大列表（pages/ + components/）

- 过掉文件：全部 20 个 `components/**/*.vue` + 各列表页。
- 怎么查：读表格 `data-source`/`:pagination`，grep `pagination|pageSize|deep: true|computed|v-for`，核对「列表规模 × 是否服务端分页 × 是否虚拟化」。
- 命中候选：候选 6（deep watch 整卷）、候选 7（答题页答案全量浅拷贝）、候选 8（主观题批改面板同题全量行 + 客户端分页）。
- 判干净：`QuestionPickerModal` 服务端分页 PAGE_SIZE=20；`papers/index` 一次取 100（后端单页上限、无 total，已注释）；`absences`/`makeups`/`reviews`/`scores` 列表页客户端分页 pageSize=10；`ExamSnapshotPreview`/`BehaviorTimeline` 量级受控。

## 面 4：状态与重复提交（store/ + pages/ + api/）

- 过掉文件：`store/index.ts`、全部提交类页面与 `useSubmitExam.ts`、`api/apiClient.ts`。
- 怎么查：grep `loading|submitting|disabled|_retry|phase|单飞|幂等`，核对提交按钮防重与 token 过期各路径。
- 判干净：`SubmitExamPanel`（`:29` `:disabled="submitting || phase === 'submitted'"`）+ `useSubmitExam` 单飞 + 后端 SETNX/防重表/CAS（阶段 22 已收口，不立项）；401 在 axios 路径单飞 Refresh 重放，blob 导出走同一 `apiClient` 覆盖；Grafana 探测用无认证 `no-cors` fetch（设计如此）。
- 命中候选：候选 4/5（批量串行未做并发/批量化，属提交类路径一致性）。

## 面 5：网络与包体（router/ + 各 import）

- 过掉文件：`router/index.ts`、`router/guard.ts`、`router/access.ts`、全部页面/组件 import 段。
- 怎么查：读 `createRouter` 路由来源与页面组件引入方式；grep `from 'ant-design-vue'`（命中 60 文件）、`from 'echarts'|from 'lodash'`、`import(`。
- 判干净：路由使用 `vue-router/auto-routes` 生成 routes（页面按路由懒加载分包），`router/index.ts` 无手工 eager import；echarts/lodash 整包引入与 SSE 生成代码为本轮既有结论，不重复立项。
- 命中候选：候选 9（待测量）——`ant-design-vue` 具名导入是否被生产构建 tree-shake 无法静态判定，登记待测量，**本轮零构建**。

## 面 6：错误与边界路径（api/ 拦截器 + pages/）

- 过掉文件：`api/apiClient.ts`、`api/errorMap.ts`、各页面错误/空态分支、`components/exam/ExamSnapshotPreview.vue`、`components/student/StudentExamList.vue`、`components/observability/GrafanaEntry.vue`。
- 怎么查：grep `catch|finally|error|Empty|404|notFound|loading`，核对失败后 loading 是否复位、空态与错误态是否分开。
- 判干净：列表/表单页 loading 均在 `finally` 复位；`StudentExamList` 空态与错误态分开（不把故障画成「暂无考试」）；`ExamSnapshotPreview` 以 404 表示「尚未发布」并呈空态；`GrafanaEntry` 区分 `unconfigured/unreachable/reachable`；`isNotPublishedError` 把「成绩待发布」识别为状态而非错误弹窗。
- 命中候选：**无新增**（未发现违反既有 spec Scenario 的行为缺陷，故不触发停止条件 1）。

## 与「已裁决不重复立项」的比对

- 逐条候选登记前已比对 `proposal.md`「已裁决不重复立项」清单与 `spec/specs/*/spec.md`。
- 已裁决且**未**立为候选：mustChangePassword 守卫、`DraftSyncBadge` 措辞、切屏归并上报（含 durationMs）与兜底、草稿 30s 间隔 + 30s 防抖双路径、交卷防重/幂等、倒计时「服务端时间 + 单调秒表」口径、echarts/lodash 整包引入、SSE 生成代码。
- 差集说明：候选 1（页签门控）、候选 6（deep watch 成本）触碰的是上述已裁决模块的**性能成本**而非其**行为口径**，故不构成重复立项；候选 6 明确不改归零判定口径。
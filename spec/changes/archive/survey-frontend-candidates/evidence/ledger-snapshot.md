# 台账全文快照（survey-frontend-candidates）

> 本文件为 `docs/frontend-optimization-candidates.md`（**不入库纪律文件**）的**全文快照**，随本变更归档入库，
> 以保证台账内容在 `docs/` 未入库的情况下仍可追溯。
> - 快照来源：`docs/frontend-optimization-candidates.md`
> - 原件 sha256：`27F335BD67B69AFD318DA6CED6ADC75A4547232D0EC5F1B34442A5B1FC2A2A83`
> - 快照时 revision：`bd5c645`（分支 `feature/update-monitor-overview-submission-projection`，2026-10-01）
> - 快照方式：`[System.IO.File]::AppendAllText`（UTF-8 无 BOM），内容逐字取自原件。
> - 边界：本快照为「候选 ≠ 已确认瓶颈 ≠ 已批准」的静态巡查记录，非行为规范，不得据以直接实施。

---
# 前端优化候选证据台账

> 静态巡查快照：2026-10-01，基于提交 `bd5c645`（分支 `feature/update-monitor-overview-submission-projection`）。
> **候选 ≠ 已确认的运行时瓶颈 ≠ 已批准的 OpenSpec 变更。**
> 本文只防遗忘、指向证据，不替代 `spec/specs/*/spec.md` 的行为规范、`spec/README.md` 的当前变更状态。
> 以后改代码或归档提案时，须在当时 revision 重新核对代码与规范，不能把本文快照当新测量。
> 由 `spec/changes/survey-frontend-candidates/`（前端只读巡查，零代码改动）产出；**本文为不入库纪律文件**。

## 巡查范围与证据边界

- 巡查 `frontend/src` 全部 46 个 `.vue` 与 `api/`、`components/`、`constants/`、`hooks/`、`pages/`、`router/`、`store/`、`utils/` 相关 `.ts`（生成客户端 `api/axios/**` 为 `gen:api` 产物，不逐行审计，与本仓库既有「SSE 生成代码」判定一致）。
- 六面：①API 调用模式 ②生命周期与清理 ③渲染与大列表 ④状态与重复提交 ⑤网络与包体 ⑥错误与边界路径。
- 此轮**只读本地代码与规范**；**没有**启动 dev server、**没有**跑前端构建、**没有**碰 Docker/共享 dev/任何真实运行环境。下列调用次数、数据规模与复杂度是从代码路径推得，不是耗时占比、帧率或优化收益实测。
- 涉及收益判定的疑点一律登记为「待测量」，不静态断言收益；任何候选实施前须按 `spec/specs/performance/spec.md`「先归因、一次只改一类」另行立卡走独立 delta。
- 「已裁决不重复立项」清单（`proposal.md`）与各 `spec/specs/*/spec.md` 为登记前比对基准，已裁决行为不再立项（见文末「已处理、不能重复立项的路径」）。

## 候选 1：监考总览轮询不按页签门控（面 1／面 2 交叉）

**已核代码**

- `frontend/src/pages/(dashboard)/teacher/exams/[id].page.vue`：`activeTab` 为 `overview | snapshot | roster | monitor | behavior`（第 286 行）。同一页内**行为日志**查询按页签门控——`enabled: … && activeTab.value === 'behavior'`（第 521–525、557 行）；而**监考总览**查询 `useQuery`（第 364–375 行）的 `enabled` 只来自 `createMonitorQueryOptions`，不读 `activeTab`。
- `frontend/src/hooks/useExamMonitor.ts:31-42`：`createMonitorQueryOptions` 的 `enabled: Number.isInteger(examId) && examId > 0`——只校验 examId，与页签无关；`refetchInterval: MONITOR_POLLING_INTERVAL_MS`。
- `frontend/src/constants/monitor.ts`：`MONITOR_POLLING_INTERVAL_MS = 10000`（10s）。
- 结论（代码可证）：只要详情页启用了 examId，**无论停在哪一个页签**（概览/快照/名单/监考/行为），监考总览都按 10s 持续轮询。

**待验证 / 最小下一步**：先量「详情页停留某非监考页签时，10s 轮询的实际请求数与后端耗时占比」——若确为可观开销，评估把 `enabled` 收紧为 `… && activeTab === 'monitor'`（与行为日志同法）；须守住「轮询属后台刷新、失败不弹重试」的既有取舍（`useExamMonitor` 注释）与 `spec/specs/frontend/spec.md`「监考与行为日志界面」的呈现要求。一次只动这一个因素，不混改轮询间隔。

## 候选 2：成绩页考试下拉仅取第一页（面 1）

**已核代码**

- `frontend/src/pages/(dashboard)/teacher/scores/index.page.vue:216`：`const PAGE_SIZE = 50`；第 229–237 行 `examPage = ref(1)`，`query: { page: examPage.value, size: PAGE_SIZE }`。全页**无**对 `examPage` 的自增/翻页（grep `PAGE_SIZE|examPage` 仅 4 处，无 watch）。
- 结果（代码可证）：教师考试数超过 50 时，成绩页考试下拉**只列第一页**，更早的考试无法在页内选中。

**待验证 / 最小下一步**：确认教师考试量级与是否真有 >50 场使用场景；若有，评估与候选 3 统一「下拉取数策略」（服务端搜索/分页选择器），而不是各自补一段翻页。改造须保持 `spec/specs/score-management/spec.md` 的发布/撤回入口与状态门控。

## 候选 3：批改页考试下拉「满页即再拉一页」的累积翻页（面 1／面 4）

**已核代码**

- `frontend/src/pages/(dashboard)/teacher/grading/index.page.vue:176-205`：`PAGE_SIZE = 50`；`examPage = ref(1)`；`watch(exams, list => { if (list.length === examPage.value * PAGE_SIZE) examPage.value += 1 })`——**满页即再拉一页**，直到最后一页不满为止；注释自陈「分页信封无 total：当页满页就再拉一页，攒出完整候选」。
- 与候选 2 同源、策略相反：本页会**连续 N 次**请求页面以攒全量，成绩页只取第一页。

**待验证 / 最小下一步**：量教师考试量级下该策略的实际请求数与首屏耗时；若考试数增长，评估改为服务端搜索式选择器（一次一页、按输入过滤）。不得为「省请求」而牺牲下拉可选项完整性——`spec/specs/frontend/spec.md` 要求批改工作台「呈现待批改队列并支持分页与筛选」，考试选择口径变动须与该 Requirement 对齐。与候选 2 应作为**同一个下拉取数决策**统一处理，不分别叠加。

## 候选 4：题库批量删除逐条串行（面 1／面 4）

**已核代码**

- `frontend/src/pages/(dashboard)/teacher/questions/index.page.vue:232-252`：`onBatchDelete` 对 `selectedIds` 执行 `for (const id of ids) { await unwrap(deleteQuestion(…)) }`——**逐条串行** N 次请求，各自 try/catch 计数成功/失败。
- 面向：题库多选后批量删除；N 等于勾选数，逐条 await 使总耗时随勾选数线性增长（网络 RTT × N）。契约层是否有批量删除端点未在本次巡查确认（若后端无批量端点，串行是当前唯一形态）。

**待验证 / 最小下一步**：先确认后端题库是否存在批量删除接口；若无，评估是否需要（属后端能力，另立卡，不在本域）。有批量端点时再评估前端切换；须保留「部分成功不伪装成全部成功」的既有提示口径（成功/失败分计）与软删除语义（`spec/specs/frontend/spec.md`「软删除语义以后端为准」）。

## 候选 5：组卷批量入卷逐题串行（面 1）

**已核代码**

- `frontend/src/pages/(dashboard)/teacher/papers/[id].page.vue:356-378`：`onPick` 先按 `paperQuestionIds` 去重，再 `for (const question of fresh) { await unwrap(addQuestion(…)) }`——**逐题串行**加入试卷。
- 同文件「卷内分值编辑」已有避免逐键触发的取舍（第 380 行注释：blur/回车时才提交，避免逐键触发 PUT），说明作者已按高频路径设防；本候选是**批量**路径的同类问题。

**待验证 / 最小下一步**：确认后端是否有批量入卷端点；量单次组卷勾选数分布与实际串行耗时。若有批量端点再评估切换；须保持入卷去重、默认分不回填题库、顺序可 `move` 的既有语义（`spec/specs/frontend/spec.md` 组卷相关场景）。

## 候选 6：`useServerCountdown` 对整卷快照做 deep watch（面 3／面 2）

**已核代码**

- `frontend/src/hooks/useServerCountdown.ts:136-144`：`watch(() => toValue(source), snapshot => { engine.value = createServerCountdownEngine(snapshot, { now }); advance(); }, { immediate: true, deep: true })`——注释自陈「答题数据是每次 refetch 换一个新对象，deep 保证字段变了就重新锚定」。
- 调用方 `frontend/src/pages/(dashboard)/student/exams/[id].page.vue:209`：`useServerCountdown(() => snapshot.value)`，而 `snapshot` 是**含整卷 `questions` 数组**的 `EnterExamResponse`。`deep: true` 会对该响应做深层遍历以建立依赖，卷面越大遍历成本越高——但**归零判定口径**（只读后端字段 + 单调秒表）不受影响。

**待验证 / 最小下一步**：量「整卷快照的 deep watch 建立/触发成本」在同卷题量下的占比；若可观，评估改为对**窄化的时间字段**（`remainingSeconds` / `deadlineTime` / `serverTime`）建立 watcher，而非整卷对象——须守住 `spec/specs/frontend/spec.md` 与阶段 22 硬约定 2「倒计时以服务端时间为准、前端不推算」。一次只改这一个因素。

## 候选 7：学生答题页答案全量浅拷贝（面 3）

**已核代码**

- `frontend/src/pages/(dashboard)/student/exams/[id].page.vue:318-323`：`onAnswer(key, value) { answers.value = { ...answers.value, [key]: value }; autoSave.notifyAnswered(); }`——每次作答（含**简答题逐键输入**）都对整个 `answers` 映射做一次浅拷贝（O(题数)），并触发一次本地 IndexedDB 落盘（`notifyAnswered`）。
- `frontend/src/utils/draftStorage.ts`：`notifyAnswered` 写的是本地缓存（无网络负载），但仍是每次击键一次 IndexedDB `put`。

**待验证 / 最小下一步**：量简答题连续输入时的拷贝与 IndexedDB 写入频率/耗时；若可观，评估「按题局部更新 + 本地写入节流」（结构不变、契约不变）。须守住草稿版本协议与「客户端负责版本递增」口径（阶段 22 硬约定 6/7），不得因局部更新破坏 `mergeDrafts` 的保守合并前提。

## 候选 8：主观题批改面板同题全量学生行 + 客户端分页（面 3）

**已核代码**

- `frontend/src/components/postexam/SubjectiveGradingPanel.vue:162-167`：`rows: SubjectiveGradeRow[]` 由父页传入（题 156 行注释：**同题全部学生行**）；`:61-63` 表格 `:pagination="{ pageSize: 10, showChanger: false }"` 为**纯客户端**分页；`:182-193` `filteredRows` / `gradedCount` 对全量 `props.rows` 做过滤/计数；`answer` 列（第 173 行）直接渲染作答全文（简答题可能很长）。
- 结果（代码可证）：单题行数 = 该场进入考试的学生数（无服务端分页），大班/大场次时一次性取回并渲染全文。

**待验证 / 最小下一步**：确认后端 `subjectiveRows` 端点是否支持按题分页；若无，评估是否下沉分页（后端能力，另立卡）。前端侧不得先做「截断作答全文」——那会掩盖批改所需信息。须保持 409/1012 冲突回填与 `expectedVersion` 乐观锁语义不变。

## 候选 9（待测量）：`ant-design-vue` 具名导入的 tree-shaking 与路由分包证据（面 5）

**已核代码**

- 各页面/组件普遍 `import { Card, Table, Form, … } from 'ant-design-vue'`（如 `login.page.vue:45`、`StudentExamList.vue:73`、`QuestionForm.vue:132-145` 等；grep `from 'ant-design-vue'` 命中 **60 个文件**（含 `__tests__`），均为具名导入）。
- 路由级分包：`frontend/src/router/index.ts:1-12` 使用 `vue-router/auto-routes` 的 `routes`（unplugin-vue-router 生成，页面按路由懒加载）；`router/index.ts` 无手工 eager import 页面组件。
- 证据边界：**具名导入是否被 Vite 生产构建真正 tree-shake、以及 ant-design-vue 实际进包体积**，本次**静态巡查无法判定**（需构建产物 / 产物体积分析），故本条**只登记为待测量，不声称包体收益**。

**待验证 / 最小下一步**：在获得构建授权时，用产物体积分析（如构建后查看 chunk 构成）量 ant-design-vue 占比与按需引入（unplugin-vue-components 之类）的收益。**本轮不为此启动构建**（巡查停止条件 3：疑点需构建证据即登记待测量）。与已判干净的「echarts/lodash 整包引入」「SSE 生成代码」结论不重复；若结论相同则不立项。

## 已处理、不能重复立项的路径

- 访问控制与强制改密：`router/access.ts` 的 `decideNavigation` 强制改密前置、`change-password.page.vue` 不本地持久化状态——已裁决（`add-frontend-must-change-guard` / `add-auth-must-change-password`）。
- 草稿：`DraftSyncBadge` 措辞约束、`useAutoSaveDraft` 30s 间隔 + 30s 防抖双路径引擎（节流优先修复已收口）——已裁决，不立项。
- 切屏上报：一次离开一条（含 `durationMs`）、交卷前/页面暂停/beforeunload 兜底——已裁决（`behaviorEvents.ts` / `useBehaviorReport.ts`），不立项。
- 倒计时的「只读后端时间 + 单调秒表」口径 —— 阶段 22 硬约定 2，`useServerCountdown` 已实现，不重报（候选 6 只针对 deep watch 成本，不改口径）。
- 交卷防重与幂等：`useSubmitExam` 单飞守卫、`SubmitExamPanel` loading+disabled、后端 SETNX/防重表/CAS —— 已收口，不立项。
- `fix-frontend-paper-table-render`、`accept-frontend-19-23` 等已收口前端卡覆盖的行为 —— 不重复。
- 本轮模式级抽查已判干净的三类（`v-for` key、echarts/lodash 整包引入、SSE 生成代码）—— 结论相同，不立项（候选 9 仅新增「ant-design-vue 是否 tree-shake」这一未判项，且只作待测量）。
- `api/apiClient.ts` 的 401→单飞 Refresh→重放、blob 原样放行 —— 已实现的成熟路径，不立项。

## 后续决策纪律

1. 先重查现时 `spec/README.md` 与源代码，挑**一个**因素；优先可量且低风险者（候选 1 页签门控、候选 4/5 批量串行）。**代码可证增长规律，是否值得优化仍待同负载测量。**
2. 要实施时再为选中的一个方向写 `proposal.md`、`tasks.json`、`spec-delta.md` 三件套与子 agent 派工提示词；步骤含旧基线、语义回归、定向红绿、已提交实现 revision 的门禁与归档判据。**不得把本台账直接当已审批提案。**
3. 无复现、无剖面或改后指标没动时，保留候选并回到度量；不叠加新的参数。候选 2 与候选 3 属同一下拉取数决策，应合并评估、不分别叠加实施。
4. 候选 9 只作待测量；启动构建/产物体积分析须先获授权（本轮零构建）。
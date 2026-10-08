# fix-frontend-exam-page-error-states 提案（考试列表查询失败 + 编辑回填失败显性化）

> 立项依据：第二轮前端只读巡查台账 `docs/frontend-improvement-candidates-r2.md` 候选 U-6 / U-7（台账为不入库纪律文件，行号基于 `608adf5`；本卡开工基线与台账经现场复核，见 tasks.json ＆确认锚点）。指导主 agent 已裁决立项为纯前端变更。**本卡零后端改动。**

## Why

1. **U-6 考试列表页查询/搜索失败静默落空态**：`teacher/exams/index.page.vue` 的 `useQuery` 只解构 `{ data, isFetching, refetch }`，未消费 `error`；失败时 `data` 为 `undefined` → `rows = []`，antd 内置空态与「确实没有考试」不可区分。`add-exam-list-filtering` 改造（服务端筛选）后失败窗口/误导面更大。教师依赖该页做编辑/发布/删除决策，误判无考试可能导致重复建考。
2. **U-7 编辑模式回填失败静默**：`teacher/exams/create.page.vue` 编辑模式回填 `useQuery`（`detail2`）只解构 `{ data: editDetail }`，失败时 `watch` 不触发回填，表单停初始默认值且全文件 0 个 Alert。编辑模式下「保存修改」若被点击，存在**以默认/缺省值覆盖真实现有考试**的写风险（`update2` path 携带 examId）。

## What Changes

- **U-6（`teacher/exams/index.page.vue`）**：列表 `useQuery` 补 `error` 解构；查询失败以 Alert 显性呈现后端 message 且 Table 隐藏；成功空数组才落既有空态。搜索/筛选失败同口径。发布/强制结束/删除三个确认弹窗内的 Alert 一字不动（业务确认，非查询错误反馈）。不自动重试（沿批改域既有取舍）。
- **U-7（`teacher/exams/create.page.vue`）**：编辑回填 `useQuery` 补 `error` 解构；`isEditMode` 且回填失败时以 Alert 显性呈现后端 message；回填成功前编辑模式禁用「保存修改」（提交拦截 + 保存按钮 `:disabled` 双保险，防默认值误写）。回填成功后恢复保存按钮可用。创建路径语义零改动；编辑回填成功路径与既有提交链路一字不动。
- **禁止自动重试、不自动跳转**。

## Impact

- 运行时影响：若查询/回填失败，页面上显性呈现错误 Alert 并隐藏正常内容区（U-6）/禁用保存（U-7）；成功路径行为零变化。
- 门禁：本卡零后端改动，后端 surefire 预期 374/0/0/1 持平；前端 vitest 相对基线 63 文件 / 490 例净增数 = 新增用例数。
- 规范：新增一条 frontend Requirement（两页 Scenario 组），实施后合入 `spec/specs/frontend/spec.md`。

## 验收与停止条件

- **验收**：U-6 三态（失败 Alert+Table 隐藏 / 成功空态保留 / 成功数据零回归）与 U-7（回填失败 Alert+保存禁用 / 回填成功零回归 / 创建路径零回归）spec 红后绿；变异校验（撤 error 消费或撤禁保存即红、复原即绿）；已提交状态双端门禁复跑留证；零后端改动凭据 `git diff --name-only <开工基线> HEAD -- src pom.xml schema.sql openapi.yaml` 为空；按固定清单回传。
- **停止条件**：①与本卡无关的既有测试失败（环境抖动按既有惯例重跑一次留证，仍红则停）；②发现 U-6/U-7 之外的既有 spec Scenario 违规 → 停报另立；③门禁红或计数与预期不符。
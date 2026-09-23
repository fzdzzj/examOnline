# 提案：修复试卷详情题目表未渲染（fix-frontend-paper-table-render）

> 状态：已归档；`f0e1288` 的组件修复、20-3b/20-5 真实 Chromium + dev 复验均已据证据收口。专用锁定试卷 18 / 快照 1 按授权永久保留。来源：`spec/README.md` 遗留 #17（D1）、`frontend/docs/frontend-stages-walkthrough.md` 的 20-3b / 20-5。

## Why

教师试卷详情页模板使用 `<Table>`，但 `frontend/src/pages/(dashboard)/teacher/papers/[id].page.vue` 的 ant-design-vue 导入只有 `TableColumnsType` 类型、没有运行时 `Table` 组件（本提案起草时复核）。既有真机走查记录显示题目表未渲染，组卷后的改分、排序、移出入口不可见，预览只剩分值分布；因此 frontend 基线「组卷界面」中的相关场景不能据现有测试全绿宣称成立。

**最强反例**：补上导入后，若数据加载、表格插槽或操作请求仍有缺陷，不能仅凭“Vue 组件警告消失”宣称组卷流程恢复。需实际渲染包含题目的页面，至少覆盖可编辑与只读两种状态；真实后端交互如未实测应明确保留证据边界。

## What Changes

1. 在上述页面显式导入并使用 ant-design-vue 的 `Table`，不改 API 契约、成绩规则或后端。
2. 新增页面级回归测试：挂载真实页面组件（只 mock 数据/网络边界），断言有题目时表格及题目内容实际出现，未锁定时相关改分/排序/移出入口可见，锁定时保留只读题目内容；捕获组件解析失败告警。测试先在缺导入的状态下变红、修复后变绿，不能把整张表 mock 掉。
3. 按 `spec/README.md` 工作流验收：前端相关测试与既有 lint/type-check/vitest，必要时实走 20-3b、20-5；若环境不足，分清“组件渲染已证”和“真实交互未证”。只在证据足够时解除 frontend 基线的 D1 缺陷注记、回勾步骤并归档。

## Impact

- **规范**：`spec/specs/frontend/spec.md` 的「组卷界面」Requirement；本变更增补可观察的题目表呈现场景，不改变组卷业务语义。已知缺陷注记只在验收后据实更新。
- **代码/测试**：限定 `frontend/src/pages/(dashboard)/teacher/papers/[id].page.vue` 及该页面的针对性回归测试；收尾涉及本变更的 `tasks.json`、delta 与 `spec/README.md`。
- **用户**：教师应能在详情页看到题目及可编辑操作，在锁定状态查看只读内容；是否端到端恢复由验收证据判定。
- **API / 数据库**：无端点、契约、schema 或迁移改动；不纳入 D2 批改入口、补考展示和 E2 工具链。

## 实施边界与风险

小范围前端缺陷修复。若定向测试表明问题不止组件导入，先记录可复现证据，再就新范围单独裁决，不借本提案扩大重构。子 Agent 仅在提案获批后实施；不得混入工作区既有未提交的 `README.md`、主 Agent 指南或 `.trae/`。工作树与提交归属先确认，再进行收尾验收。

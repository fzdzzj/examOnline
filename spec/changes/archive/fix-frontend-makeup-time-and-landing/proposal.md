# 提案：补考时间控件与落地页收官（fix-frontend-makeup-time-and-landing，UX 台账 U-4 + U-5）

## Why

前端完善候选台账（`docs/frontend-improvement-candidates.md`）遗留最后两项纯前端体验优化项：
1. **U-4（时间输入控件与表单校验约定不一致）**：
   - `frontend/src/pages/(dashboard)/teacher/makeups/index.page.vue:108` 开始/结束时间为自由文本 `Input`（placeholder 示例「2026-09-20 09:00:00」）；
   - 对照考务创建页（`teacher/exams/create.page.vue:51-69`）统一采用 `DatePicker`；
   - 校验约定分裂：提交时通过 `message.warning` 进行文本解析检查（双轨拦截），未走 antd Form 的规范化 `rules` 机制。
2. **U-5（登录后落地页仍是开发验证卡）**：
   - `frontend/src/pages/(dashboard)/index.page.vue:21-40` 第二张卡片为阶段 19 开发遗留的「这一页验证了什么」验证清单卡，对日常业务无任何使用价值；
   - 用户登录后缺乏直观的概览与快捷入口，需自行展开侧边栏点击二级菜单。

本卡作为 UX 台账 U-4 与 U-5 的打包收官变更，彻底消除两处遗留体验缺陷。

## What Changes

### 1. 补考创建时间控件换为 DatePicker 并规范化 Form rules（U-4）
- `makeups/index.page.vue` 中的开始时间与结束时间 `Input` 替换为 `DatePicker`（`show-time`，format `YYYY-MM-DD HH:mm:ss`）；
- 表单响应式状态中 `startTime` 与 `endTime` 的值形态统一为 `Dayjs | null`；
- 引入 Form `:rules` 声明式校验：
  - 开始时间：必选校验；
  - 结束时间：必选校验 + 必须晚于开始时间校验（`isAfter` 自定义 validator）；
- 提交时格式化：将选定的 `Dayjs` 实例转换为 `YYYY-MM-DDTHH:mm:ss` 字符串，与既有 `toIsoLocalDateTime` 格式化口径逐字严格等价（带字面量 `T`）；
- 移除提交时的 `message.warning` 时间解析失败分支（清除双轨校验路径），统一由 `formRef.validate()` 拦截。

### 2. 落地页移除验证卡，换欢迎卡与常用入口（U-5）
- `(dashboard)/index.page.vue` 彻底移除 `:21-40` 的「这一页验证了什么」验证清单卡；
- 替换为欢迎卡：呈现用户显示名、角色标签与问候语，纯消费既有 Vuex auth store（`store.state.user`），**零新增后端数据请求**；
- 呈现常用快捷入口卡片：对齐 `(dashboard).page.vue` 侧边栏的既有路由定义与 `canAccess(role, prefix)` 角色过滤逻辑（学生/教师/管理员自适应展示对应入口），**零新造路由**，点击直达目标功能。

## Impact

### 受影响的规范
- `spec/specs/frontend/spec.md` — ADDED：
  - Requirement「补考时间控件与规范化表单校验」
  - Requirement「仪表盘落地页与常用入口」

### 受影响的文件
- `frontend/src/pages/(dashboard)/teacher/makeups/index.page.vue`
- `frontend/src/pages/(dashboard)/index.page.vue`
- 新增用例：
  - `frontend/src/pages/(dashboard)/teacher/makeups/__tests__/makeupTimeAndRules.spec.ts`
  - `frontend/src/pages/(dashboard)/__tests__/landingWelcomeAndEntries.spec.ts`

### 约束与不做
- **零后端改动**：`src/main`、`src/test`、`pom.xml`、`schema.sql`、`openapi.yaml` 均不动；
- **零契约变更与零 gen:api**；
- **不引入新依赖**（`ant-design-vue` 与 `dayjs` 已在 `package.json` 依赖内）；
- **makeups 其余行为零改动**（考试下拉累积、候选人查询、创建结果展示等完全不变）；
- **落地页零新增数据请求、零新造路由**。

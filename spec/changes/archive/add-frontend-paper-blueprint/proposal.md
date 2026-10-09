# 提案：试卷抽题矩阵蓝图模式（add-frontend-paper-blueprint，双向细目表，规格书 §3.1 创新点1）

## Why

规格书 `docs/examOnline需求规格说明书.md` §3.1 核心创新点 1「蓝图组卷（双向细目表）：保证试卷覆盖度和难度分布，教师只需填矩阵」在现有基线中尚未落地。
经现场核实，后端组卷引擎完备（`RandomDrawRequest.Rule` 已全面支持 `type` / `difficulty` / `tagIds` / `count` 任意组合，`previewDraw` 纯内存试抽与 `commitRandomDraw` 确认入卷均已支持），**本变更零后端、零 API 契约改动**。
前端 `papers/[id].page.vue` 既有「规则列表」抽题模式已支持多条规则录入与抽题入卷，但缺乏知识点×难度的二维矩阵细目表视图，教师无法直观把握考点覆盖度与难度梯度。

指导 agent 已裁决立项：形态为**纯前端矩阵视图**（双向细目表），与既有规则列表模式并列切换，编译为既有契约规则后复用既有 `drawFlow` 状态流转。

## What Changes

1. **矩阵→rules 独立编译工具函数**（`frontend/src/utils/blueprint.ts`）：
   - 输入：选中的标签列表与矩阵单元格数值映射 `matrix[tagId][difficulty] = count`；
   - 编译逻辑：遍历每个选中标签与难度（1=简单、2=中等、3=困难），非空且 count > 0 的单元格编译为 `Rule{tagIds: [tagId], difficulty, count}`，`type` 不设（题型维度不进矩阵，对齐规格书知识点×难度口径）；
   - 规则顺序确定性：按选中标签顺序外层循环、难度 1→2→3 内层循环；
   - 校验拦截：未勾选标签、或所有单元格均为空/0 时，由 `validateBlueprint` 返回警告拦截，不发请求；
   - 覆盖度合计：计算所有有效单元格数量之和 `totalBlueprintCount`。

2. **前端试卷详情页抽题区增强**（`frontend/src/pages/(dashboard)/teacher/papers/[id].page.vue`）：
   - **模式切换**：在随机抽题页签顶部增加模式切换（Radio.Group 按钮形态：「规则列表」与「矩阵蓝图」），切换时作废旧预览；
   - **标签行选择器**：教师从多选下拉框中勾选要纳入蓝图的知识点标签（复用既有 `listTags` 数据源）；
   - **细目表矩阵渲染**：已选标签作为矩阵行，易/中/难三列渲染 `InputNumber`（0–100），底部渲染覆盖度摘要行（合计题数）；
   - **三态齐备**：
     - 标签查询失败：按 U-1 三态口径呈现错误 Alert 并隐藏矩阵数据区；
     - 题库无任何标签：呈现空态提示「题库尚无标签，请先在题库管理中创建并打标」；
     - 预览/入卷失败：透传既有 400 题量不足或网络错误文案；
   - **流程复用与重置**：预览与入卷复用既有 `drawFlow`，确认入卷成功后重置蓝图输入，规则或矩阵改动触发预览失效。

3. **零破坏与零回归**：
   - 既有「规则列表」模式代码与交互逻辑完全保留；
   - 既有 `drawRules.ts` 与单测保持不变；
   - 严禁修改后端任何代码、契约文件及数据库定义。

## Impact

### 受影响的规范
- `spec/specs/frontend/spec.md` — ADDED：试卷抽题矩阵蓝图模式 Requirement（双向细目表矩阵交互、规则编译、三态与零回归）。

### 受影响的文件
- 前端实现：
  - 新增 `frontend/src/utils/blueprint.ts`；
  - 修改 `frontend/src/pages/(dashboard)/teacher/papers/[id].page.vue`；
- 前端测试：
  - 新增 `frontend/src/utils/__tests__/blueprint.spec.ts`；
  - 新增 `frontend/src/pages/(dashboard)/teacher/papers/__tests__/paperBlueprintDraw.spec.ts`；
- 规范文件：
  - `spec/changes/add-frontend-paper-blueprint/**`。

## 验收判据

1. **测试先行与全绿**：
   - `blueprint.spec.ts` 覆盖多格编译、空格跳过、全空拦截、规则顺序确定性、数量合计等场景；
   - `paperBlueprintDraw.spec.ts` 覆盖模式切换、无标签空态、查询失败 Alert、矩阵渲染输入与合计、预览拦截、入卷成功重置、规则列表模式零回归等场景；
   - 先红后绿留证；关键断言通过变异校验（撤 difficulty 映射、撤空矩阵拦截）。
2. **门禁命令**：
   - 前端：`npm run lint:check`、`npm run type-check:check`、`npm run test`（全部 exit 0，测试文件数与用例数净增）；
   - 后端：`mvnw.cmd clean test`（378/0/0/1 BUILD SUCCESS 持平，零破坏）。

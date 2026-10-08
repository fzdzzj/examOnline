# 提案：判分失败答卷手动给分入口（add-frontend-manual-score，前端台账 T-1 澄清后立项）

## Why

前端完善候选台账（`docs/frontend-improvement-candidates.md`）待澄清项 T-1：`manualScore`（答卷手动定分）端点**零消费、语义未上 UI**；该项此前因「与复核调分（`handle` 端点 `adjustedTotalScore`）的语义关系未厘清」而维持待澄清。本轮澄清结论：`manualScore` 与复核调分**不是同一个东西**，二者语义、对象、时机均不同，不构成重复能力（见下「澄清结论」）。

- `frontend/src/pages/(dashboard)/teacher/grading/index.page.vue:82-95`：失败清单每行只有 `Popconfirm` 包裹的「重判」（F-3 已交付），行内无第二动作；
- **重判走判分引擎重算**——若答卷数据本身损坏（如答案 JSON 无法解析、快照缺题），重判必然反复失败，教师**没有兜底通道**，只能卡在失败清单上；
- SDK `manualScore`（`POST /api/exams/{examId}/grading/submissions/{submissionId}/manual-score`）全仓零引用。后端本就按「教师裁定兜底」设计：`GradingController.manualScore` 返回 `ApiResponse<Void>`，`ExamGradingService.manualScore` 注释自陈「判分反复失败（如答案数据损坏无法解析）时，教师可……直接裁定客观题总分」「与重判的差异：重判走判分引擎重算，手动给分**完全绕过引擎——教师裁定即终局**」；
- 影响：spec 场景「判分失败处理」要求「支持重判**或手动给分**」（`spec/specs/grading/spec.md:102-112`），后端与契约均已就绪，但前端只有「重判」一条腿；「可见 → 可重判」之后仍缺「可裁定」。

### 澄清结论（T-1 结项判据）

| 维度 | `manualScore`（本卡） | 复核调分 `handle`（`adjustedTotalScore`） |
|---|---|---|
| 对象 | 判分引擎失败的答卷（`grading_status=2`） | 学生对已发布成绩发起的复核申请 |
| 时机 | 汇总成绩**之前**的判分阶段 | 成绩发布**之后**的申诉阶段 |
| 输入 | 客观题总分（`objectiveScore`，≤ 快照客观题满分） | 调整后的总分（`adjustedTotalScore`） |
| 语义 | 绕过引擎，教师裁定即终局 | 对已发布成绩的行政调整 |
| 前端现状 | 零消费（本卡接线） | 已有复核页全流程（不在本卡范围） |

二者无功能重叠，不存在「接一个即可、另一个多余」的关系。选本卡即择定「先补齐判分阶段的兜底裁定」这条最小闭环。

## What Changes

### 失败清单行内「重判」旁新增「手动给分」操作（唯一改动点）

- 每行失败项在既有「重判」之后加 `Popconfirm` 包裹的「手动给分」按钮（`type=link`、`size=small`，与「重判」同排同形态），确认层内提供 `InputNumber` 录入客观题总分；
- **数值输入精度对齐契约**：`ManualScoreRequest.objectiveScore` 为 `number`，后端校验 `@Digits(integer = 3, fraction = 1)`、`@DecimalMin("0")`。故 `InputNumber` 取 `:min="0"`、`:max="999"`、`:precision="1"`、`:step="0.5"`（与主观题打分输入 `SubjectiveGradingPanel` 同族口径）。**真实上限是「快照客观题满分」而前端此刻拿不到该值**，故不预判、不本地拦截，越限由后端裁决并以原文呈现；
- **确认文案必须明示「绕过引擎、不再重算」**：该操作语义为「教师裁定即终局」，与「重判」（引擎重算）不可混同。确认标题写明将对这一份答卷手动给分、该操作将绕过判分引擎且不再重算；
- **`onManualScore` 复用 `onRejudge` 的竞态防护 / 防重入 / 回填结构**：同一在途标记守卫（同一时刻只允许一条行内动作在途），防重复点击；请求前记 `examId`，响应到达时若 `selectedExamId` 已变即丢弃（与 `onRunGrading` 同口径）；成功走与重判同一份回填（移除该行 + `failed` 减一 / `success` 加一 + `invalidateQueries({queryKey:['grading']})` + `message.success`），失败走 `message.error(error.message)` 后端原文范式；
- **空值即不构成一次给分请求**：未录入分数时确认不发起请求（`objectiveScore` 缺失不是「后端会拒绝的业务错误」，而是这一次动作不成立）；不因此本地编造后端文案。

## Impact

### 受影响的规范
- `spec/specs/frontend/spec.md` — ADDED：Requirement「判分失败答卷手动给分」

### 受影响的文件
- `frontend/src/pages/(dashboard)/teacher/grading/index.page.vue`（失败清单行内第二入口 + `onManualScore` 处理函数）
- `frontend/src/pages/(dashboard)/teacher/grading/__tests__/manualScore.spec.ts`（新增用例文件；既有 `submissionRejudge.spec.ts` 的 mock 若不覆盖 `manualScore` 则补一行属测试基建适配，断言零改动）

### 约束与不做
- **零后端改动**：`src/main`、`src/test`、`pom.xml`、`schema.sql`、`openapi.yaml` 均不动；**零契约变更零 `gen:api`**（`manualScore` 与 `ManualScoreRequest` 已在生成 SDK 内）；
- **重判（F-3 既有）行为零改动**：失败行「重判」入口的文案、`Popconfirm` 标题、`onRejudge` 的路径参数、成功回填、仍失败换原文、请求失败原文呈现与切换考试丢弃逻辑一字不动，只在其后**加**一个动作；
- **失败清单既有展示零改动**：`<ul class="failure-list">` 的行文案结构（答卷 ID / 学生 ID / `error ?? '未知错误'`）原样保留，只在行末**加**操作；不重排、不改写、不加状态徽标；
- **整场判分与汇总成绩流程零改动**：`onRunGrading`、`run` 调用、`gradingRunError`、`total===0`/`failed>0`/`failed===0` 三段 Alert 判定与 `resolveScoreActions` 门控一字不动；
- **仅失败行呈现手动给分入口**：入口只存在于 `failures` 行内；`failed === 0` 或清单为空时页面上不出现任何「手动给分」；
- **不做复核调分**：`handle`（`adjustedTotalScore`）属成绩复核域既有能力，不在本卡范围；
- **不自动重试**：沿用批改域「绝不自动重试提交」的既有取舍，给分只由教师显式确认发起，失败后不自动再发；
- **不新造失败态载体**：给分的失败原因一律取后端原文。

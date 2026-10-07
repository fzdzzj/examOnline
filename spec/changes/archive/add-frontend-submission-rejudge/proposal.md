# 提案：判分失败答卷逐卷重判（add-frontend-submission-rejudge，前端台账 F-3）

## Why

前端完善候选台账（`docs/frontend-improvement-candidates.md`）F-3：判分失败清单只读，无逐卷重判入口。

- `frontend/src/pages/(dashboard)/teacher/grading/index.page.vue:82-90`：`gradingRunResult.failures` 渲染为只读 `<ul>`（答卷 ID / 学生 ID / 错误文案），行内无任何动作；
- 同文件 `onRunGrading`（:251-304）只有整场 `run`，失败后的唯一恢复路径是**整场重跑**——大场次若个别答卷失败（如某答卷答案数据异常），教师必须把已成功的全部答卷重判一遍，代价随成功卷数放大；
- SDK `rejudge`（`POST /api/exams/{examId}/grading/submissions/{submissionId}/rejudge`）全仓零引用。后端本就按「单卷重判 + 返回失败项」设计：`GradingController.rejudge` 返回 `ApiResponse<GradingRunResponse.FailureItem>`，`ExamGradingService.rejudge` 走 `gradeSafely` **同步返回本次判分结果**（成功时 `error` 为 null，失败时 `error` 为现场原因且 `grading_status=2` 已落库）；
- 影响：spec 场景「判分失败清单可见，不把部分失败报成全成功」已成立，但「可见」之后没有「可处置」。

本卡是 F-3 的纯前端接线，把「看得见失败」补成「单卷可处置」。

## What Changes

### 失败清单行内新增「重判」操作（唯一改动点）

- 每行失败项加 `Popconfirm`（轻量确认，文案说明将对这一份答卷重新判分）包裹「重判」按钮，`@confirm` 触发生成 SDK 的 `rejudge`，路径参数按 `types.gen.ts` 契约取 `examId`（当前所选考试）+ `submissionId`（该失败行）；在途禁用防重复点击；
- **响应语义按契约实际形状接线（同步结果，非受理回执）**：`RejudgeResponses[200] = ApiResponseFailureItem`，`unwrap` 后是**本次重判的结果行**——
  - `error` 为空 ⇒ 该卷已判分成功：**回填该行**（从失败清单移除，`failed`/`success` 统计随之调整，不新造第三种行形态），并经 `queryClient.invalidateQueries({ queryKey: ['grading'] })` 失效题级进度与学生行查询，`message.success` 提示；
  - `error` 非空 ⇒ 重判仍失败：**该行错误文案更新为后端本次原文**（清单不被破坏、行数不减），`message.error` 呈现原文；
- 请求本身失败（网络/403/404 等）走页面既有 `message.error(error.message)` 范式，呈现后端原文，失败清单与统计保持不变（不本地编造文案、不预拦截）。

## Impact

### 受影响的规范
- `spec/specs/frontend/spec.md` — ADDED：Requirement「判分失败答卷逐卷重判」

### 受影响的文件
- `frontend/src/pages/(dashboard)/teacher/grading/index.page.vue`（失败清单行内加入口 + 重判处理函数）
- `frontend/src/pages/(dashboard)/teacher/grading/__tests__/gradingRunFlow.spec.ts`（同文件扩展重判用例；既有整场判分断言零改动，`@tanstack/vue-query` mock 补 `useQueryClient` 属测试基建适配）

### 约束与不做
- **零后端改动**：`src/main`、`src/test`、`pom.xml`、`schema.sql`、`openapi.yaml` 均不动；**零契约变更零 `gen:api`**（`rejudge` 已在生成 SDK 内）；
- **整场判分与汇总成绩流程零改动**：`onRunGrading`、`run` 调用、`gradingRunError`、`total===0`/`failed>0`/`failed===0` 三段 Alert 判定与 `resolveScoreActions` 门控一字不动，`canRunGrading` 仍只在 `ENDED` 出现整场入口；
- **失败清单既有展示逻辑零改动**：`<ul class="failure-list">` 的行文案结构（答卷 ID / 学生 ID / `error ?? '未知错误'`）原样保留，只在行末**加**一个操作；不重排、不改写、不加状态徽标；
- **仅失败行呈现重判入口**：入口只存在于 `failures` 行内；`failed === 0` 或清单为空时页面上不出现任何「重判」；成功卷只有统计值，不为其造行；
- **不自动重试**：沿用批改域「绝不自动重试提交」的既有取舍，重判只由教师显式确认发起，失败后不自动再发；
- **不做手动给分**：`manualScore`（T-1 后端待决项）不接，失败到无可重判时的兜底通道保留在台账，不在本卡范围；
- **不新造失败态载体**：重判的失败原因一律取后端原文。

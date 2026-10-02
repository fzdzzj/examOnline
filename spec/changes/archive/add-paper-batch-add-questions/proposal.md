# 提案：组卷批量入卷端点（add-paper-batch-add-questions，P2）

## Why

后端能力缺口登记（`docs/backend-optimization-candidates.md` 缺口 B，2026-10-02 基于 `bfc7bfa` 现场核验）：手动组卷仅有单题入卷端点 `POST /api/papers/{id}/questions`，前端组卷页选题器 `onPick` 只能逐题串行 N 次请求（`papers/[id].page.vue`），且循环无 try/catch——中途任一题失败即中断，此前已入卷的题保持已入卷且无部分成功提示（半批静默缺陷）。Service 层积木已在：`PaperService.addQuestionInternal`（卷内查重 + 题号追加 + 默认分回退）已被 `commitRandomDraw` 循环复用，整批单事务模式有既有先例。

前端候选台账候选 5（组卷批量入卷串行）因此缺口冻结至今；本卡为解冻后的后端立项，同时收口前端候选 5 与 `onPick` 半批静默缺陷。

## What Changes

1. **新增批量入卷端点** `POST /api/papers/{id}/questions/batch`（类级 `@RequirePermission("paper:manage")` 随类继承）：
   - 请求体 `BatchAddPaperQuestionsRequest`：`items` 非空、单次至多 100 项（与分页上限同量级，防误传全库）；每项 `{ questionId: Long(必填), score: BigDecimal(可选，缺省用题目默认分——与单题语义一致) }`；
   - **整批单事务全有全无**（`rollbackFor = Exception.class`，与 `commitRandomDraw` 同构）：任一题失败整体回滚，杜绝半批入卷；
   - 失败语义：请求内重复 `questionId` → 400「请求内存在重复题目」（前置显式校验，不靠逐题查重间接暴露）；题目已在卷中 → `DATA_ALREADY_EXISTS`「该题目已在试卷中」（与单题端点同文案）；题目不存在或已软删 → 404「题目不存在或已删除」；试卷已锁定（快照生成）→ 400（既有 `assertNotLocked` 文案）；被进行中考试绑定 → 既有 `assertPaperEditable` 文案；非归属教师 → 403/404（既有 `getOwnedPaper`）；
   - 实现为循环复用 `addQuestionInternal`（`commitRandomDraw` 同款既有模式）；**本卡不做查询数优化**（N 次逐题查重照旧）——一次只改一类，查询数归因另立卡；
   - 返回 `PaperDetailResponse`（与 `updateOrder`/`commitRandomDraw` 一致，前端组卷页本就以详情刷新视图）。
2. **契约与前端联动**：仓库根 `openapi.yaml` 经路 A 离线重导出（`OpenApiContractTest#exportOpenApiContract -DexportContract=true`）；`frontend` 下 `npm.cmd run gen:api` 再生成客户端；`onPick` 改为单次批量调用 + try/catch 错误提示（收口半批静默缺陷），成功提示与 `invalidatePaper` 既有行为保留，「所选题目均已在试卷中」前端前置提示保留（后端兜底不变）。
3. **词法护栏（前端）**：组卷页源码断言不再含串行 `await unwrap(addQuestion(` 入卷循环、含批量调用——变异还原可红。
4. **后端集成测试**：批量成功（混合显式分值/默认分、题号接续既有题目）、已在卷中整体回滚（验证零新增行）、请求内重复 400、含不存在题 404 整体回滚、锁定试卷拒绝；CI surefire 计数必须恰好增加新增用例数（基线 350/0/0/1）。
5. **既有语义零改动**：单题端点、`addQuestionInternal` 内部逻辑、总分校验口径（入卷不校验题分和=总分，校验仍在 `updateMeta` 与快照两处）、题号重排、软删题目不可入卷、默认分不回填题库——全部原样。

## Impact

### 受影响的规范
- `spec/specs/question-bank/spec.md` — ADDED：批量加题入卷 Requirement（含整批事务与失败语义 Scenario）。

### 受影响的文件
- `src/main/java/com/exam/paper/`：`PaperController`（新端点）、`PaperService`（`addQuestions` 批量方法）、新增 `dto/BatchAddPaperQuestionsRequest`；
- `src/test/java/com/exam/paper/`：新增集成测试；
- 仓库根 `openapi.yaml`（路 A 重导出）；
- `frontend/src/api/axios/**`（gen:api 再生成）、`frontend/src/pages/(dashboard)/teacher/papers/[id].page.vue`（`onPick`）、新增/扩展前端 spec。

### 需要迁移
- 无（零表变更，`schema.sql` 不动；`paper_questions` 结构与约束原样）。

## 边界与不做
- 不动 `commitRandomDraw`/抽题链路；不做逐题查重的批量化（另立卡）；不加 `@RateLimit`（单题入卷端点无此注解，教师低频操作同量级）；不动阶段 19–23 已验收的任何前端行为口径。

## 验收判据
- 护栏/新用例先红（端点未实施时 404 级证据）后绿（定向绿 + 全量绿）；
- 实施笔仅含 `src/main`、`src/test`、`openapi.yaml`、`frontend/src`；收口笔仅含 `spec/`（`git diff --name-only <实施笔> HEAD -- src frontend pom.xml` 为空）；
- 仓库根 `mvnw.cmd clean test` @ 实施笔：四计数较基线恰好 +新增用例数，`Failures/Errors` 为 0，`Skipped` 恒 1（契约导出测试不随常规门禁跑）；
- 前端 `lint:check`/`type-check:check`/`test` 三项退出码 0，vitest 计数较基线（44 文件 / 383 例）恰增新用例数；
- `git status` 终态除白名单未跟踪文件外干净。

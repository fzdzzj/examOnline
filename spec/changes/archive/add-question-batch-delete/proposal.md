# 提案：题库批量删除端点（add-question-batch-delete，P2）

## Why

后端能力缺口登记（`docs/backend-optimization-candidates.md` 缺口 A，2026-10-02 基于 `bfc7bfa` 现场核验）：题库仅有单题软删端点 `DELETE /api/questions/{id}`，前端题库页 `onBatchDelete` 只能逐题串行 N 次请求（`questions/index.page.vue:232-252`）。前端候选台账候选 4 因此冻结。

## What Changes（关键裁决：逐题结果信封，非全有全无）

1. **新增批量删除端点** `POST /api/questions/batch-delete`（类级 `@RequirePermission("question:manage")` 随 QuestionController 既有类级注解继承）：
   - 请求体 `BatchDeleteQuestionsRequest`：`ids` 非空 @NotEmpty、@Size(max=100)、请求内重复 → 400「请求内存在重复题目」（与批量入卷口径一致）；
   - **逐题结果信封语义**（裁决依据：前端既有「成功/失败分计、部分成功不伪装成全部成功」提示口径必须保留，全有全无会把「第 3 题被进行中考试引用」连坐到已可删的前 2 题并改变现有 UX）：先校验全部题目（selectBatchIds 取回后逐题：不存在或已软删→该题失败；非归属教师且非 ADMIN→该题失败；被进行中考试引用→该题失败），收集逐题 reason；再对合规子集单事务 `@Transactional(rollbackFor=Exception.class)` 逐题逻辑删除（复用单删的 `deleteById` 语义，`is_deleted` 标记）；
   - 失败 reason 文案与单删路径同类语义（不存在/已删、无权、被进行中考试锁定），**具体措辞以现有 QuestionService / ExamPaperLockService 既有文案为准逐字对齐**，不新造口径；
   - 返回 200 + `{ "succeeded": number[], "failed": [{ "id": number, "reason": string }] }`；全部失败时 succeeded 为空数组（不报错）；请求校验失败（空/超限/重复）才是 400；
   - 单题端点 `DELETE /api/questions/{id}` 原样保留，不动。
2. **契约与前端联动**：仓库根 `openapi.yaml` 路 A 离线重导出；`gen:api` 再生成；`onBatchDelete` 改单次批量调用，解析信封后提示「成功 N 题，失败 M 题」（失败项逐条 reason，沿用既有 message 提示模式），成功后刷新列表与选中态；**「部分成功不伪装成全部成功」语义由信封天然承载**。
3. **前端词法护栏**：题库页源码断言不再含串行 `for (const id of` 删除循环、含批量调用与信封解析——变异还原可红。
4. **后端集成测试**：部分成功（混合有效/不存在/被锁/非归属，验证仅有效子集落库软删、failed 逐题 reason 正确）、全部成功、全部失败零删除、请求内重复 400、空/超上限 400；CI surefire 计数必须恰好增加新增用例数（基线 355/0/0/1@6fa6aec）。
5. **既有语义零改动**：单题删除端点、softDelete 内部校验链（owner + 考试锁定 + 逻辑删除）、`schema.sql`、历史试卷/快照对已删题目的引用处理——全部原样。

## Impact

### 受影响的规范
- `spec/specs/question-bank/spec.md` — ADDED：批量删除题目 Requirement（含逐题结果信封与失败语义 Scenario）。

### 受影响的文件
- `src/main/java/com/exam/question/`：`QuestionController`（新端点）、`QuestionService`（`batchDelete`）、新增 `dto/BatchDeleteQuestionsRequest`（或复用所在 dto 包惯例）；
- `src/test/java/com/exam/question/`：新增集成测试；
- 仓库根 `openapi.yaml`；`frontend/src/api/axios/**`（gen:api）、`questions/index.page.vue`、新增前端 spec。

### 需要迁移
- 无（零表变更）。

## 边界与不做
- 不动单题删除端点与 softDelete 既有方法体（批量方法为纯新增）；不做批量校验的查询数优化（一次只改一类）；不加 @RateLimit（单删端点无此注解，同量级低频操作）。

## 验收判据
- 先红后绿（端点未实施时红档 + 实施后定向绿与全量绿）；实施笔仅含 `src/main`、`src/test`、`openapi.yaml`、`frontend/src`；收口笔仅含 `spec/`（`git diff --name-only <实施笔> HEAD -- src frontend pom.xml` 为空）；
- 仓库根 `mvnw.cmd clean test` @ 实施笔：总数恰好 355+新增用例数、Failures/Errors 0、Skipped 恒 1；
- 前端 lint:check / type-check:check / test 退出码 0，vitest 恰增新用例数（基线 45 文件 388 例）；
- `git status` 终态除白名单未跟踪文件外干净。

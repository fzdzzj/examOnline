# 变异验证证据（add-student-question-favorites）

执行环境：分支 `feature/add-student-question-favorites`，立案笔 HEAD `911b333`，实施工作树（实施笔提交前）。原始日志同目录 `m1-mutation-red.log` / `m1-revert-green.log` / `m2-mutation-red.log` / `m2-revert-green.log`，门禁全量日志 `full-gate-backend.log`。

判定测试：`StudentQuestionFavoriteServiceTest`（单元 8 例）+ `StudentQuestionFavoriteIntegrationTest`（集成 4 例），共 12 例；命令 `./mvnw.cmd test -Dtest=StudentQuestionFavorite*`。

## m1 —— 题目有效性校验移除（404 校验短路）

- **注入**：`StudentQuestionFavoriteService.favorite()` 中移除整段题目存在性与软删除校验（`questionMapper.selectById` + `QUESTION_NOT_FOUND` 抛出块）。
- **预期**（proposal §3）：收藏不存在的 questionId 时应 404 但返回 200，断言击红。
- **击红**：`Tests run: 12, Failures: 3, Errors: 2, Skipped: 0`，BUILD FAILURE。关键断言（逐字引自原始日志）：
  - 集成 `favoriteNotFoundAndSoftDeleted_returns404:75` → `java.lang.AssertionError: Response status expected:<404> but was:<200>`
  - 单元 `favorite_questionNotFound_throws404:106` / `favorite_questionSoftDeleted_throws404:119` → `Expected com.exam.common.BusinessException to be thrown, but nothing was thrown.`
  - 另有 2 例 `UnnecessaryStubbing` ERROR（Mockito 严格桩对失效桩的附带报错，同属红）。
- **复原**：从备份字节级还原（`cmp` 一致）后复跑：`Tests run: 12, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS。

## m2 —— 学生数据隔离移除

- **注入**：三处 student_id 过滤同时摘除——`checkFavorites` wrapper 的 `.eq(studentId)`；`selectFavoriteItemsPaged` 与 `countFavoriteItems` 两条 SQL 的 `WHERE f.student_id = #{studentId}`（改为 `WHERE 1=1`，保持 SQL 可执行）。
- **预期**（proposal §3）：跨学生隔离测试确定性变红。
- **击红**：`Tests run: 12, Failures: 1, Errors: 0, Skipped: 0`，BUILD FAILURE。关键断言（逐字引自原始日志）：
  - 集成 `studentDataIsolation_strictSeparation:99` → `org.opentest4j.AssertionFailedError: expected: <1> but was: <2>`——A 生收藏列表 total=1 变 2（看到了 B 生的收藏），列表隔离先于批量检测断言击红。
  - **如实登记**：单元侧 8 例保持绿——`checkFavorites_studentIsolation` 对 mapper 打桩，wrapper 内过滤条件变化不透传 mock 返回值，m2 的确定性击红只能由走真实 SQL 的集成测试给出。该限制与本仓库「列级一致性靠真实链路集成用例暴露」的口径一致。
- **复原**：两文件从备份字节级还原（`cmp` 一致）后复跑：`Tests run: 12, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS。

## 结论

m1/m2 均按 proposal §3 完成注入→击红→复原→回绿闭环，判定测试在两轮变异下均给出确定性红，护栏有效。

## 双端门禁（证据笔实测）

- 后端：仓库根 `./mvnw.cmd clean test` → `Tests run: 714, Failures: 0, Errors: 0, Skipped: 1`，BUILD SUCCESS（基线 702 + 本案 12 = 714；原始日志 `full-gate-backend.log`）。
- 前端：`frontend/` 下 `pnpm test`（vitest run）→ `Test Files 120 passed (120)`、`Tests 917 passed (917)`（基线 908 + 本案 9 = 917；原始日志 `full-gate-frontend.log`）。
- 契约：`grep -cE '^  /' openapi.yaml` = 100（基线 97 + 本案 3，收藏/取消共用 `/{questionId}`）。
- `.github/ci/gate-baseline.json` 同笔上调：backendTests 702→714、frontendTests 908→917、openapiPaths 97→100。

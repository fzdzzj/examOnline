# 变异验证留证（add-question-duplicate-detection）

阶段 2 先红后绿 + 变异击红验证。文字版五步（注入违规 → 击红 → 复原 → 回绿），
输出日志见同目录 `m1-*.log` 与 `m2-*.log`。

## m1 —— 题干空白/短题干校验门禁（controller，`stem.trim().length() < 2`）

- 注入：把 `if (stem == null || stem.trim().length() < 2)` 突变为仅 `if (stem == null)`，
  即空/短题干（空白、单字符）不再被判为 400。
- 击红：`mvnw.cmd test -Dtest=QuestionDuplicateIntegrationTest#blankOrShortStemReturns400`
  → `Tests run: 1, Failures: 1`；断言 `Response status expected:<400> but was:<200>`，
  注入标志即「短题干不再 400」被测试锁定。
  - 日志：`evidence/m1-stem-validation-red.log`
- 复原 + 回绿：恢复原校验后重跑同一测试 → `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`。
  - 日志：`evidence/m1-stem-validation-green.log`

结论：m1 门禁有测试覆盖，缺失即红。

## m2 —— 排除自身 excludeId 逻辑（service，`q.getId().equals(excludeId)`）

- 注入：删除 `checkDuplicate` 循环内的 `if (q.getId().equals(excludeId)) { continue; }`，
  即编辑场景下不排除自身。
- 击红：`mvnw.cmd test -Dtest=QuestionDuplicateIntegrationTest#excludeIdExcludesSelf`
  → `Tests run: 1, Failures: 1`；断言 `excludeId 应排除题目自身 ==> expected: <true> but was: <false>`。
  - 日志：`evidence/m2-excludeId-red.log`
- 复原 + 回绿：恢复排除逻辑后重跑同一测试 → `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`。
  - 日志：`evidence/m2-excludeId-green.log`

结论：m2 排除自身逻辑有测试覆盖，缺失即红。

## 门禁数字链（本阶段实测，收口笔据此上调 gate-baseline）

- 后端全量：`mvnw.cmd clean test` → `Tests run: 534, Failures: 0, Errors: 0, Skipped: 1`
- 前端全量：`pnpm vitest run` → 106 文件 / 806 用例全绿
- openapi paths：90 → 91
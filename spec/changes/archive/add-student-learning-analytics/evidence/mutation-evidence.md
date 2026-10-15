# 变异验证留证 — add-student-learning-analytics 阶段 2

> 判定目标：m1 掌握度/薄弱点过滤逻辑、m2 教师班级归属越权校验，各注入一次必然使其红线用例击红，
> 复原后恢复全绿。每个「红 / 绿」日志见同目录 `m1-mutation-red.log` / `m1-mutation-green.log` /
> `m2-mutation-red.log` / `m2-mutation-green.log`（含该次执行的 Tests run / Failures / BUILD 结果）。

## m1：掌握度 / 薄弱点过滤逻辑

- **注入点**：`StudentLearningAnalyticsService.weakTagsOf` —— 移除 `.filter(t -> Boolean.TRUE.equals(t.getWeak()))`，
  改为对全部知识点升序取 Top 5（薄弱判定被抽掉）。
- **红线用例**：`StudentLearningAnalyticsIntegrationTest.studentAnalytics_masteryAndWeakTags`
  （断言 `weakTags` 只包含掌握度 0 的物理，不含掌握度 100 的数学）。
- **击红**：`Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, BUILD FAILURE`；
  断言 `expected: <1> but was: <2>`（非薄弱数学混入 weakTags）。
- **复原**：恢复 `weak` 过滤后，`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS`。
- 意义：薄弱知识点标定是推荐与画像的入口口径，抽掉过滤会误把高掌握度知识点标为薄弱，测试能抓住。

## m2：教师班级归属越权校验

- **注入点**：`StudentLearningAnalyticsService.getClassAnalytics` —— 把 `classService.getOwnedClass(classId)`
  改为 `classMapper.selectById(classId)`（跳过所有权强校验，直接读班级）。
- **红线用例**：`StudentLearningAnalyticsIntegrationTest.classAnalytics_ownershipGuard`
  （断言非归属教师 B 访问 403）。
- **击红**：`Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, BUILD FAILURE`；
  断言 `Response status expected:<403> but was:<200>`（水平越权读到了他人班级）。
- **复原**：恢复 `getOwnedClass` 强校验后，`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS`。
- 意义：班级学情是教师端跨班数据，越权读他人班级属水平数据泄露，只有服务层所有权校验拦得住；抽掉校验即 200 泄露，测试能抓住。

## 结论

两条红线（薄弱点过滤、班级越权）均实现「注入 → 击红 → 复原 → 回绿」闭环，变异能被既有红线用例杀死，
对应实现不为装饰性判据。复原后全量 `StudentLearningAnalyticsIntegrationTest`（6 例）返回
`Failures: 0, Errors: 0, Skipped: 0`。
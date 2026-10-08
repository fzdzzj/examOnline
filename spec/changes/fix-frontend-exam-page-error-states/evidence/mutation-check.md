# 变异校验记录（fix-frontend-exam-page-error-states）

判据纪律：注入违规 → 确认红 → 撤销 → 确认绿。所有变异在已实施页面上临时施加、验证后复原。

## 变异 ① U-6 撤 error 消费
- 注入：`index.page.vue` 列表错误 Alert `v-if="queryErrorText"` 改为 `v-if="false"`（Alert 永不现，等价于不消费 error）。
- 期望红：`examQueryFailureStates.spec.ts` 列表查询失败 / 搜索筛选失败两用例因 Alert 缺失变红。
- 实测：`Tests 2 failed | 2 passed (4)` —— 两处错误 Alert 断言变红。
- 复原：`v-if="queryErrorText"`；定向跑 4 例全绿。

## 变异 ② U-7 撤保存按钮禁用
- 注入：`create.page.vue` 保存按钮移除 `:disabled="isEditMode && !backfilled"`。提交拦截仍保留。
- 期望红：`examEditBackfillFailure.spec.ts` 回填失败用例因「保存按钮禁用」断言变红。
- 实测：`Tests 1 failed | 2 passed (3)` —— 失败在 :128 `disabled===true` 断言。
- 复原：恢复 `:disabled`；3 例全绿。

## 变异 ③ U-7 撤提交拦截 guard
- 注入：`create.page.vue` `handleSubmit` 移除 `isEditMode && !backfilled` 拦截。
- 增强：测试在回填失败态程序化填出「合法表单」再调 handleSubmit（独立覆盖 guard，防腐默认值误写）。
- 期望红：合法表单下 guard 缺失会放行 → update2 被误调 → 断言 `update2 not.toHaveBeenCalled` 变红。
- 实测：`Tests 1 failed | 2 passed (3)` —— 失败在 :148 `update2 not.toHaveBeenCalled` 断言。
- 复原：恢复 guard；3 例全绿。

## 结论
三处关键断言（错误 Alert 显性呈现、保存按钮禁用、提交拦截)均经变异红→绿验证，非装饰性断言。
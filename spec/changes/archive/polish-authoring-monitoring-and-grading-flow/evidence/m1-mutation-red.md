# m1 变异击红留证 — 题目复制另存必须走 create1，不得走 update

注入：`QuestionForm.vue` `onSubmit` 中新增分支——复制另存（`isCopy`）改为调用 `update()` 而非 `create1()`，
即复制保存误走更新端点（源题会被「静默"更新"」，破坏「源题零影响」红线）。

判定目标（红线用例）：`src/components/question/__tests__/QuestionFormCopy.spec.ts`
- 「复制模式不是编辑：提交走 create1 且绝不调用 update（源题零影响）」
- 「复制保存是「新建」，绝不触碰 update 端点（源题零影响）」

执行：`npx vitest run src/components/question/__tests__/QuestionFormCopy.spec.ts`

击红：

```
❯ src/components/question/__tests__/QuestionFormCopy.spec.ts (4 tests | 2 failed)
    ✓ 复制模式以「复制为新题」标题打开，源题全字段深拷贝回填且题干前缀「（副本）」
    × 复制模式不是编辑：提交走 create1 且绝不调用 update（源题零影响）
    × 复制保存是「新建」，绝不触碰 update 端点（源题零影响）
    ✓ 编辑模式（question prop）回归零破坏：仍走 update 不走 create
Test Files  1 failed (1)
     Tests  2 failed | 2 passed (4)
AssertionError: expected "vi.fn()" to be called 1 times, but got 0 times
```

失败断言 `expected "vi.fn()" to be called 1 times, but got 0 times`：即 `create1` 未被调用 0 次——复制保存被错误路由到 `update`，
`create1` 一次都没收到请求。红线用例抓住「复制误走 update」变异。

复原后回绿（全部 4 例 `Tests: 4 passed`）。
意义：复制提交既有的「新建端点 + 源题零影响」是唯一性状，抽掉即复制等同编辑、直接污染源题，红线用例能抓住。
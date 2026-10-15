# 变异验证留证 — add-batch-tagging-and-score-balancing 阶段 2

> 判定目标：m1 题库批量打标所有权越权校验、m2 试卷配平离散最大余数保和算法，各注入一次
> 必然使其红线用例击红，复原后恢复全绿。每个「红 / 绿」留证见同目录
> `m1-mutation-red.md` / `m1-mutation-green.md` / `m2-mutation-red.md` / `m2-mutation-green.md`。

## m1：题库批量打标所有权越权校验

- **注入点**：`QuestionService.batchTags` —— 移除 `OwnershipGuard.assertOwner(question.getCreatedBy(), operator, "题目")`，
  改为无条件 `allowed.add(id)`（ownership 守卫被抽掉）。
- **红线用例**：`QuestionBatchTagsIntegrationTest.ownershipViolationGoesToFailedWhileAdminIsAllowed`
  （断言教师 A 对教师 B 题目打标时越权题计入 `failed` 且 reason 为「无权操作该题目（资源不属于当前用户）」）。
- **击红**：`Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, BUILD FAILURE`；
  断言 `expected: <无权操作该题目（资源不属于当前用户）> but was: <null>`；响应体
  `{"succeeded":[1,2],"failed":[]}` 证明越权题目被当成合法处理。
- **复原**：恢复 `OwnershipGuard.assertOwner` 后（`mvnw clean` 强编译去除 stale 字节码），
  `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS`。
- 意义：所有权校验是批量打标越权防护的唯一闸门，抽掉即水平越权泄露，红线用例能抓住。

## m2：试卷配平离散最大余数保和算法

- **注入点**：`PaperService.applyLargestRemainder` —— 把 `long r = t % n;` 改为 `long r = 0;`
  （丢弃余数，前 R 题不再 +1 步长，保和破坏）。
- **红线用例**：`PaperRebalanceScoresIntegrationTest.evenModeDistributesByLargestRemainderWithExactSum`
  （3 题目标 10 分，T=20/Q=6/R=2 → 3.5/3.5/3，断言 sum === 10）。
- **击红**：`Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, BUILD FAILURE`；
  断言 `EVEN 保和 sum 应等于目标 10 ==> expected: <true> but was: <false>`。
- **复原**：恢复 `long r = t % n;` 后（`mvnw clean` 强编译），全类
  `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS`。
- 意义：最大余数是保和算法的数学核心，抽掉余数分配即总分不符，红线用例能抓住。

## 结论

两条红线（所有权越权、最大余数保和）均实现「注入 → 击红 → 复原 → 回绿」闭环，
变异能被既有红线用例杀死，对应实现不为装饰性判据。复原后双端后端新增集成测试
（QuestionBatchTagsIntegrationTest 6 例 + PaperRebalanceScoresIntegrationTest 7 例）全部
`Failures: 0, Errors: 0, Skipped: 0`。
# m1 变异击红留证 — 题库批量打标所有权越权校验（QuestionBatchTagsIntegrationTest）

注入：QuestionService.batchTags 中移除 `OwnershipGuard.assertOwner(question.getCreatedBy(), operator, "题目")`（改为无条件 allowed.add）
判定目标：教师 A 试图对教师 B 题目打标 → 越权题必须计入 failed 且 reason 为「无权操作该题目（资源不属于当前用户）」

执行：mvnw -o test -Dtest=QuestionBatchTagsIntegrationTest#ownershipViolationGoesToFailedWhileAdminIsAllowed -Djacoco.skip=true
击红：Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, BUILD FAILURE
断言：expected: <无权操作该题目（资源不属于当前用户）> but was: <null>
响应体：{"data":{"succeeded":[1,2],"failed":[]}} —— 越权题目被当成合法处理（ownership 守卫被抽掉）
意义：所有权校验是批量打标越权防护的唯一闸门，抽掉即水平越权泄露，红线用例能抓住。

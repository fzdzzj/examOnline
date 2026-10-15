# m1 复原回绿 — 题库批量打标所有权越权校验

复原：恢复 OwnershipGuard.assertOwner 后（mvnw clean 强编译去除 stale 字节码）
执行：mvnw -o clean test -Dtest=QuestionBatchTagsIntegrationTest#ownershipViolationGoesToFailedWhileAdminIsAllowed -Djacoco.skip=true
回绿：Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS

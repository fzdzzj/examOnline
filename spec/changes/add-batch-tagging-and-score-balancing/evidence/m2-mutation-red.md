# m2 变异击红留证 — 试卷配平离散最大余数保和算法（PaperRebalanceScoresIntegrationTest）

注入：PaperService.applyLargestRemainder 把 `long r = t % n;` 改为 `long r = 0;`（丢弃余数 → 前 R 题不再 +1 步长）
判定目标：3 题目标 10 分（T=20, Q=6, R=2 → 3.5/3.5/3 保和=10），余数被丢后 sum != target

执行：mvnw -o clean test -Dtest=PaperRebalanceScoresIntegrationTest#evenModeDistributesByLargestRemainderWithExactSum -Djacoco.skip=true
击红：Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, BUILD FAILURE
断言：EVEN 保和 sum 应等于目标 10 ==> expected: <true> but was: <false>
意义：最大余数是保和算法的数学核心，抽掉余数分配即总分不符，红线用例能抓住。

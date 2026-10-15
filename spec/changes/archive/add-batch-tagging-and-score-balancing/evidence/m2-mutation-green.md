# m2 复原回绿 — 试卷配平离散最大余数保和算法

复原：恢复 `long r = t % n;`（mvnw clean 强编译去除 stale 字节码）
执行：mvnw -o clean test -Dtest=PaperRebalanceScoresIntegrationTest -Djacoco.skip=true
回绿：Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS

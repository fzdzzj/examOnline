# evidence/ —— harden-slave-read-failover 证据索引（证据三要素：命令 + 当次输出 + 短 revision）

定向命令均为仓库根 `./mvnw test -Dtest=...`（Git Bash；定向运行不带 clean）；01–05 为定向运行完整日志原样入库，06 为全量门禁尾部判定段裁剪（全量原始日志留存仓库外 `D:\code\examOnline-measure\harden-slave-read-failover\`，变异五步留证同盘 mutation-check/）。

## 01 先红（编译红，测试先行）

- revision：立案笔 2d1dc12 工作树（三服务旧构造 / BusinessMetrics 旧形态 / SlaveReadFallbackExecutor 不存在）
- 命令：`./mvnw test -Dtest=SlaveReadFailoverTest`
- 当次输出（01-tdd-red-old-form.log）：COMPILATION ERROR——`找不到符号`（SlaveReadFailoverTest L65 引用 SlaveReadFallbackExecutor 旧形态不存在）；BUILD FAILURE（EXIT=1）
- 定性：先红=编译红（编译红先例同族）。行为红由 03 变异补证（注释摘除 L62 压栈行 → 断言红，非编译红）。

## 02 转绿（实施后定向全绿）

- revision：实施笔 84f023f
- 命令：`./mvnw test -Dtest=SlaveReadFailoverTest,GradingQueryServiceProjectionTest,GradingProgressAlgorithmTest,SubjectiveGradingServiceSaveScoreReadBackTest,AlertAssetsTest`
- 当次输出（02-targeted-green-impl.log）：`Tests run: 7, Failures: 0`（SlaveReadFailoverTest 新增七用例：failoverPushesMasterAndReturns〔重试期路由栈顶轨迹 null→master + 计数〕/ masterFailurePropagatesHonestly〔主库也失败原样上抛且恰好计数一次〕/ businessExceptionNotCaughtAndNotCounted〔NOT_FOUND 不重试不计数〕/ normalPathNoMetricsNoPush〔正常路径零压栈零计数 + afterEach 栈平衡断言〕/ examSnapshotCurrentFailsOverToMaster〔拉卷主链路：从库失败→主库重试→业务 NOT_FOUND 传播链〕/ paperSnapshotCurrentFailsOverToMaster〔归属校验读失败同链〕/ gradingProgressSucceedsViaMasterAfterSlaveFailure〔主库兜底成功路径返回真实进度 + 栈顶轨迹〕）+ 既有 22 用例零回归（grading 三测试类构造适配 lenient 透传桩，行为零改动）+ `Tests run: 7`（AlertAssetsTest：EXPECTED_ALERTS 含 DatasourceDegraded、known 含 exam_datasource_degraded_total）；合计 36/36 BUILD SUCCESS（EXIT=0）

## 03 变异击红（注释摘除 L62 压栈行 → 确定性断言红）

- 变异：`SlaveReadFallbackExecutor.java` L62 `DynamicDataSourceContextHolder.push(ReadYourWriteRouter.MASTER);` 整行注释为 `// [MUTATION-03] ...`，仅此一行（业务/测试源码零改动）
- 为何换改法：旧记录的变异（catch 内补 `throw e;`）使 L63 起代码全部不可达，javac 报「无法访问的语句」——那是**编译红**，行为红并未被证明（复核 P1）。注释单行后代码仍可编译，红只能来自断言。
- revision：be715cc 工作树（变异未提交）
- 命令：`mvnw.cmd test -Dtest=SlaveReadFailoverTest#failoverPushesMasterAndReturnsRetryValue`
- 当次输出（03-red-mutation-no-failover.log）：`Tests run: 1, Failures: 1, Errors: 0, Skipped: 0`（EXIT=1）；失败点 `SlaveReadFailoverTest.failoverPushesMasterAndReturnsRetryValue:108 重试必须压栈 master（与 ReadYourWriteRouter 同机制） ==> expected: <master> but was: <null>`；BUILD FAILURE
- 等价变异登记（如实，不强杀）：①重试次数从 1 改 2——现有用例断言 calls==2 且 peek 轨迹 [null, master]，第二次重试会让轨迹变长、mock 桩（第二次起返回成功值）使结果相同，peekTrace.get(2) 不存在但断言未覆盖 → 对测试面等价，登记不强杀（区分度需「重试次数上限」专项断言，属后续加固空间非本卡缺陷）；②tag 常量改名（exam_snapshot→exam_current）——单测 mock 按同名字符串断言会同步改，测试面等价，由 BusinessMetrics javadoc 与矩阵文档承载约束。

## 04 复原回绿

- 还原：`git checkout -- src/main/java/com/exam/config/SlaveReadFallbackExecutor.java`，L62 压栈行原样回归（还原后 `git status --porcelain` 仅 evidence/03–04 两文件为变更，源码零残留）
- 命令：同 03
- 当次输出（04-green-restored.log）：`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS（EXIT=0）
- 复核 P1 更正登记：本笔之前的 03 是编译红（非断言红），04 是 `Tests run: 0` + BUILD SUCCESS——旧方法名写作 `failoverPushesMasterAndReturns`（漏 `RetryValue`），surefire 未匹配到任何用例，「回绿」不构成证据。两文件已由上述两次真实运行整份覆盖。

## 05 范围外零改动核对（收口 diff，对照 main）

- 命令：`git diff main --stat -- src/main/java/com/exam/config/ReadYourWriteRouter.java src/main/java/com/exam/config/ReadYourWriteMark.java src/main/java/com/exam/config/DynamicDataSourceConfig.java src/main/java/com/exam/taking/service/ src/main/java/com/exam/submission/service/ src/main/java/com/exam/score/service/ src/main/resources/schema.sql docker/mysql/ openapi.yaml pom.xml frontend/`
- 当次输出（05-out-of-scope-diff.txt）：空（0 字节，OUT_OF_SCOPE_DIFF_LINES=0）——读写分离既有设施（Router/Mark/DynamicDataSourceConfig）、写路径与无 @DS 读点服务 + schema.sql、migrations、openapi.yaml、pom、frontend 全零触碰。
- 分支总 diff：恰 15 文件（三件套 3 + spec/README.md 在途表 1 + 实施 11），全在 proposal 白名单内。

## 06 全量门禁

- revision：84f023f（工作树=实施笔干净态）
- 命令：仓库根 `./mvnw clean test`（Git Bash；带 clean、未用 -o）
- 当次输出（06-full-gate-summary.log）：`Tests run: 674, Failures: 0, Errors: 0, Skipped: 1`，BUILD SUCCESS，GATE_EXIT=0（Skipped 1 为既有 OpenApiContractTest 基线跳过；674 = 667 基线 + 本案新增 7）

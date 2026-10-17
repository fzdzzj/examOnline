# evidence/ —— harden-token-store-redis-degradation 证据索引（证据三要素：命令 + 当次输出 + 短 revision）

定向命令均为仓库根 `./mvnw test -Dtest=...`（Git Bash；定向运行不带 clean——clean 只影响残留报告拼数，定向 -Dtest 已限定类集）；01–05 为定向运行完整日志原样入库，06 为全量门禁尾部判定段裁剪（全量原始日志留存仓库外 `D:\code\examOnline-measure\harden-token-store-redis-degradation\`，变异五步留证同盘 mutation-check/）。

## 01 先红（编译红，测试先行）

- revision：立案笔 d0ba916 工作树（AuthService 仍为旧形态、旧 9 参构造）
- 命令：`./mvnw test -Dtest=AuthServiceTest`
- 当次输出（01-tdd-red-old-form.log）：COMPILATION ERROR——新测试以 10 参构造（含 BusinessMetrics）调用旧 9 参构造 `无法将类 AuthService中的构造器应用到给定类型`；BUILD FAILURE（EXIT=1）
- 定性：先红=编译红（降级断言依赖的构造形态在旧形态不存在；harden-auth-edge-cases / harden-submit-lock 编译红先例）。行为红由 03 变异以同形补证。

## 02 转绿（实施后定向全绿）

- revision：实施笔 a165e99（工作树仅含不影响测试的 runbook 文档编辑）
- 命令：`./mvnw test -Dtest=AuthServiceTest,AlertAssetsTest`
- 当次输出（02-targeted-green-impl.log）：`Tests run: 11, Failures: 0, Errors: 0`（AuthServiceTest：既有 4 用例零改动零回归 + 新增 7——loginStillIssuesAccessWhenSessionVersionReadFails〔版本读失败仍发 access + refresh 照常 + 计数 issue_session_version + ERROR 日志「令牌签发降级」〕/ loginOmitsRefreshWhenStoreRefreshFails〔存储失败不返回 refresh、refreshExpiresIn=0、计数 issue_refresh_store〕/ loginHappyPathCountsNoDegradation〔正常路径零降级计数〕/ refreshReturnsAccessOnlyWhenRotationFails〔轮换失败降级仅发 access、不返回新 refresh、计数 refresh_rotate + ERROR 日志〕/ refreshReuseStillRejectsWhenCleanupFails〔REUSED 拒绝语义不变、清理降级计数 invalidate_sessions×2〕/ logoutCompletesWhenBlacklistWriteFails〔黑名单写失败登出照常 + 计数 logout_blacklist〕/ changePasswordCompletesWhenSessionInvalidationFails〔全端下线写失败改密照常成功、计数 invalidate_sessions〕）+ `Tests run: 7, Failures: 0`（AlertAssetsTest 回归）；合计 18/18 BUILD SUCCESS（EXIT=0）

## 03 变异击红（还原「Redis 异常即上抛」旧形态）

- 变异：`scripts/mutation-check.sh --inject-sed 's/return 0L;/throw new RuntimeException(e);/'`——issueSessionVersionSafe 降级分支改上抛（旧形态「Redis 异常即失败」；return 0L; 全文件唯一命中，注入标志在场由工具核验）
- revision：a165e99 + 工作树变异（工具内自动备份/复原）
- 命令：`./mvnw test -Dtest=AuthServiceTest#loginStillIssuesAccessWhenSessionVersionReadFails`
- 当次输出（03-red-mutation-issue-rethrow.log）：测试确定性失败（RuntimeException 从 login 裸穿而非降级放行），BUILD FAILURE（EXIT=1）
- 等价变异登记（如实，不强杀）：签发降级把 sv 嵌入值从 0 改为任意常量——TokenResponse 不暴露 sessionVersion（在 JWT claims 内且 jwtUtil 为 mock 不产生真实 claims），现有单测面无法区分，属测试面等价变异；该维度由拦截器 sv 比对集成链路（AuthenticationInterceptor 既有降级测试族）承载，不在此强杀。其余五触点与本变异同构同形（同一 helper 家族、同一「放行+日志+计数」断言形态），m1 代表族形态。

## 04 复原回绿

- 还原：mutation-check 第④步 cmp 字节级复原 + 注入标志归零（工具自动）
- 命令：同 03（第⑤步重跑）
- 当次输出（04-green-restored.log）：BUILD SUCCESS（EXIT=0）

## 05 范围外零改动核对（收口 diff，对照 main）

- 命令：`git diff main --stat -- src/main/java/com/exam/auth/service/TokenStoreService.java src/main/java/com/exam/auth/security/AuthenticationInterceptor.java src/main/java/com/exam/auth/service/LoginGuardService.java src/main/java/com/exam/auth/service/MailService.java src/main/java/com/exam/auth/service/JwtUtil.java src/main/java/com/exam/taking/service/ExamDraftService.java src/main/resources/schema.sql docker/mysql/ openapi.yaml pom.xml frontend/ docker/observability/`
- 当次输出（05-out-of-scope-diff.txt）：空（0 字节，OUT_OF_SCOPE_DIFF_LINES=0）——原语层 TokenStoreService、拦截器/登录守卫先例本体、MailService/JwtUtil、草稿硬依赖面（ExamDraftService）+ schema.sql、migrations、openapi.yaml、pom、frontend、docker/observability（零新告警资产）全零触碰。auth:reset: 调用路径（storeResetCode/consumeResetCode）在 AuthService diff 中零命中（diff 增行 grep 计数=0）。
- 分支总 diff：恰 7 文件（三件套 3 + spec/README.md 在途表 1 + 实施 3），全在 proposal 白名单内。

## 06 全量门禁

- revision：a165e99（工作树=实施笔 + runbook 文档编辑）
- 命令：仓库根 `./mvnw clean test`（Git Bash；带 clean、未用 -o）
- 当次输出（06-full-gate-summary.log）：`Tests run: 658, Failures: 0, Errors: 0, Skipped: 1`，BUILD SUCCESS，GATE_EXIT=0（Skipped 1 为既有 OpenApiContractTest 基线跳过；658 = 651 基线 + 本案新增 7）

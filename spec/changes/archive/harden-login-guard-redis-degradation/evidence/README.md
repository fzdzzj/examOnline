# evidence/ —— harden-login-guard-redis-degradation 证据索引（证据三要素：命令 + 当次输出 + 短 revision）

定向命令均为仓库根 `./mvnw test -Dtest=LoginGuardServiceTest`（Git Bash；定向运行不带 clean——clean 只影响残留报告拼数，定向 -Dtest 已限定类集）；01–05 为定向运行完整日志原样入库，06 为全量门禁判定行裁剪（全量原始日志留存仓库外 `D:\code\examOnline-measure\harden-login-guard-redis-degradation\`）。

## 01 先红（编译红，测试先行）

- revision：立案笔 6931dd2 工作树（LoginGuardService 仍为旧形态）
- 命令：`./mvnw test -Dtest=LoginGuardServiceTest`
- 当次输出（01-tdd-red-old-form.log）：COMPILATION ERROR——`无法将构造器 LoginGuardService应用到给定类型`（需要 3 参，找到 4 参：新增 BusinessMetrics）+ `找不到符号: 变量 DEGRADED_LOGIN_RATE_LIMIT / DEGRADED_LOGIN_LOCK_CHECK / DEGRADED_LOGIN_FAIL_RECORD / DEGRADED_LOGIN_LOCK_WRITE / DEGRADED_LOGIN_FAIL_CLEAR / DEGRADED_CODE_RATE_LIMIT` ×6；BUILD FAILURE（EXIT=1）
- 定性：先红=编译红（容错断言引用的构造与 tag 常量在旧形态不存在；harden-auth-edge-cases「编译红 14 处」先例）。行为红由 03 变异以同形补证（摘除容错 → 六降级用例确定性失败），与 fix-mail-smtp-timeout-and-catch「先红 → 转绿 → 变异与先红同形」结构一致。

## 02 转绿（实施后定向全绿）

- revision：实施笔 22e3aa0 工作树态
- 命令：`./mvnw test -Dtest=LoginGuardServiceTest`
- 当次输出（02-targeted-green-impl.log）：`Tests run: 16, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS（EXIT=0）——16 = 既有 8（Lua 原子化三断言/真实 IP 透传/阈值锁定/验证码限流两用例）+ 新增 8（六降级用例：放行+ERROR 日志含「登录守卫降级」+ countAuthDegraded 对应 tag 三断言；checkLocked 回归两用例：锁定命中仍 ACCOUNT_LOCKED、无锁仍放行且计数器零调用）

## 03 变异击红（还原无容错旧形态）

- 变异：LoginGuardService 五触点 try-catch 全部摘除、Redis 调用直接上抛（旧形态；保留 4 参构造与六 tag 常量使测试可编译，逐处行尾 [MUTATION] 注释标记，跑完即 `git checkout HEAD --` 还原）
- revision：22e3aa0 + 工作树变异（未提交）
- 命令：`./mvnw test -Dtest=LoginGuardServiceTest`
- 当次输出（03-red-mutation-no-tolerance.log）：`Tests run: 16, Failures: 6, Errors: 0`——失败恰为六个降级用例（checkRateLimitRedisError / checkLockedRedisError / recordFailureRedisError / recordFailureLockWriteRedisError / clearFailuresRedisError / checkCodeRateLimitRedisError，异常上抛而非放行/不抛，确定性失败）；既有 8 用例与 checkLocked 回归 2 用例不受影响；BUILD FAILURE（EXIT=1）
- 等价变异登记：无。摘除 try-catch 是行为可观测差异（异常上抛 vs 降级放行）；降级三断言中 ERROR 日志与计数各自独立断言（摘其一即红），不存在行为等价的免杀形态。

## 04 复原回绿

- 还原：`git checkout HEAD -- src/main/java/com/exam/auth/service/LoginGuardService.java`（还原到实施笔 22e3aa0；变异在实施笔入库后进行，无 checkout 丢修复二踩）
- 命令：`./mvnw test -Dtest=LoginGuardServiceTest`
- 当次输出（04-green-restored.log）：`Tests run: 16, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS（EXIT=0）

## 05 范围外零改动核对（收口 diff，对照 main）

- 命令：`git diff main --stat -- src/main/java/com/exam/taking/service/ExamSubmitService.java src/main/java/com/exam/config/CacheConfig.java src/main/java/com/exam/taking/service/ExamDraftService.java src/main/java/com/exam/common/cache/ src/main/java/com/exam/auth/security/AuthenticationInterceptor.java src/main/java/com/exam/auth/service/AuthService.java src/main/resources/schema.sql docker/mysql/ openapi.yaml pom.xml frontend/ src/main/resources/db`
- 当次输出（05-out-of-scope-diff.txt）：空（OUT_OF_SCOPE_DIFF_LINES=0）——交卷 SETNX 锁（ExamSubmitService，D1 卡另案）、RedisCacheManager（CacheConfig）、草稿 Redis 存储（ExamDraftService，裁决为硬依赖面）、RedisLockHelper/CacheMutexLoader、拦截器先例本体（AuthenticationInterceptor）、调用链（AuthService）+ schema.sql、migrations、openapi.yaml、pom、frontend、db/migration 全零触碰。
- 分支总 diff：恰 7 文件（三件套 3 + spec/README.md 在途表 1 + 实施 3），全在 proposal 白名单内。

## 06 全量门禁

- revision：22e3aa0（工作树=实施笔干净态）
- 命令：仓库根 `mvnw.cmd clean test`（cmd 调用，Git Bash 下 `./mvnw clean test` 语义相同；带 clean、未用 -o）
- 当次输出（06-full-gate-a1-summary.log）：`Tests run: 649, Failures: 0, Errors: 0, Skipped: 1`，BUILD SUCCESS，GATE_EXIT=0（Skipped 1 为既有 OpenApiContractTest 基线跳过；649 = 收口前基线 641 + 本案新增 8）

## 过程偏差登记（如实）

- 立案笔 6931dd2 的 tasks.json 五阶段 status 直接预置 `completed`，未走「全 false 待审批」中间态——本案系派工明示的一次性实施（用户裁决已下、派工即审批），预置回填在实施推进后与事实一致；该偏差如实登记，不改写已成立案笔（未 push、无第三方观察面）。
- 全量门禁执行方式为 Git Bash 下 `cmd //c "mvnw.cmd clean test"`，与判据命令 `mvnw.cmd clean test` 同一 wrapper 同一语义（带 clean、禁 -o 均满足）。

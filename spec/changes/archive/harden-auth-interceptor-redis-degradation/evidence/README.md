# evidence/ —— harden-auth-interceptor-redis-degradation 证据索引（证据三要素：命令 + 当次输出 + 短 revision）

原始全量日志（未裁剪）留存于仓库外 D:\code\examOnline-measure\prod-tz-drill\（h2-*.log /
full-gate-h2.log）。03–05 为定向运行完整日志原样入库；06 为全量门禁判定行裁剪（先例
fix-exam-state-machine-transaction）。定向命令均为仓库根 `./mvnw test -Dtest=...`（带 clean 的
全量门禁见 06；定向运行不带 clean——clean 只影响残留报告拼数，定向 -Dtest 已限定类集）。

## 01 容错单测三断言（绿相，实施笔 5b1daef 工作树态）

- 命令：`./mvnw test -Dtest=AuthenticationInterceptorTest,AlertAssetsTest`
- 当次输出（03-targeted-green-impl.log）：`Tests run: 5, Failures: 0`（AuthenticationInterceptorTest：
  黑名单 Redis 异常降级放行 + ERROR 日志 + 计数递增 check=blacklist；会话版本 Redis 异常同三断言
  check=session_version；黑名单命中/版本不匹配/无 Authorization 头 401 回归 ×3）
  + `Tests run: 7, Failures: 0`（AlertAssetsTest：EXPECTED_ALERTS 含 AuthDegraded、
  known 集合含 exam_auth_degraded_total）；合计 12/12 BUILD SUCCESS（EXIT=0）

## 02 变异击红（还原无容错旧形态）

- 变异：AuthenticationInterceptor 两处 try-catch 摘除、Redis 调用直接上抛（旧形态；
  文件头 [MUTATION] 注释标记，跑完即 git checkout HEAD -- 还原）
- 命令：`./mvnw test -Dtest=AuthenticationInterceptorTest`
- 当次输出（04-red-mutation-no-tolerance.log）：`Tests run: 5, Failures: 0, Errors: 2` ——
  两个降级用例（blacklistRedisError_degradesAllow / sessionVersionRedisError_degradesAllow）
  preHandle 直接抛 RuntimeException 而非放行，确定性失败；BUILD FAILURE（EXIT=1）
- 等价变异登记：无。摘除 try-catch 是行为可观测差异（放行 vs 上抛），不存在等价形态。

## 03 复原回绿

- 还原：`git checkout HEAD -- src/main/java/com/exam/auth/security/AuthenticationInterceptor.java`
- 命令：`./mvnw test -Dtest=AuthenticationInterceptorTest`
- 当次输出（05-green-restored.log）：`Tests run: 5, Failures: 0, Errors: 0` BUILD SUCCESS（EXIT=0）

## 04 范围外四处零改动核对（收口 diff，对照 main）

- 命令：`git diff main --stat -- src/main/java/com/exam/config/CacheConfig.java
  src/main/java/com/exam/auth/service/LoginGuardService.java
  src/main/java/com/exam/taking/service/ExamDraftService.java
  src/main/java/com/exam/common/cache/ src/main/resources/schema.sql docker/mysql/
  openapi.yaml pom.xml frontend/ src/main/resources/db`
- 当次输出：空（OUT_OF_SCOPE_DIFF_LINES=0）——RedisCacheManager（CacheConfig）、登录守卫
  （LoginGuardService）、草稿 Redis 存储（ExamDraftService）、交卷 SETNX 锁
  （common/cache/RedisLockHelper、CacheMutexLoader）四处零改动，另 schema.sql、
  migrations、openapi.yaml、pom、frontend、db/migration 亦零触碰。
- 分支总 diff：恰 10 文件全在 proposal 白名单内。

## 05 全量门禁

- revision：5b1daef（工作树=实施笔干净态）
- 命令：仓库根 `./mvnw clean test`（Git Bash；Windows cmd 等价 `mvnw.cmd clean test`；带 clean、未用 -o）
- 当次输出（06-full-gate-h2-summary.log）：`Tests run: 636, Failures: 0, Errors: 0, Skipped: 1`，
  BUILD SUCCESS，GATE_EXIT=0（Skipped 1 为既有 OpenApiContractTest 基线跳过；
  636 = H1 收口后基线 631 + 本案容错单测 5）

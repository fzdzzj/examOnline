# harden-login-guard-redis-degradation

## 一、背景与问题（B.2 横切首扫 H2 残留面 · Redis 依赖裁决 A1 卡，2026-10-17 立案）

**裁决背景（用户已拍板，2026-10-17）**：Redis 故障口径统一为「**正确性由 DB 幂等保证，Redis 降级保可用性，草稿为已声明的硬依赖面**」。现状已收敛两侧：限流 fail-open（`RateLimitInterceptor` / `exam.ratelimit.degraded`）、鉴权拦截器降级放行（`AuthenticationInterceptor` / `exam.auth.degraded` + `AuthDegraded` 告警，harden-auth-interceptor-redis-degradation）。本卡为该裁决的第一张实施卡（A1）。

`LoginGuardService` 承载登录路径的全部 Redis 交互，对 Redis 异常零容错：

- 登录限流 `checkRateLimit`（incrementWithTtl 计数点）：Redis 异常裸穿 → 登录 500；
- 锁定检查 `checkLocked`（getExpire）：同上；
- 失败计数与锁定写入 `recordFailure`（incrementWithTtl + set + delete）：凭据错误路径 500；
- 成功后清计数 `clearFailures`（delete）：**凭据校验已通过的有效登录在最后一步 500**；
- 找回密码验证码限流 `checkCodeRateLimit`（同 incrementWithTtl 计数点）：找回路径 500。

Redis 挂 = **登录全停摆**（含与 Redis 无关的 DB 凭据校验），与限流/拦截器两侧已落定的降级哲学不一致。该类五个方法即 H2 卡在案时登记的禁触点「登录守卫容错」，此前为「Redis 依赖与草稿兜底」裁决页辖域，裁决已下，本卡实施。

## 二、方案（与 AuthenticationInterceptor 拦截器侧先例逐条同构，不新增告警资产）

- 五个 Redis 触点各自包 try-catch（**try 只包 Redis 调用本身，业务判定与正常拒绝/锁定的 throw 在 try 外**——照抄 `RateLimitInterceptor` / `AuthenticationInterceptor` 收紧纪律）：异常 → 降级放行（或跳过该步 Redis 操作）+ ERROR 日志（含 username/异常/requestId，格式对齐拦截器先例）+ 降级计数；
- **复用 `exam.auth.degraded` 指标**（`BusinessMetrics.countAuthDegraded`，tag `check` 追加六个登录守卫侧代码常量：`login_rate_limit` / `login_lock_check` / `login_fail_record` / `login_lock_write` / `login_fail_clear` / `code_rate_limit`，基数代码常量有界、无客户端可控输入），**不新增指标、不新增告警资产**——`AuthDegraded` 告警盯 `exam_auth_degraded_total` 全 tag 增量，登录守卫侧降级自动纳入既有告警；`countAuthDegraded` javadoc 与 Counter description 同笔勘正（原表述限定"拦截器"两常量）；
- **计数与锁定语义零变化**：计数原子化脚本（incrementWithTtl）、阈值判定、锁定写入/审计透传、429/锁定拒绝路径全部逐字不动；recordFailure 的锁定审计（DB 侧）保持在 Redis 容错面之外；
- **JWT/凭据校验不降级**：BCrypt 凭据校验（DB）与 Token 签发不在容错面——它们是登录正确性的底座（口径同拦截器侧「JWT 校验不降级」）；降级的只是 Redis 支撑的保护性功能（限流/锁定/计数）；
- **降级半径论证（写进 javadoc）**：凭据校验（DB）才是登录正确性底座，限流/锁定/失败计数只是暴力破解的**减速器**。Redis 故障期间这些减速器暂缺——半径=暴力破解防护暂时弱化，且限流侧本就 fail-open（Redis 故障时该防护无论如何不生效），可观测性由 `AuthDegraded` 告警兜住；失败计数清理（clearFailures）降级无正确性影响（计数键按既有 TTL lockMinutes 自然过期自愈）；替代口径（登录全停摆 500）严格更糟。

**范围口径偏差如实登记**：派工原文「登录路径锁定检查（含 incrementWithTtl 计数点）」。本卡按「登录路径全部 Redis 触点 + 同类同脚本计数点」实施——`clearFailures`（redis.delete，登录成功路径最后一步）不包则有效凭据登录仍在 Redis 故障期 500，与降级保可用性的裁决目标直接冲突，故必含；`checkCodeRateLimit`（找回密码路径）与登录限流同一 incrementWithTtl 脚本、同一降级族，不含则同类触点半数降级半数裸穿且 runbook 矩阵（限流 fail-open）失真，故顺带同卡覆盖。二者均在 `LoginGuardService` 单类之内，不属范围外组件。

## 三、白名单

- `src/main/java/com/exam/auth/service/LoginGuardService.java`（五触点容错 + 注入 BusinessMetrics）
- `src/main/java/com/exam/monitoring/metrics/BusinessMetrics.java`（countAuthDegraded javadoc/description 勘正，无新指标）
- `src/test/java/com/exam/auth/service/LoginGuardServiceTest.java`（容错单测 + 构造适配）
- `.github/ci/gate-baseline.json`、`spec/changes/harden-login-guard-redis-degradation/**`、
  `spec/README.md`、`docs/功能待办清单.md`
- **禁触（范围外，本卡不动）**：`src/main/java/com/exam/taking/service/ExamSubmitService.java`（交卷 SETNX 锁，D1 卡另案）、
  `src/main/java/com/exam/config/CacheConfig.java`（RedisCacheManager）、
  `src/main/java/com/exam/taking/service/ExamDraftService.java`（草稿 Redis 存储——裁决为硬依赖面，无兜底）、
  `src/main/java/com/exam/common/cache/RedisLockHelper.java`、`CacheMutexLoader.java`、
  `src/main/java/com/exam/auth/security/AuthenticationInterceptor.java`（先例本体零改动）、
  `src/main/java/com/exam/auth/service/AuthService.java`（调用链零改动——容错收在 LoginGuardService 内）；另禁触 schema.sql、docker/mysql/migrations/、openapi.yaml、pom.xml、frontend/、MQ 发布路径。

## 四、验收要点

- 容错单测三断言（对齐拦截器先例形态）：Redis 异常时放行/不抛 + ERROR 日志落（ListAppender）+ 降级计数递增（`verify countAuthDegraded(tag)`）——checkRateLimit / checkLocked / recordFailure（计数臂与锁定写入臂）/ clearFailures / checkCodeRateLimit 六个降级用例；回归用例：锁定命中仍拒绝（ACCOUNT_LOCKED）、无锁仍放行（降级不得吞掉正常拒绝/放行路径）
- 变异击红：还原无容错旧形态（Redis 异常上抛）→ 降级用例确定性失败 → 复原回绿，留证；等价变异如实登记不强杀
- 范围外零改动核对（ExamSubmitService / CacheConfig / ExamDraftService / RedisLockHelper / CacheMutexLoader / AuthenticationInterceptor / AuthService + schema.sql + docker/mysql + openapi.yaml + pom + frontend）
- 门禁 `mvnw.cmd clean test` 全绿（仓库根、带 clean、禁 -o）；backendTests 基线在 641 上只增不减，收口笔同笔上调
- S 级理由：单类五触点同型容错 + 指标方法复用（零新指标零新告警）、无契约变化、无设计权衡（口径与半径论证均沿用拦截器先例与已下裁决）

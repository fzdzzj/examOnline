# harden-auth-interceptor-redis-degradation

## 一、背景与问题（B.2 横切首扫 H2-S 卡，2026-10-17 立案）

`AuthenticationInterceptor`（所有受保护接口入口）的黑名单吊销检查（`tokenStore.isBlacklisted`）
与会话版本检查（`tokenStore.getSessionVersion`）对 Redis 异常零容错——异常从 `preHandle`
裸穿即 500，Redis 挂 = **全 API 停摆**（含与 Redis 无关的业务）。同仓库已有 fail-open 降级
先例（限流 `RateLimitInterceptor` / `exam.ratelimit.degraded`：Redis 故障放行 + ERROR 日志 +
降级计数），鉴权拦截器与该降级哲学不一致。

## 二、方案（口径对齐限流 fail-open 先例，不新增第二套降级哲学）

- 两处 Redis 调用各自包 try-catch（try 只包调用本身，业务判定在 try 外——照抄
  `RateLimitInterceptor` 的收紧纪律）：异常 → 降级放行 + ERROR 日志（含 userId/jti/异常/
  requestId，格式对齐先例）+ 降级计数；
- 新指标 `exam.auth.degraded`（Counter，tag `check` ∈ {blacklist, session_version}，基数
  代码常量有界），`BusinessMetrics.countAuthDegraded` 落点与 `countRateLimitDegraded` 同构；
  `AlertAssetsTest` 同笔守护登记（known 集合显式断言 + EXPECTED_ALERTS 集合）；
- 告警联动照抄限流先例（RateLimitDegraded 有告警规则）：新增 `AuthDegraded` 规则
  （`sum(increase(exam_auth_degraded_total[5m])) > 0`，for 1m，severity warning）；
- 降级半径论证（写进拦截器 javadoc）：黑名单项 TTL = Access Token 剩余寿命
  （≤30min，access-expire-minutes:30）自限，Redis 故障期间已吊销 token 的复活半径 ≤ 其自然
  剩余寿命，与在案观察项「Redis 清空后已吊销 Access ≤30min 复活」同半径 by design（黑名单
  本就是 TTL 自限设计）；会话版本检查失败 = 改密/踢人的全端下线暂不生效，半径同性质同上界；
  替代口径（全 API 500 停摆）更糟。JWT 签名校验（鉴权正确性底座）不降级——校验失败仍 401；
- 不动：WebMvcConfig 注册顺序、公开端点豁免清单、401 响应格式。

## 三、白名单

- `src/main/java/com/exam/auth/security/AuthenticationInterceptor.java`（两处容错）
- `src/main/java/com/exam/auth/security/WebMvcConfig.java`（构造注入 BusinessMetrics）
- `src/main/java/com/exam/monitoring/metrics/BusinessMetrics.java`（countAuthDegraded）
- `docker/observability/prometheus/rules/exam-online-alerts.yml`（AuthDegraded 规则）
- `src/test/java/com/exam/auth/security/**`（容错单测）、
  `src/test/java/com/exam/observability/AlertAssetsTest.java`（守护登记）
- `.github/ci/gate-baseline.json`、`spec/changes/harden-auth-interceptor-redis-degradation/**`、
  `spec/README.md`、`docs/功能待办清单.md`
- **禁触（范围外四处，「Redis 依赖与草稿兜底」裁决页后置）**：
  `src/main/java/com/exam/config/CacheConfig.java`（RedisCacheManager）、
  `src/main/java/com/exam/auth/service/LoginGuardService.java`（登录守卫容错）、
  `src/main/java/com/exam/taking/service/ExamDraftService.java`（草稿 Redis 存储 DB 兜底）、
  `src/main/java/com/exam/common/cache/RedisLockHelper.java` 与 `CacheMutexLoader.java`
  （交卷 SETNX 锁）；另禁触 schema.sql、docker/mysql/migrations/、openapi.yaml、pom.xml、
  frontend/、MQ 发布路径。

## 四、验收要点

- 容错单测三断言：Redis 异常时请求放行、ERROR 日志落、降级计数递增（黑名单/会话版本各一）
  + 正常拒绝路径回归（黑名单命中 401、版本不匹配 401、无 Token 401）
- 变异击红：还原无容错旧形态（异常上抛）→ 单测确定性失败 → 复原回绿，留证；
  等价变异如实登记不强杀
- 范围外四处收口 diff 零改动核对
- 门禁 `mvnw.cmd clean test` 全绿（仓库根、带 clean、禁 -o）；backendTests 基线在 H1 收口后的
  631 上只增不减，收口笔同笔上调
- S 级理由：两处同型容错 + 一个指标方法 + 告警规则照抄先例、无契约变化、无设计权衡
  （口径与半径论证均沿用限流先例与在案观察项）

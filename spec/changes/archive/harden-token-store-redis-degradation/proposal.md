# harden-token-store-redis-degradation

## 一、背景与问题（B.2 横切首扫 H2 残留面 · Redis 依赖裁决 1① 卡，2026-10-17 立案）

**裁决背景（用户拍板，2026-10-17）**：Redis 故障口径统一为「正确性由 DB 幂等保证，Redis 降级保可用性，草稿为已声明的硬依赖面」。拦截器侧（harden-auth-interceptor-redis-degradation）、登录守卫侧（A1）、交卷锁侧（D1）已按该口径闭环并沉淀 canonical runbook（`docs/redis-dependency-runbook.md`）。

runbook 矩阵如实登记的残留观察面之一：**令牌签发与续期面（`auth:refresh:` / `auth:sv:` 签发侧）零容错**——`TokenStoreService` 是纯 Redis 原语层（get/set/incr/Lua），全部容错决策由调用方做出；而签发续期面的调用方 `AuthService` 对 Redis 异常零捕获：

- **登录签发**（`issueTokens`）：`getSessionVersion`（读当前会话版本嵌入新 token）与 `storeRefresh`（登记 Refresh 会话）任一失败 → 异常裸穿 500——**凭据校验已通过的新登录失败**（Redis 故障期全部用户无法登录）；
- **续期轮换**（`refresh`）：`rotateRefresh`（Lua 原子轮换 + 复用检测）失败 → 500——存量客户端 access 到点后被登出且无法续期；
- **登出**（`logout`）：`blacklistAccessToken` + `removeRefresh` 失败 → 500——用户无法登出；
- **改密/踢人/重置密码**（`invalidateAllSessions`）：`bumpSessionVersion` + `removeRefresh` 失败 → 500——密码已改（DB）但接口报错（且 changePassword 事务回滚致改密本身失败）。

拦截器侧已降级放行（请求时校验），签发续期面是与降级哲学不一致的最后一处认证大半径面（runbook 矩阵「硬依赖」行如实声明，消除需单独立案——本卡即该立案）。

## 二、方案（逐触点定降级口径，复用 countAuthDegraded 零新指标）

容错落点在 **`AuthService` 调用侧**（try 只包 Redis 调用本身），不包在 `TokenStoreService` 内：TokenStoreService 是无业务语义的原语层，降级决策（放行/跳过/部分成功响应）依赖调用语义；且拦截器对同一 `isBlacklisted`/`getSessionVersion` 原语已有自己的降级口径——若在原语层吞异常会令拦截器降级分支永不可达、降级静默（无日志无计数）。与 A1/拦截器先例同构：**降级放行（或跳过该步）+ ERROR 日志（含 requestId）+ 降级计数（复用 `exam.auth.degraded`，tag `check` 追加签发续期侧六个代码常量）**，零新指标零新告警资产（AuthDegraded 盯全 tag 增量自动覆盖）。

| 触点 | Redis 调用 | Redis 异常降级口径 | tag `check` | 半径论证 |
|---|---|---|---|---|
| 登录签发 | `getSessionVersion` | 以 0 嵌入新 token，签发继续 | `issue_session_version` | 会话版本检查在拦截器侧已降级放行；若该用户真实版本 >0（曾改密/被踢），恢复后新 token 的 sv=0 低于真实版本被拦截器拒绝 → 重新登录即可（恢复后登录读取真实版本），自愈 |
| 登录签发 | `storeRefresh` | 本次不返回 refresh（`refreshToken=null`，Jackson 全局 non_null 下字段缺省） | `issue_refresh_store` | access 仍签发，用户可正常工作一个 access 周期（≤30min）；静默续期暂缺，access 到点后重新登录（恢复后成功），自愈 |
| 续期轮换 | `rotateRefresh` | 跳过轮换与复用检测，凭签名+DB 账号状态校验签发 access-only（不返回新 refresh，客户端保留旧 refresh） | `refresh_rotate` | 轮换/复用检测是「会话唯一性的加速器」，签名校验才是底座——与拦截器黑名单降级同构：已轮换/已吊销 refresh 在故障期可换 access（吊销延迟裁决），半径受 refresh TTL（7d）与故障期时长自限；恢复后客户端首次刷新即按存量 jti 正常裁决（当前会话正常轮换/陈旧会话 REUSED 全端下线） |
| 续期 OK 分支 | `getSessionVersion` | 同登录签发（sv=0） | `issue_session_version` | 同上 |
| 刷新账号失效清理 | `removeRefresh`（user 为空/禁用分支） | 跳过清理，拒绝语义不变 | `invalidate_sessions` | 清理只是回收，拒绝本身由 DB 状态保证 |
| 复用检测后清理 | `bumpSessionVersion` + `removeRefresh` | 跳过清理，TOKEN_INVALID 拒绝不变 | `invalidate_sessions` | 复用判定已做出（拒绝不变），清理写入失败恢复后重试自愈 |
| 登出 | `blacklistAccessToken` | 跳过黑名单写，登出照常返回成功 | `logout_blacklist` | 吊销延迟生效：旧 access 至多再活剩余寿命（≤30min），与拦截器降级半径同源自限 |
| 登出 | `removeRefresh` | 跳过会话删除，登出照常返回成功 | `logout_refresh_remove` | 旧 refresh 至 refresh TTL（7d）自然过期自愈；恢复后可再次登出显式清理 |
| 改密（`changePassword`） | `blacklistAccessToken` | 同登出 | `logout_blacklist` | 同登出（TTL 自愈） |
| 改密/踢人/重置密码 | `invalidateAllSessions`（bump + removeRefresh） | 跳过全端下线，改密/重置主流程照常成功 | `invalidate_sessions` | bump 未持久化 → 恢复后旧 access 的 sv 与真实版本一致仍有效至自然过期（≤30min）；旧 refresh 仍为存量会话可继续续期至 refresh TTL（7d）——**延迟生效半径受 TTL 自限且恢复后管理员可重新踢人（届时 Redis 正常 bump 生效）**；改密本身（DB 写）不受影响 |

**不降级面（负向边界）**：凭据校验（DB BCrypt）、JWT 签名/有效期校验、账号状态检查（DB）——正确性底座不降级；`storeResetCode`/`consumeResetCode`（`auth:reset:` 找回码族）**维持硬依赖口径不动**（runbook 矩阵该行如实声明故障期不可用，不在本卡范围）；拦截器请求时校验既有降级零改动。

**复用检测安全语义保留**：轮换降级只在 Redis 不可用（基础设施异常）时发生；旋转脚本返回 REUSED/NO_SESSION 是正常业务结果，原样走既有分支（降级口只认不可用，不认业务结果——D1 同款边界）。

## 三、白名单

- `src/main/java/com/exam/auth/service/AuthService.java`（签发续期面逐触点 try-catch + 降级决策 + javadoc 半径论证）
- `src/main/java/com/exam/monitoring/metrics/BusinessMetrics.java`（countAuthDegraded javadoc/tag 面登记扩围，零新指标）
- `src/test/java/com/exam/auth/service/AuthServiceTest.java`（降级单测 + 构造适配 +1 mock）
- `docs/redis-dependency-runbook.md`（矩阵「令牌签发续期」行改降级口径 + §三 盘点注记 + §四 学生端指引更新 + §五 审计链追加）
- `.github/ci/gate-baseline.json`、`spec/changes/harden-token-store-redis-degradation/**`、`spec/README.md`、`docs/功能待办清单.md`
- **禁触**：`TokenStoreService.java` 本体（原语层零改动——降级决策在调用侧，理由见 §二）、`AuthenticationInterceptor.java`/`LoginGuardService.java`（先例本体零改动）、`MailService.java`/`JwtUtil.java`、`ExamDraftService.java`（硬依赖面）、`storeResetCode`/`consumeResetCode` 调用路径（auth:reset: 硬依赖族维持）、schema.sql、docker/mysql/migrations/、openapi.yaml、pom.xml、frontend/、docker/observability/**（零新告警资产）。

## 四、验收要点

- 降级单测六触点（对齐 A1/拦截器先例「放行+日志+计数」三断言形态）：登录版本读失败仍发 access；登录 refresh 存储失败不返回 refresh；轮换失败降级仅发 access（且不返回新 refresh、复用检测延迟裁决登记）；登出黑名单写失败照常成功；改密全端下线失败改密照常成功（DB 写完成、事务不回滚）；复用检测分支拒绝语义不变（REUSED 仍 TOKEN_INVALID）+ 正常路径零降级计数回归
- 变异击红：还原「Redis 异常即上抛」旧形态（摘除签发侧 try-catch）→ 降级用例确定性失败 → 复原回绿留证（优先 `scripts/mutation-check.sh` 驱动）；等价变异如实登记不强杀
- 范围外零改动核对（TokenStoreService/拦截器/登录守卫先例本体 + MailService/JwtUtil/ExamDraftService + auth:reset: 调用路径 + schema.sql + docker/mysql + openapi.yaml + pom + frontend + docker/observability）
- 门禁 `mvnw.cmd clean test` 全绿（仓库根、带 clean、禁 -o）；backendTests 基线在 651 上只增不减，收口笔同笔上调
- runbook 矩阵「令牌签发续期」行同笔改降级口径（逐触点故障行为与 tag 全量登记），§四 学生端「故障期新登录会失败」指引同步改写，§五 审计链追加本卡
- M 级理由：认证签发主链路既有测试密集需零回归；逐触点半径论证需落档（含 7d 自限半径的如实登记）；六触点降级语义需测试钉住

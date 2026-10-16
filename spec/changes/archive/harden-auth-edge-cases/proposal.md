# 变更提案：harden-auth-edge-cases

## 为什么（Why）

2026-10-16 三轮后端巡查 auth 域发现（中×4 + 低危顺带×4，B.2 台账），主 Agent 立项侦察源码直读核实：

1. **中 · 登录审计/锁定用 remoteAddr 而非 XFF 解析**：`AuthController.login` L53 `http.getRemoteAddr()`——反代（Nginx）部署下 `audit_log.ip_address` 恒为代理地址，取证失效；`LoginGuardService.recordFailure` L77 `logAccountLock(username, "unknown", ...)` IP 硬编码 "unknown"，锁定审计无 IP。仓库已有 `anticheat/ip/ClientIpResolver.resolve`（XFF 解析）未被 auth 使用。
2. **中 · reset-code 公开端点无按维度限流**：`AuthController.sendResetCode` L80-84 无任何限流（登录有 LoginGuard 按 账号+IP 维度），`MailService` 同步发信——脚本可对已注册邮箱高频轰炸（每封真发信）+ Redis 验证码键刷写。既有 `@RateLimit` 注解是全端点令牌桶整流（key 无调用方维度，见 `common/ratelimit/RateLimitInterceptor`），不满足「按 email/IP 维度」需求，需走 LoginGuard 同款固定窗口。
3. **中 · InviteCode.markUsed 读-改-写竞态**：`InviteCodeService.markUsed` L68-71 整实体 `updateById`——两教师并发注册同码时 `used_count` 丢失更新；更严重的是并发「作废 + 使用」时 markUsed 会把作废前读到的 `status=0` 旧值回写，**复活已作废邀请码**。
4. **中 · changePassword 两次 DB 写缺事务**：`AuthService.changePassword` L240-245 改密 + 清 `must_change_password` 两写无 `@Transactional`——第二步失败则密码已改而强制改密流未解除（用户陷入「新密码 + 仍被强制改密」矛盾态）。
5. **低 · Redis 计数 INCR/EXPIRE 两步非原子**：`LoginGuardService.checkRateLimit` L43-45 与 `recordFailure` L66-68——INCR 后进程崩溃/Redis 异常致 EXPIRE 未执行，键永不过期 → 账号永久限流/永久锁定。
6. **低 · 注册同号竞态 500**：`AuthService.register` L91 `existsByUsername` 查后插——并发同号注册撞 `uk_users_username` 抛 `DuplicateKeyException` 呈现为 500，而非友好的「账号已存在」。
7. **低 · 邮件文案与配置漂移**：`MailService.sendResetCode` L46 硬编码「5 分钟内有效」，而 `AuthProperties.ResetCode.ttlMinutes` 可配——配置改为 10 分钟后文案说谎。
8. **勘正（B.2 台账原中危条目前提不成立）**：台账原条目「禁用账号只拦新登录不踢存量会话」——立项侦察发现**用户禁用操作入口在代码中不存在**（user 包 controller 仅导入导出端点，`User.setStatus(1)` 无任何可达路径；`UserImportExportService` L213 恒 `setStatus(0)`）。该发现转为**规范设计约束**固化：「任何使账号不可用的管理操作（禁用/删除）必须联动全端下线（bumpSessionVersion + removeRefresh）」，待未来补建禁用功能时受此约束。不冒领为代码修复。

## 改什么（What）

1. **登录真实 IP**：`AuthController.login` 改用 `ClientIpResolver.resolve(http)`（import anticheat 包，不触碰该包文件）；`LoginGuardService.recordFailure` 签名扩展 `(username, ip)`，`logAccountLock` 传真实 IP 替代 "unknown"；`AuthService.login` L143 调用点同步传 ip。
2. **reset-code 按 email+IP 维度限流**：`LoginGuardService` 新增 `checkCodeRateLimit(email, ip)`——固定窗口计数（key `auth:coderl:{email}:{ip}`，窗口 1 分钟，阈值复用 `props.rateLimit.perMinute` 既有配置），超限抛 TOO_MANY_REQUESTS；`AuthService.sendResetCode` **入口最先调用**（先于邮箱存在性查询，防枚举语义不变、未注册邮箱同计数）。
3. **InviteCode 定向原子 UPDATE**：`markUsed` 改 `Wrappers.lambdaUpdate().setSql("used_count = used_count + 1").eq(id).eq(status, 0)`，返回 0 行抛 `INVITE_CODE_INVALID`（并发作废时注册回滚）——消除丢失更新与复活两条路径，`invalidate()` 不动。
4. **changePassword 加事务**：`@Transactional(rollbackFor = Exception.class)`——两 DB 写原子；Redis 操作（黑名单 + 全端下线）在其后，DB 回滚时未执行，顺序天然安全。
5. **INCR/EXPIRE 原子化**：`LoginGuardService` 三计数点（checkRateLimit / recordFailure / 新 checkCodeRateLimit）改 Lua 脚本原子执行（INCR + 首次置 EXPIRE，`DefaultRedisScript` 先例 `RedisTokenBucket`）——防键永不过期。
6. **注册竞态友好提示**：`userService.insert` 外 catch `DuplicateKeyException` 转 `ACCOUNT_ALREADY_EXISTS`（既有 ResponseCode）；事务内抛出仍回滚，语义不变。
7. **邮件文案读配置**：`MailService.sendResetCode` 签名扩展 `(email, code, ttlMinutes)`，文案动态生成「X 分钟内有效」；唯一调用方 `AuthService.sendResetCode` 传 `props.getResetCode().getTtlMinutes()`。
8. **规范设计约束**（非代码）：authentication 基线「会话主动失效」Requirement 追加 Scenario「账号不可用操作联动全端下线」——固化勘正后的设计约束；B.2 台账该条勘正留痕。

## 影响面（Impact）

- 落点：`src/main` + `src/test` 的 `com/exam/auth/**` 单包（AuthController、AuthService、LoginGuardService、InviteCodeService、MailService + 对应测试）；零 DDL、零契约变更（openapi paths 97 不变）、零前端。
- 既有语义保全红线：登录统一失败提示防枚举、reset-code 防枚举（未注册邮箱也返回成功）、Refresh 轮换复用检测、强制改密流、邀请码生成/作废/查询链路均零改动；`ClientIpResolver` 仅 import 不触碰。
- B.2 台账范围如实声明：`BehaviorCounterService.java:46` INCR/EXPIRE 同型问题在 anticheat 包，**不在本案白名单**，台账保持未修注记。
- 门禁基线：仅 backendTests 预期增长；M 级双岗隔离。

## 任务阶段

- 阶段 0 & 1：立项三件套与在途总账登记。
- 阶段 2：七项代码实施 + 单测先红后绿 + 变异击红留证。
- 阶段 3：全量门禁实测、基线上调、规范合入、归档、`--no-ff` 合并入 main。

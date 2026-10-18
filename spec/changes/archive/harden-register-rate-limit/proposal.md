# 提案：注册端点防刷与 CPU 耗尽 DoS 保护（harden-register-rate-limit）

> **变更 ID**：`harden-register-rate-limit`  
> **能力域**：`authentication`  
> **级别**：S 级安全加固（纯后端）  
> **前置依赖**：`harden-data-retention-scope`（已合入 main）  
> **影响面**：`AuthController`、`AuthService`、`LoginGuardService`、`AuthProperties`、`application.yml`、`docs/redis-dependency-runbook.md`

---

## 1. 为什么做（Why）

- **安全漏洞**：`POST /api/auth/register` 支持学生自主注册，底层对密码执行 `BCrypt.hashpw(req.getPassword(), BCrypt.gensalt(12))`。BCrypt 计算哈希具有高 CPU 消耗特征（单次耗时约 200~300ms）。
- **现状缺陷**：与登录接口（配有 `loginGuard.checkRateLimit` 账号+IP 限流）及找回密码验证码接口（配有 `checkCodeRateLimit` 邮箱+IP 限流）不同，当前注册接口**完全缺乏任何频控与限流措施**。攻击者单机并发 10~20 个虚假注册请求，即可将服务器全部 CPU 核心打满至 100%，导致全站所有业务（交卷、鉴权、在线心跳等）因 CPU 饥饿全面瘫痪。
- **治理目标**：为注册接口接入基于客户端 IP 的频控限流机制（默认 10 次/分钟/IP，可配置），拦截恶意密集请求并返回 HTTP 429；同时严格对齐全仓唯一的 Redis fail-open 降级哲学与可观测规范。

---

## 2. 改什么（What）

1. **配置显式化**：
   - [`AuthProperties.java`](file:///d:/code/examOnline/src/main/java/com/exam/config/AuthProperties.java) 的 `RateLimit` 内部类追加 `registerPerMinute` 属性（默认 10）；
   - [`application.yml`](file:///d:/code/examOnline/src/main/resources/application.yml) 显式声明 `exam.auth.rate-limit.register-per-minute: ${AUTH_REGISTER_RATE_LIMIT_PER_MINUTE:10}`。
2. **守卫层实现**：
   - [`LoginGuardService.java`](file:///d:/code/examOnline/src/main/java/com/exam/auth/service/LoginGuardService.java) 增加 `checkRegisterRateLimit(String ip)`：
     - 使用同源 Lua 脚本原子计数（前缀 `auth:register_rl:{ip}`，窗口 60 秒）；
     - 超限抛出 `BusinessException(ResponseCode.TOO_MANY_REQUESTS)`；
     - **Fail-Open 降级**：Redis 发生异常时记录包含 requestId 的 ERROR 日志，调用 `businessMetrics.countAuthDegraded(DEGRADED_REGISTER_RATE_LIMIT)` 递增指标并放行（不阻断正常注册）。
3. **接口接入**：
   - [`AuthController.java`](file:///d:/code/examOnline/src/main/java/com/exam/auth/controller/AuthController.java) 注册端点获取客户端 IP（经既有 [`ClientIpResolver.resolve(http)`](file:///d:/code/examOnline/src/main/java/com/exam/anticheat/ip/ClientIpResolver.java) 获取反代真实 IP），透传给 `AuthService.register(request, ip)`；
   - [`AuthService.java`](file:///d:/code/examOnline/src/main/java/com/exam/auth/service/AuthService.java) 入口首行调用 `loginGuard.checkRegisterRateLimit(ip)`。
4. **运维与文档同笔更新**：
   - [`docs/redis-dependency-runbook.md`](file:///d:/code/examOnline/docs/redis-dependency-runbook.md) 的键族矩阵追加 `auth:register_rl:` 注册限流定义与降级口径说明。

---

## 3. 约束与红线（Guardrails）

- **零 DDL**：纯内存/Redis 加固，严禁修改 `src/main/resources/schema.sql`，严禁新增数据库迁移脚本；
- **契约零漂移**：`POST /api/auth/register` 请求与响应体模型不变，OpenAPI 路径数保持 101 paths 不变；
- **降级哲学一致性**：必须严格复用 `exam.auth.degraded`（tag `check=register_rate_limit`），严禁新增未注册的告警规则或破坏 `AlertAssetsTest`；
- **双端门禁与 TDD 纪律**：新增用例先红后绿，变异验证破坏限流条件（如抽除超限判断或放行分支）必须断言级击红并回绿留证。

# 规范差异：authentication 域增加注册端点 IP 频控与 CPU 保护

> **能力域**：`authentication`  
> **对应变更**：`harden-register-rate-limit`

---

## ADDED Scenarios

### Requirement: 注册端点防暴力攻击与 CPU 耗尽保护

系统必须对公开注册接口 `POST /api/auth/register` 实施基于客户端 IP 的速率限制，以防范恶意脚本通过高并发触发密集 BCrypt 计算造成服务器 CPU 耗尽 DoS。

#### Scenario: 客户端 IP 正常注册请求在阈值内放行
- **GIVEN** 客户端 IP 在 1 分钟内的注册请求次数未达到配置上限（默认 10 次）
- **WHEN** 客户端提交合法参数的注册请求
- **THEN** 系统正常执行密码哈希与账号入库，返回 200 SUCCESS

#### Scenario: 同一客户端 IP 短时间内注册频次超限被 429 拦截
- **GIVEN** 客户端 IP 在当前 1 分钟窗口内累计注册请求数已达上限
- **WHEN** 客户端再次发起注册请求
- **THEN** 系统在进入 BCrypt 计算及数据库操作前直接拒绝该请求
- **AND** 返回 HTTP 429 与业务错误码 `TOO_MANY_REQUESTS`（请求过于频繁，请稍后重试）
- **AND** 服务器未产生 BCrypt 哈希计算开销

#### Scenario: 注册限流对 Redis 故障实行 Fail-Open 降级放行
- **GIVEN** Redis 服务不可用或发生连接超时
- **WHEN** 客户端发起注册请求
- **THEN** 系统捕获 Redis 异常，记录包含 `requestId` 的 ERROR 日志
- **AND** 递增降级监控计数 `exam.auth.degraded`（tag `check=register_rate_limit`）
- **AND** 请求降级放行，主业务逻辑不受阻断

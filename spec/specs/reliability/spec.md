# reliability 规范

> 能力域：可靠性（阶段 8，W10）。
> 来源：`spec/changes/archive/add-slow-sql-and-rate-limit` 合入（核心接口限流、分布式一致性、限流粒度）。
> 实施注记：超限返回 `ResponseCode.TOO_MANY_REQUESTS`（1008 → HTTP 429），由 `GlobalExceptionHandler` 统一转换。
> 遗留：`RateLimitInterceptor` 对 Redis 异常**无兜底**（当前 Redis 不可用会直接 500，限流器成为可用性单点）；fail-open 放行 + 告警的取舍待单独立项。

## Requirements

### Requirement: 核心接口限流

WHEN 访问交卷、拉卷、抽题等核心接口,

系统 SHALL 按接口维度限流（Redis 令牌桶），超限 SHALL 返回 429。

#### Scenario: 限流生效

GIVEN 接口令牌桶已配置容量与速率

WHEN 请求速率超过令牌桶放行能力

THEN 超出请求返回 429

AND 放行请求正常处理

#### Scenario: 峰值可抗

GIVEN 5000 人同时拉卷或交卷

WHEN 系统限流

THEN 后端以配置速率平滑放行

AND 数据库与 MQ 不被瞬时峰值打垮

---

### Requirement: 分布式一致性

WHEN 多实例部署,

系统 SHALL 使限流桶在实例间共享（令牌桶计数存 Redis，Lua 脚本原子操作）。

#### Scenario: 多实例共享桶

GIVEN 两个应用实例服务同一接口

WHEN 两者限流

THEN 共享同一 Redis 令牌桶

AND 总放行速率等于配置值而非翻倍

#### Scenario: 原子操作

GIVEN 并发请求同时扣减令牌

WHEN Lua 脚本执行

THEN 令牌扣减原子完成

AND 不出现超发

---

### Requirement: 限流粒度

WHEN 配置限流,

系统 SHALL 按接口粒度（非用户粒度），各核心接口 SHALL 可独立设定容量与速率。

#### Scenario: 接口独立限流

GIVEN 交卷、拉卷、抽题三个接口

WHEN 配置限流

THEN 每个接口有独立的桶容量与放行速率

AND 互不影响

# reliability 规范

> 能力域：可靠性（阶段 8，W10；限流器降级阶段 10）。
> 来源：`spec/changes/archive/add-slow-sql-and-rate-limit` 合入（核心接口限流、分布式一致性、限流粒度）+ `spec/changes/archive/add-rate-limit-resilience` 合入（限流器降级、降级可观测）。
> 实施注记：超限返回 `ResponseCode.TOO_MANY_REQUESTS`（1008 → HTTP 429），由 `GlobalExceptionHandler` 统一转换。
> 实施注记：限流依赖（Redis）异常时默认 **fail-open 放行**（`exam.ratelimit.fail-open`，默认 true），并打 ERROR 日志 + 递增 `exam.ratelimit.degraded` 计数器；置 false 则异常上抛（fail-close）。

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

---

### Requirement: 限流器降级

WHEN 限流依赖（Redis）不可用,

系统 SHALL 以可配置的降级策略保持核心接口可用（默认 fail-open 放行），且 SHALL 区分「业务超限」与「限流器降级」两种情形。

#### Scenario: Redis 异常时放行

GIVEN 令牌桶因 Redis 异常无法判定

WHEN 请求命中带 @RateLimit 的核心接口

THEN 请求被放行

AND 记录降级 ERROR 日志

#### Scenario: 业务超限不降级

GIVEN 令牌桶判定桶已空

WHEN 请求命中核心接口

THEN 抛 TOO_MANY_REQUESTS（429）

AND 不记为降级

#### Scenario: 可切回 fail-close

GIVEN 配置为 fail-close

WHEN 令牌桶因 Redis 异常无法判定

THEN 异常上抛，请求失败

AND 行为与降级前一致

---

### Requirement: 降级可观测

WHEN 限流器发生降级,

系统 SHALL 记录降级计数指标，使降级状态可被外部告警发现。

#### Scenario: 降级指标可采集

GIVEN 限流器发生一次降级

WHEN 采集指标

THEN 降级计数器递增

AND 可按接口维度区分

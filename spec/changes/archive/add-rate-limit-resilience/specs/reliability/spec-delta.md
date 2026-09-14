# 规范差异：reliability（限流器降级与可观测）

本文件包含对 `spec/specs/reliability/spec.md` 的规范变更（限流器降级与可观测，新增）。

## ADDED Requirements

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

### Requirement: 降级可观测
WHEN 限流器发生降级,
系统 SHALL 记录降级计数指标，使降级状态可被外部告警发现。

#### Scenario: 降级指标可采集
GIVEN 限流器发生一次降级
WHEN 采集指标
THEN 降级计数器递增
AND 可按接口维度区分

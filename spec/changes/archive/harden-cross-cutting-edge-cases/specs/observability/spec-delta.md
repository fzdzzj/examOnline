# observability 规范变更

## ADDED Requirements

### Requirement: 慢 SQL 指标与告警

WHEN 一条 SQL 执行耗时超过配置阈值,

系统 SHALL 递增慢 SQL 计数器 `exam.database.slow.sql`，并通过 Prometheus 告警规则 `DatabaseSlowSqlDetected` 实时告警，`AlertAssetsTest` 静态守护规则与指标不漂移。

#### Scenario: 慢 SQL 计数递增与告警 (harden-cross-cutting-edge-cases)

GIVEN 系统检测到一条 SQL 执行耗时超过阈值
WHEN 拦截器触发告警逻辑
THEN 输出 WARN 日志
AND 递增 `exam.database.slow.sql` 计数器
AND `DatabaseSlowSqlDetected` 规则在检测到增量时触发告警

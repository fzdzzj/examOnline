# 规范差异：observability

本文件包含对 `spec/specs/observability/spec.md` 的规范变更（可观测性，全部为新增）。

## ADDED Requirements

### Requirement: 指标导出
WHEN 系统运行,
系统 SHALL 经 Prometheus 格式暴露指标端点，供外部采集。

#### Scenario: 指标端点可访问
GIVEN 应用已启动
WHEN 访问指标端点
THEN 返回 Prometheus 格式指标
AND 包含 JVM 与应用级指标

### Requirement: 自定义业务指标
WHEN 核心业务发生,
系统 SHALL 记录交卷 QPS、交卷成功率、交卷耗时、MQ 队列深度、防作弊事件计数。

#### Scenario: 交卷指标
GIVEN 学生交卷
WHEN 交卷发生
THEN 交卷计数与耗时被记录
AND 成功/失败分别计数

#### Scenario: 队列深度指标
GIVEN MQ 队列有消息
WHEN 采集指标
THEN 暴露当前队列积压深度

### Requirement: 指标与请求关联
WHEN 记录指标,
系统 SHALL 使业务指标可关联到请求（经 requestId），便于定位故障。

#### Scenario: 请求链路可追溯
GIVEN 一次慢请求
WHEN 排查
THEN 可经 requestId 关联指标与日志
AND 定位具体慢 SQL 或慢操作
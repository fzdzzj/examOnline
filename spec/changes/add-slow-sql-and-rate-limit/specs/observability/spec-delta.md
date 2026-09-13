# 规范差异：observability（慢 SQL 识别）

本文件包含对 `spec/specs/observability/spec.md` 的补充变更（慢 SQL 识别，新增）。

## ADDED Requirements

### Requirement: 慢 SQL 识别
WHEN 一条 SQL 执行耗时超过配置阈值,
系统 SHALL 输出 WARN 日志，日志 SHALL 含 SQL、耗时、参数与关联 requestId。

#### Scenario: 慢 SQL 告警
GIVEN 一条 SQL 执行超过阈值（默认 1000ms）
WHEN 系统检测到超时
THEN 输出 WARN 日志
AND 日志含执行耗时、SQL 文本、参数与 requestId

#### Scenario: 正常 SQL 不告警
GIVEN 一条 SQL 在阈值内执行
WHEN 系统处理
THEN 不产生慢 SQL 日志
AND 不引入额外开销

### Requirement: 慢 SQL 与请求关联
WHEN 慢 SQL 发生时,
系统 SHALL 使日志的 requestId 与业务请求一致，便于从指标定位到具体请求个案。

#### Scenario: requestId 可追溯
GIVEN 一次慢请求包含慢 SQL
WHEN 排查
THEN 可经 requestId 关联指标方向与日志个案
AND 定位具体慢 SQL
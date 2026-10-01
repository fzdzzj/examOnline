# 规范差异：observability（异步链路请求关联）

本文件包含对 `spec/specs/observability/spec.md` 的规范变更（异步链路请求关联，新增）。

## ADDED Requirements

### Requirement: 异步链路请求关联
WHEN 请求经消息队列异步处理,
系统 SHALL 使生产者线程的 requestId 透传到消费者线程，使消费端日志与慢 SQL 日志仍可关联到原始请求。

#### Scenario: 交卷请求可逐单追溯
GIVEN 学生发起交卷且该 HTTP 请求已生成 requestId
WHEN 交卷消息被消费者批量落库
THEN 落库日志携带同一 requestId
AND 该批次内的慢 SQL 日志同样携带该 requestId

#### Scenario: 无 requestId 消息不污染日志
GIVEN 消息头不含 requestId（存量消息或手工投递）
WHEN 消费者处理
THEN 消费端不写入上一批次残留的 requestId
AND 处理完成后 MDC 被清理

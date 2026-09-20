# 规范差异：reliability（API 限流）

> ⚠️ **本 delta 只有一条该被合入**：实际进入 `spec/specs/reliability/spec.md` 的是
> 「分页入参上限」。其"全局 QPS 限流/降级/统一限流错误码"描述的组件已实现后**撤回删除**——
> 与既有 `@RateLimit` + `RedisTokenBucket` 重叠且更危险（一刀切 100 QPS 会把交卷接口压到
> 其自身 500 预算之下，并绕开"先鉴权再限流"）。按本文件合入会写入一条已不存在的机制。

本文件包含对 `spec/specs/reliability/spec.md` 的规范变更。

## ADDED Requirements

### Requirement: 参数验证上限

```markdown
WHEN 定义 API 查询参数，
系统 SHALL 为所有分页、排序、过滤参数设置合理上限。

#### Scenario: 分页大小限制
GIVEN 分页查询接口
WHEN 调用 /exams?page=1&size=101
THEN 返回 400 Bad Request
AND 错误码 PARAMETER_INVALID
AND 提示"pageSize 最大值为 100"

#### Scenario: 排序字段限制
GIVEN 排序参数接口
WHEN 调用 /exams?sortBy=deletedAt
THEN 仅允许白名单字段（id, name, status, deadlineTime）
AND 非法字段返回 400 错误
```

---

### Requirement: QPS 限流

```markdown
### Requirement: 分布式限流
WHEN 请求到达网关层，
系统 SHALL 使用 Redis Token Bucket 算法进行限流。

#### Scenario: 默认限流策略
GIVEN 普通用户请求
WHEN QPS > 100
THEN 拒绝额外请求
AND 返回 429 Too Many Requests
AND 响应头包含 Retry-After

#### Scenario: 核心接口更高限额
GIVEN 考试提交等核心接口
WHEN 并发请求 > 200
THEN 按 200 QPS 限流（高于普通接口）
AND 非核心接口按 50 QPS 限流
```

---

### Requirement: 降级策略

```markdown
### Requirement: Redis 故障降级
WHEN Redis 不可用，
系统 SHALL 降级为本地限流（Guava RateLimiter）。

#### Scenario: 限流服务降级
GIVEN Redis 连接失败
WHEN 执行限流检查
THEN 使用 Caffeine 本地缓存实现单机限流
AND 记录警告日志"rate limiting degraded to local"
AND 不影响主业务逻辑
```

---

### Requirement: 限流监控

```markdown
### Requirement: 限流指标上报
WHEN 触发限流，
系统 SHALL 上报以下指标：
- http_request_rate_limited_total (Counter)
- http_request_qps (Gauge)
- http_request_allowed_total (Counter)

#### Scenario: Prometheus 可见
GIVEN 应用运行中
WHEN 访问 /actuator/prometheus
THEN 可见上述三个指标
AND Grafana Dashboard 展示 QPS 趋势与限流次数
```

---

## MODIFIED Requirements

### Requirement: 异常处理

**原需求文本**:
```markdown
WHEN 发生业务异常，
系统 SHALL 返回统一错误格式。
```

**新需求文本**:
```markdown
WHEN 发生业务异常，
系统 SHALL 返回统一错误格式，包括：
- 错误码（如 RATE_LIMIT_EXCEEDED）
- 错误信息
- 建议操作（如"请稍后重试"）
- 请求 ID（用于追踪）
```

#### Scenario: 限流响应
GIVEN 超过 QPS 限制
WHEN 返回错误
THEN HTTP 状态码 429
AND body 包含:
{
  "code": "RATE_LIMIT_EXCEEDED",
  "message": "请求过于频繁，请稍后重试",
  "retryAfter": 60,
  "requestId": "abc123"
}

---

## REMOVED Requirements

无移除项。

---

## 规范变更总结

| 类型 | 需求名称 | 变更说明 |
|------|---------|----------|
| ADDED | 参数验证上限 | 分页/排序/过滤参数限制 |
| ADDED | QPS 限流 | Redis Token Bucket 算法 |
| ADDED | 降级策略 | Redis 故障时降级本地限流 |
| ADDED | 限流监控 | Prometheus 指标上报 |
| MODIFIED | 异常处理 | 新增限流错误码与响应格式 |

---

## 验证清单

- [ ] `curl -X GET "/exams?page=1&size=101"` 返回 400
- [ ] Prometheus 可见 `http_request_rate_limited_total` 指标
- [ ] Grafana Dashboard 展示 QPS 趋势
- [ ] 压测：1000 并发下系统稳定
- [ ] Redis 断连时降级为本地限流
- [ ] 全量测试通过，无回归问题

---

## 参考示例

### ✅ 正确示例：参数验证

```java
@RestController
@RequestMapping("/exams")
public class ExamController {
    
    @GetMapping
    public ApiResponse<List<ExamResponse>> page(
        @RequestParam(defaultValue = "1") @Min(1) long page,
        @RequestParam(defaultValue = "10") @Min(1) @Max(100) long size) {
        // ...
    }
}
```

### ❌ 错误示例：无上限

```java
// ❌ 禁止
@RequestParam(defaultValue = "10") long size  // 无 @Max
```

### ✅ 正确示例：限流配置

```java
@Configuration
public class RateLimitConfig {
    
    @Bean
    public RateLimiter rateLimiter() {
        // Token Bucket: 100 QPS, burst 150
        return RateLimiter.create(100.0, 150.0);
    }
}
```

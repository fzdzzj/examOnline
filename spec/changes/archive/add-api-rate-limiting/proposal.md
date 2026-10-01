# 提案：API 限流与防抖机制（阶段 9，可靠性增强）

> ⚠️ **本提案的"新建全局限流器"部分已被撤销并收敛到既有机制**（见
> [`../../IMPLEMENTATION_STATUS.md`](../../IMPLEMENTATION_STATUS.md)）。
>
> 项目**早就有**一套按端点声明式限流：`@RateLimit` + `RedisTokenBucket` +
> `RateLimitInterceptor`，通过 `WebMvcConfig` **刻意注册在鉴权之后**（先鉴权再限流），
> 且只对打了注解的热点端点生效，预算是按端点实测调过的：
> `random-draw` 50/200、`submit` 500/2000、`pull-paper` 2000/5000，
> 另有 Redis 故障 fail-open 的降级计数 `exam.ratelimit.degraded`。
>
> 本提案另起的全局拦截器对 `/api/**` 无差别按 100qps 限流，会有两处实质危害：
> 1. 把 `submit` 压到 500 预算之下——恰好在 5000 人同时交卷这个系统最关键场景上限错；
> 2. 绕开"先鉴权再限流"的既定顺序，未认证流量也能消耗令牌桶。
> 两套并存还让故障归因变难（同一个 429 分不清来自哪个桶）。
>
> 因此全局拦截器（`RateLimitConfig` 及其测试、`rate-limiting.*` 配置）已删除，
> 需要限流的新端点一律走 `@RateLimit` 显式声明预算。**保留**的部分是
> `ExamController.page()` 的 `@Min/@Max` 分页上限——那属于入参校验，与限流无关。

## Why

**当前问题**: 分页查询无上限限制，存在 DoS 攻击风险。

### 问题现状

```java
// ExamController.java:70-73
@GetMapping
public ApiResponse<List<ExamResponse>> page(
    @RequestParam(defaultValue = "1") long page,
    @RequestParam(defaultValue = "10") long size) {  // ❌ 无上限
    // ...
}
```

**风险场景**:
1. 攻击者调用 `/exams?page=1&size=10000` → 大响应耗尽带宽
2. 慢查询导致数据库连接池耗尽
3. 单接口占用全部 CPU/内存资源

**安全等级**: ⚠️ **中危**（CVSS 6.5）

**背景**:
- Spring Validation 已集成（@Validated）
- 但未定义业务参数上限
- 面试价值：可讲"分层限流"实战经验

**期望状态**:
- 所有分页接口 `size` 参数 ≤ 100
- 全局 QPS 限流（Redis + Token Bucket）
- 区分核心/非核心接口限流策略

## What Changes

### 代码变更

| 文件 | 修改类型 | 说明 |
|------|---------|------|
| `ExamController.java` | MODIFIED | 添加 `@Max(100)` 验证 |
| `RateLimitConfig.java` | NEW | 新建 Redis 限流配置 |
| `GlobalExceptionHandler.java` | MODIFIED | 新增限流异常处理 |
| `application.yml` | ADDED | 限流阈值配置 |

### 规范变更

- `spec/specs/reliability/spec.md` - **ADDED**: 新建 API 限流规范

## Impact

### 受影响的规范
- `spec/specs/reliability/spec.md` - 新建限流规范

### 受影响的代码
- `com.exam.controller.ExamController`
- `com.exam.config.RateLimitConfig` (新建)
- 所有 Controller 的查询接口

### 用户影响
- **安全性提升**: 防止 DoS 攻击
- **体验变化**: 超大分页被拒绝（返回 400）

### API 变更
- 无功能 API 变更
- 新增错误码 `RATE_LIMIT_EXCEEDED`

### 需要迁移
- [x] 数据库迁移（无）
- [ ] 配置变更（application.yml）
- [x] 文档更新（规范 + 决策记录）
- [ ] 测试验证（压力测试）

## 时间线评估

**小**: 约 1 天（W13）
- 限流实现：0.5 天
- 测试验证：0.25 天
- 文档更新：0.25 天

## 风险

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| 误伤正常大查询 | 低 | 低 | 提供管理员白名单 |
| Redis 依赖故障 | 中 | 高 | 降级为本地限流 |

## 验收标准

1. ✅ `size > 100` 返回 400 错误
2. ✅ Prometheus 可见 `http_request_rate_limited_total` 指标
3. ✅ Grafana Dashboard 展示 QPS 趋势
4. ✅ 压测：1000 QPS 下系统稳定
5. ✅ 全量测试通过，无回归问题

## 备选方案

**方案 B（不推荐）**: 仅前端限制分页大小
- 缺点：后端无防护，可绕过

**方案 C（折中）**: 使用 Guava RateLimiter
- 优点：简单
- 缺点：单机限流，多实例不一致

**推荐方案 A**: Redis + Token Bucket 分布式限流

## 参考资源

- [Spring Cloud Gateway Rate Limiter](https://spring.io/projects/spring-cloud-gateway)
- [Redis Rate Limiting Best Practices](https://redis.io/docs/latest/develop/use-cases/rate-limiting/)

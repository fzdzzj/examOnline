# 提案：慢 SQL 识别 + 接口限流（阶段 8.1 补全）

## Why

阶段 8「性能深化」三块中，缓存三防、读写分离读己之写、Prometheus 指标均已落地，但经代码证据核实仍有两块缺口，是当前剩余的真实技术债：

1. **慢 SQL 拦截器缺失**：`BusinessMetrics` 已在 Javadoc 中 `@see com.exam.common.slow_sql.SlowSqlInterceptor`，但该类从未创建——可观测性「慢 SQL 识别」的需求（performance spec-delta 第 6 条 Requirement）未兑现，`MybatisPlusConfig` 也未注册任何慢查询拦截器。
2. **接口限流缺失**：登录接口有固定窗口限流（`LoginGuardService`），但交卷、拉卷、抽题三个核心接口无任何限流——5000 人开考拉卷/交卷高峰会直接把压力打进 DB 与 MQ，缺少抗雪崩的入口保护。

两者均落在**拦截器/切面层**（一个 MyBatis Interceptor、一个接口限流注解切面），写入边界高度重叠，合并为一次变更，避免拆分后并发写 `MybatisPlusConfig`/config 包。

**背景**：
- 慢 SQL 该用 MyBatis Interceptor（`@Intercepts`）而非 AOP——能拿到真实 SQL、参数、耗时，与 `%X{requestId}` 日志链路（`logging.pattern`）配合「指标定方向、日志定个案」；
- 接口限流用 **Redis 令牌桶 + Lua 脚本原子性**——分布式一致（多实例共享桶），面试可讲；不用本地 Guava（单机不一致）；
- 已存在基础设施：`RequestIdFilter`（MDC requestId）、`StringRedisTemplate`（Redis）、`MybatisPlusConfig`（interceptor 注册点）、`LoginGuardService`（固定窗口计数可参照）。

**当前状态**：无慢 SQL 拦截器；核心接口（交卷/拉卷/抽题）无限流。

**期望状态**：超阈值的 SQL 打出含 requestId 的 WARN 日志；交卷、拉卷、抽题接口按注解声明式限流，过载返回 429，峰值可抗。

## What Changes

- **慢 SQL 拦截器**（MyBatis Interceptor）：
  - `SlowSqlInterceptor implements Interceptor`，`@Intercepts` 拦截 `Executor.query/update`；
  - 可配阈值（默认 1000ms），超阈值打 WARN（含耗时、SQL、参数、requestId）；
  - 注册进 `MybatisPlusConfig` 的 `MybatisPlusInterceptor`。
- **接口限流**（Redis 令牌桶 + 注解）：
  - `@RateLimit` 注解（挂 Controller 方法）；
  - `RateLimitInterceptor`（HandlerInterceptor）读注解，按「接口」维度令牌桶限流；
  - Lua 脚本原子令牌桶（GET + DECR + 续期），分布式一致；
  - 超限抛 429（复用 `ResponseCode.RATE_LIMITED`）。

## Impact

### 受影响的规范
- `spec/specs/observability/spec.md` - 修改（`ADDED`）：慢 SQL 识别。
- `spec/specs/reliability/spec.md` - 新建（`ADDED`）：接口限流。

### 受影响的代码
- `common/slow_sql/`（SlowSqlInterceptor）、`common/ratelimit/`（注解+拦截器+Lua）、`config/`（注册）、`exam/taking/paper/question` 的 Controller 加 `@RateLimit`

### 用户影响
- 无功能变化；高并发下核心接口受保护。

### API 变更
- 超限时返回 429（已有错误码 `RATE_LIMITED`）；无破坏性变更。

### 需要迁移
- [ ] 数据库迁移（无新表）
- [x] 配置变更（慢 SQL 阈值、限流桶参数）
- [ ] API 版本提升
- [x] 文档更新（本提案 + 规范 + 面试弹药）

## 时间线评估

小：约 2-3 天（W10）。

## 风险

- **慢 SQL 拦截器开销** —— 缓解：只在超阈值时日志，拦截器本身 O(1)，对每条 SQL 仅计时。
- **令牌桶分布式一致性** —— 缓解：令牌桶计数放 Redis，Lua 脚本原子 GET+DECR，多实例共享一桶。
- **限流过严误伤** —— 缓解：桶容量与速率按接口可配，交卷/拉卷/抽题分别设独立阈值。
- **限流维度** —— 缓解：按接口粒度（非用户粒度），峰值保护而非惩罚单用户。
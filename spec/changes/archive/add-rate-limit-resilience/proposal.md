# 提案：限流器可用性 —— Redis 异常降级放行（阶段 10）

## Why

限流的目的是**保可用**，但当前实现让限流器自己变成了可用性单点。

经代码核实（`RateLimitInterceptor.preHandle` 全文已读）：打了 `@RateLimit` 的接口直接调用 `tokenBucket.tryAcquire(key, capacity, qps)`，**外层没有任何 try-catch**；而 `RedisTokenBucket.tryAcquire` 内部走 `redisTemplate.execute(...)`（Lua EVAL）：

- Redis 连接失败、超时或脚本异常时，异常从 `preHandle` 直接冒泡 → 请求失败。
- 后果：**Redis 一挂，交卷、拉卷、抽题这三个最核心的接口整体不可用**——为保护核心链路而加的限流，反而成了核心链路的单点。
- 更糟的是这个失败模式**完全不可观测**：既无降级日志、也无指标。运维无法区分「真被限流（429）」和「限流器坏了（5xx/异常）」。

该取舍此前登记在 `spec/specs/reliability/spec.md` 的「遗留」段（"fail-open 放行 + 告警待单独立项"），本次正式落项。

**背景（已核实的既有设施）**：
- 已有 `BusinessMetrics`（Micrometer，`@Component`），埋降级指标是**接线**而非新建机制；其内部约定「业务侧只依赖本类打点，不在各 Service 散落 Micrometer API」，故降级打点走 `BusinessMetrics` 而非直接注入 `MeterRegistry`。
- 已有 `RateLimitInterceptorTest`（3 个用例：桶空 429 / 放行 / 未打注解跳过）与 `RateLimitEndToEndTest`（端到端 429），可在其上扩展降级用例。
- `ResponseCode.TOO_MANY_REQUESTS(1008, ..., 429)` 已存在，**不得**新增错误码。

**期望状态**：Redis 异常时 **fail-open 放行**（优先保核心链路可用），同时打 ERROR 日志 + 埋降级计数指标，使「限流器降级中」可被外部告警发现；并保留可配置的 fail-close 开关。

## What Changes

### 1. `RateLimitInterceptor`（`common/ratelimit/`）
- 把 `tokenBucket.tryAcquire(...)` 包进 `try-catch (Exception)`，并**严格区分两条路径**：
  - （**只捕 `Exception`、不捕 `Throwable`**：`Throwable` 会连 `OutOfMemoryError`/`StackOverflowError` 一起当成"降级"放行，属误用——那些是进程级故障，放行只会让问题扩大。）
  - **正常返回 `false`（桶空 = 业务超限）→ 保持抛 `BusinessException(ResponseCode.TOO_MANY_REQUESTS)`（429），绝不被 catch 吞掉**。因此「取返回值」必须在 try 内、「判 false 抛 429」必须在 try 外（或 catch 只捕获异常、不捕获 false）。
  - **抛异常（Redis 侧故障）→ fail-open 分支**：打 ERROR 日志（含接口标识、异常摘要、以及 MDC 里的 requestId）→ 埋降级计数 → `return true` 放行。
- **构造器签名改为** `RateLimitInterceptor(RedisTokenBucket tokenBucket, BusinessMetrics businessMetrics, boolean failOpen)`（原为单参）。
- fail-close 分支：`failOpen == false` 时异常原样上抛（先记日志与埋点，再 rethrow）。
- 类注释写明取舍理由：限流是保护手段而非业务正确性约束，Redis 故障时优先保核心链路可用。

### 2. `WebMvcConfig`（`auth/security/`）—— **本提案的隐藏依赖，必须一起改**
- `RateLimitInterceptor` **不是 Spring Bean**，而是在 `WebMvcConfig.addInterceptors()` 里 `new RateLimitInterceptor(redisTokenBucket)` 手工构造的（已核实）。
- 因此：`WebMvcConfig` 构造器新增 `BusinessMetrics` 形参 + `@Value("${exam.ratelimit.fail-open:true}") boolean failOpen`，并改为 `new RateLimitInterceptor(redisTokenBucket, businessMetrics, failOpen)`。
- **只改这两处**，不要动 `AuthenticationInterceptor` 的注册与 excludePathPatterns。

### 3. `BusinessMetrics`（`monitoring/metrics/`）
- 新增降级计数器 `exam.ratelimit.degraded`（tag 键用 `endpoint`，值 = 接口标识，与桶 key 派生的接口标识同一口径）。
- 参照既有的 `countAntiCheatEvent(String)` 写法（`ConcurrentHashMap<String, Counter>` 按值缓存 + `computeIfAbsent` 注册），新增 `countRateLimitDegraded(String endpoint)`。

### 4. `application.yml`
- 在既有 `exam.ratelimit:` 段（约 146 行）下新增 `fail-open: ${RATE_LIMIT_FAIL_OPEN:true}`，并补一行说明：默认放行以保核心链路可用，置 false 则异常上抛（fail-close）。

### 5. 测试
- `RateLimitInterceptorTest`：**因构造器签名变化，3 处 `new RateLimitInterceptor(bucket)` 需补参**（如 `new RateLimitInterceptor(bucket, mock(BusinessMetrics.class), true)`）。**只补构造参数，不得改动既有断言**。
- 新增降级用例（同文件）：
  1. `tryAcquire` 抛异常 + `failOpen=true` → `preHandle` 返回 `true`（放行），且 `businessMetrics.countRateLimitDegraded(...)` 被调用一次；
  2. `tryAcquire` 抛异常 + `failOpen=false` → 异常上抛（fail-close 分支有效）；
  3. 桶空（返回 false）→ 仍抛 429 且**不**记为降级（`verify(businessMetrics, never())...`）。

## Impact

### 受影响的规范
- `spec/specs/reliability/spec.md` — 新增（`ADDED`）：限流器降级、降级可观测；并删除该文件头部「遗留」行（Redis 异常无兜底）。

### 受影响的代码（写入边界，务必按此收敛）
- `common/ratelimit/RateLimitInterceptor`（主改）
- `auth/security/WebMvcConfig`（构造器接线 + 新依赖注入）
- `monitoring/metrics/BusinessMetrics`（新增降级计数器）
- `src/main/resources/application.yml`（新增 fail-open，**只加这一项**）
- `src/test/java/com/exam/common/ratelimit/RateLimitInterceptorTest`（补构造参数 + 新增用例）

**不要触碰**：`RedisTokenBucket`（Lua 与桶语义不变）、`RateLimit` 注解、`RateLimitEndToEndTest`、`ResponseCode`、`GlobalExceptionHandler`、`AuthenticationInterceptor`。

### 用户影响
- Redis 故障时核心接口不再整体失败；极端情况下可能放行超额流量（见风险，属有意取舍）。

### API 变更
- 无（429 语义不变；降级时不返回 429 而放行，属新增行为）。

### 需要迁移
- [ ] 数据库迁移（无新表）
- [x] 配置变更（新增 `exam.ratelimit.fail-open`，默认 true）
- [ ] API 版本提升
- [x] 文档更新（本提案 + 规范 + README 遗留清单）

## 时间线评估

小：约 1 天（W12）。

## 风险

- **fail-open 会削弱保护** —— Redis 挂掉期间令牌桶失效，尖峰可能直接打进 DB/MQ。取舍理由：限流是**保护手段**而非业务正确性约束，「核心链路整体不可用」比「可能过载」更严重；且过载仍有 `ExamSweepService` 对账兜底可恢复。反向选项是 fail-close（快速失败 + 告警），代价是 Redis 故障时核心链路全灭——**默认取 fail-open；如需改口径请在实施前与用户确认**。
- **降级不可观测 = 等于没做** —— 必须同时有 ERROR 日志与指标，否则降级会长期潜伏无人察觉。
- **catch 边界过宽会吞掉编程错误** —— 若 `catch(Exception)` 用得过泛，`tokenBucket` 之外的 NPE、参数错误也会被当成「降级」静默放行。缓解：try 范围收紧到**仅包住 `tryAcquire` 调用**；日志保留完整异常栈；注释注明该分支只应命中基础设施异常。**尤其注意：`false`（桶空）不是异常，绝不能被降级逻辑接走——这是本变更最容易写错、也最致命的一点**（会把限流彻底架空）。

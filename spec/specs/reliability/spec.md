# reliability 规范

> 能力域：可靠性（阶段 8，W10；限流器降级阶段 10；定时扫描多实例安全阶段 13）。
> 来源：`spec/changes/archive/add-slow-sql-and-rate-limit` 合入（核心接口限流、分布式一致性、限流粒度）+ `spec/changes/archive/add-rate-limit-resilience` 合入（限流器降级、降级可观测）+ `spec/changes/archive/add-multi-instance-sweep-safety` 合入（定时扫描多实例安全、不引入调度锁的取舍）。
> 实施注记：超限返回 `ResponseCode.TOO_MANY_REQUESTS`（1008 → HTTP 429），由 `GlobalExceptionHandler` 统一转换。
> 实施注记：限流依赖（Redis）异常时默认 **fail-open 放行**（`exam.ratelimit.fail-open`，默认 true），并打 ERROR 日志 + 递增 `exam.ratelimit.degraded` 计数器；置 false 则异常上抛（fail-close）。
> 实施注记（阶段 13）：定时扫描正确性靠下游幂等（CAS + 唯一索引 + INSERT IGNORE + 消费端 casFillAnswers），**刻意不加分布式调度锁**；重复扫描指标 `exam.sweep.duplicate_detected`（tag `task`=`sweep`/`state-advance`，含消费者 `filled==0`）；交卷锁按 token 解锁（`RedisLockHelper` Lua compare-and-delete，`exam.taking.submit.lock-ttl-seconds` 默认 30）。

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

---

### Requirement: 定时任务的并发与多实例安全

WHEN 同一套服务部署多个实例,

系统 SHALL 使定时扫描（考试状态推进、超时交卷兜底与答案补发对账）**在多实例并发执行下仍只产生一次业务效果**，且 SHALL 不依赖外部调度中间件来保证这一点。

#### Scenario: 并发扫描同一份超时答卷只交卷一次

GIVEN 一份已超时的进行中答卷

WHEN 两个实例的扫描同时处理它

THEN 只有一个实例完成状态迁移

AND 强制交卷只成功一次（答卷唯一、`submissionId` 唯一）

AND 同轮对账补发允许再投递，消费端幂等跳过

AND 答卷仍是唯一一条

#### Scenario: 并发扫描不重复落库

GIVEN 一条已交卷但答案未落库的答卷

WHEN 两个实例的扫描同时重新投递

THEN 答案只被写入一次

AND 重复投递被消费端幂等跳过

#### Scenario: 并发结束只标记一次缺考

GIVEN 一场考试在并发路径下被迁到已结束

WHEN 两条路径同时触发缺考标记

THEN 每个应考且无答卷的学生恰好一条缺考记录

AND 不产生重复缺考

#### Scenario: 并发推进同一场考试只迁移一次

GIVEN 一场到期未推进的考试

WHEN 两个实例同时推进

THEN 状态只迁移一次

AND 版本号只递增一次

#### Scenario: 重复扫描可被观测

GIVEN 多实例在重复处理同一批数据

WHEN 下游以幂等方式跳过这些重复处理

THEN 系统产生可采集的重复处理计数

AND 据此可判断是否需要引入调度锁，而非凭感觉决策

---

### Requirement: 不引入调度中间件与锁的取舍

WHEN 选择是否用分布式锁协调定时扫描,

系统 SHALL 以「正确性由幂等保证、锁仅用于省资源」为准则，且 SHALL NOT 让定时兜底能力依赖 Redis 等外部组件的可用性。

#### Scenario: 定时兜底不因锁而停摆

GIVEN 分布式协调组件（如 Redis）不可用

WHEN 定时扫描触发

THEN 扫描照常执行

AND 不因抢不到锁而整体跳过

#### Scenario: 幂等是正确性的唯一保证

GIVEN 调度锁失效或未启用

WHEN 多个实例并发扫描

THEN 业务效果仍只发生一次

AND 该性质由自动化测试证明

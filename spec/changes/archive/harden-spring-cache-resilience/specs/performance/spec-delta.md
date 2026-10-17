# spec-delta：performance 域（harden-spring-cache-resilience）

## ADDED Requirements

### Requirement: Spring Cache 对 Redis 异常的降级容错 (harden-spring-cache-resilience)

WHEN Redis 不可用时缓存切面执行读取（@Cacheable get）、回填（put）或失效（@CacheEvict evict/clear）, 且互斥回源器（CacheMutexLoader）执行其直连 Redis 触点（快速路径读/空标记读写/互斥锁/回填写/解锁/空标记清除）,

系统 SHALL 静默降级继续（回源 DB / 放弃回填 / 失效延迟生效 / 跳过互斥）并记录降级计数与 ERROR 日志, AND SHALL NOT 因基础设施异常裸穿 500 使拉卷、考试详情与考试写路径停摆。

#### Scenario: 读失败静默回源 DB (harden-spring-cache-resilience)

GIVEN 缓存只是加速器，DB 是正确性同源

WHEN 缓存读取因 Redis 异常失败

THEN 请求继续走方法体回源 DB，返回正确数据

AND 记录 ERROR 日志，降级计数器按动作 tag 递增（action=get）

#### Scenario: 失效失败脏读窗口受 TTL 自愈 (harden-spring-cache-resilience)

GIVEN 失效写（evict/clear）不生效时旧值存续

THEN 考试详情缓存（examDetail）的脏读窗口受短 TTL（5m + 抖动 1m）自愈

AND 快照类缓存（examSnapshot/paperSnapshot）发布后只读不可变，缓存与 DB 天然一致，不存在 DB 已改而缓存仍旧值的脏读语义

AND 记录 ERROR 日志，降级计数器按动作 tag 递增（action=evict/clear）

#### Scenario: 互斥回源器降级保拉卷可用 (harden-spring-cache-resilience)

GIVEN 互斥锁与空标记只是防抖与穿透防护器，回源本身幂等

WHEN 互斥锁不可用或空标记读写因 Redis 异常失败

THEN 跳过互斥与等待直接回源 DB（与交卷锁降级同哲学：防抖半径损失，正确性不变）

AND 业务语义异常（NOT_FOUND 等）原样传播，降级口只认不可用不认业务结果

AND 回填与解锁失败放弃本次、交由缓存/锁 TTL 自愈

#### Scenario: 降级计数与告警映射同源 (harden-spring-cache-resilience)

GIVEN 仓库已有 exam.ratelimit.degraded / exam.auth.degraded / exam.submit.lock.degraded 三先例

WHEN Spring Cache 链路发生降级

THEN 打点 exam.cache.degraded（tag action 代码常量有界）+ 告警规则 CacheDegraded 照抄同源形态，AlertAssetsTest known 集合同笔守护

AND 不新增第二套降级哲学

#### Scenario: 变异击红 (harden-spring-cache-resilience)

GIVEN 还原为无容错旧形态（Redis 异常直接上抛）

WHEN 运行缓存降级单测

THEN 以确定性断言失败（先红后绿留证；等价变异如实登记不强杀）

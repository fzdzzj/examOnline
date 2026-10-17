# spec-delta：authentication（登录守卫对 Redis 异常的降级容错）

## 变更类型

ADDED（新增 Requirement「登录守卫对 Redis 异常的降级容错」，五 Scenario；既有 Requirement 与 Scenario 零改动）

### Requirement: 登录守卫对 Redis 异常的降级容错

WHEN Redis 不可用时用户尝试登录或找回密码（登录限流、锁定检查、失败计数、计数清理、验证码限流）,

系统 SHALL 降级放行并记录降级计数与 ERROR 日志, AND SHALL NOT 因基础设施异常裸穿 500 使登录全停摆。

#### Scenario: Redis 异常时放行

GIVEN 登录限流/锁定检查/失败计数/计数清理/验证码限流任一 Redis 调用因 Redis 异常失败

WHEN 用户尝试登录（或找回密码）

THEN 请求按无该 Redis 支撑的正常流程继续（限流/锁定/计数保护暂缺，凭据校验照常）

AND 记录 ERROR 日志，降级计数器递增（复用 exam.auth.degraded，tag 按触点区分、代码常量有界）

AND 正常拒绝路径不降级：凭据错误仍按既有口径失败、锁定命中的账号在 Redis 可读时仍被拒绝、限流超限在 Redis 可读时仍 429

#### Scenario: 降级半径自限且可论证

GIVEN Redis 故障期间限流/锁定/失败计数保护暂缺

THEN 暴力破解减速器暂时弱化，但登录正确性底座（DB 凭据校验）不受影响

AND 失败计数清理降级无正确性影响（计数键按既有 TTL 自然过期自愈），锁定/计数保护在 Redis 恢复后自愈

AND 替代口径（Redis 异常裸穿 500 使登录全停摆）严格更糟；限流侧本就 fail-open，Redis 故障期间该防护无论如何不生效

#### Scenario: 与拦截器/限流降级口径同源

GIVEN 仓库已有 exam.ratelimit.degraded（fail-open）与 exam.auth.degraded（拦截器降级放行 + AuthDegraded 告警）先例

WHEN 登录守卫发生降级

THEN 打点与日志口径与先例一致（复用 exam.auth.degraded 指标与既有 AuthDegraded 告警，不新增第二套降级哲学、不新增告警资产）

#### Scenario: 变异击红

GIVEN 还原为无容错旧形态（Redis 异常直接上抛）

WHEN 运行登录守卫容错单测

THEN 以确定性断言失败（先红后绿留证；等价变异如实登记不强杀）

#### Scenario: 范围外不越界

GIVEN Redis 依赖裁决串行（A1 登录守卫 → D1 交卷锁 → B2+C2 runbook），草稿为已声明的硬依赖面

WHEN 本变更收口

THEN 交卷 SETNX 锁（ExamSubmitService）、RedisCacheManager（CacheConfig）、草稿 Redis 存储（ExamDraftService）、RedisLockHelper/CacheMutexLoader、拦截器先例本体与 AuthService 调用链零改动

AND 不在收口笔中夹带 D1/runbook 卡应辖的任何行为变更

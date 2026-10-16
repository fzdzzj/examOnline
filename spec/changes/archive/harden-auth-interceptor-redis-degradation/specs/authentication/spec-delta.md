# spec-delta：authentication（鉴权拦截器对 Redis 异常的降级容错）

## 变更类型

ADDED（新增 Requirement「鉴权拦截器对 Redis 异常的降级容错」，五 Scenario；既有 Requirement 与 Scenario 零改动）

### Requirement: 鉴权拦截器对 Redis 异常的降级容错

WHEN Redis 不可用时携带有效 JWT 的请求经过鉴权拦截器（登出吊销黑名单、会话版本检查）,

系统 SHALL 降级放行并记录降级计数与 ERROR 日志, AND SHALL NOT 因基础设施异常裸穿 500 使全部 API 停摆。

#### Scenario: Redis 异常时放行

GIVEN 黑名单/会话版本查询因 Redis 异常失败

WHEN 携带有效 JWT 的请求经过拦截器

THEN 请求被放行并正常处理

AND 记录 ERROR 日志，降级计数器递增

#### Scenario: 降级半径自限且可论证

GIVEN Redis 故障期间黑名单不可读

THEN 已吊销 Access token 的复活半径 ≤ 其剩余寿命（≤30min）

AND 与在案观察项「Redis 清空后已吊销 Access ≤30min 复活」同半径 by design（黑名单本就是 TTL 自限设计）

AND 会话版本检查失败意味着改密 bump 暂时无法踢下线，半径同性质同上界

#### Scenario: 与限流降级口径同源

GIVEN 仓库已有 `exam.ratelimit.degraded`（fail-open + ERROR 日志 + 计数）先例

WHEN 拦截器发生降级

THEN 打点与日志口径与该先例一致（指标名对齐先例命名法，`AlertAssetsTest` 守护同笔登记）

AND 不新增第二套降级哲学

#### Scenario: 变异击红

GIVEN 还原为无容错旧形态（Redis 异常直接上抛）

WHEN 运行拦截器容错单测

THEN 以确定性断言失败（先红后绿留证；等价变异如实登记不强杀）

#### Scenario: 范围外不越界

GIVEN 「Redis 依赖与草稿兜底」裁决页后置

WHEN 本变更收口

THEN RedisCacheManager、登录守卫、草稿 Redis 存储、交卷 SETNX 锁四处零改动

AND 不在收口笔中夹带裁决页应辖的任何行为变更

# spec-delta：authentication 域（harden-token-store-redis-degradation）

## ADDED Requirements

### Requirement: 令牌签发与续期面对 Redis 异常的降级容错 (harden-token-store-redis-degradation)

WHEN Redis 不可用时用户登录（签发）、刷新（续期轮换）、登出或改密/踢人/重置密码（全端下线）,

系统 SHALL 逐触点降级保可用（仍发 access / 不返回 refresh / 跳过轮换与下线写）并记录降级计数与 ERROR 日志, AND SHALL NOT 因基础设施异常裸穿 500 使登录/续期/登出/改密停摆。

#### Scenario: 签发侧 Redis 异常仍发 access (harden-token-store-redis-degradation)

GIVEN 登录凭据校验已通过（DB BCrypt 不降级）

WHEN 会话版本读取或 Refresh 会话登记因 Redis 异常失败

THEN access token 仍被签发并返回登录成功

AND Refresh 会话登记失败时本次不返回 refresh（响应缺 refreshToken 字段）

AND 记录 ERROR 日志，降级计数器按触点 tag 递增（issue_session_version / issue_refresh_store）

#### Scenario: 续期轮换失败降级仅发 access (harden-token-store-redis-degradation)

GIVEN 刷新请求携带签名有效的 Refresh Token

WHEN 原子轮换（含复用检测）因 Redis 异常失败

THEN 跳过轮换与复用检测，凭签名与 DB 账号状态校验签发 access（不返回新 refresh，客户端保留旧 refresh）

AND 轮换脚本返回 REUSED/NO_SESSION 属正常业务结果，照常走既有拒绝分支（降级口只认不可用不认业务结果）

AND 已轮换/已吊销 refresh 的延迟裁决半径受 refresh TTL 与故障期时长自限，恢复后首次刷新即按存量 jti 正常裁决

#### Scenario: 登出与全端下线失败 TTL 自愈 (harden-token-store-redis-degradation)

GIVEN 登出黑名单写、会话删除或全端下线（bump 会话版本）因 Redis 异常失败

THEN 登出/改密/重置密码主流程照常成功返回（DB 写不受影响、事务不回滚）

AND 吊销延迟生效：旧 access 至多再活剩余寿命（≤30min）自限；旧 refresh 至 refresh TTL（7d）自限，恢复后可显式重试清理或由管理员重新踢人

AND 记录 ERROR 日志，降级计数器按触点 tag 递增（logout_blacklist / logout_refresh_remove / invalidate_sessions）

#### Scenario: 正确性底座与硬依赖面不降级 (harden-token-store-redis-degradation)

GIVEN 凭据校验（DB BCrypt）、JWT 签名/有效期校验、账号状态检查（DB）是认证正确性底座

THEN 上述校验零降级（失败照常拒绝）

AND 找回密码验证码存储与消费（auth:reset: 族）维持 runbook 硬依赖口径零改动

AND 拦截器请求时校验既有降级（黑名单/会话版本检查）零改动

#### Scenario: 降级计数与告警映射同源 (harden-token-store-redis-degradation)

GIVEN 仓库已有 `exam.auth.degraded`（tag `check`）与 AuthDegraded 告警先例

WHEN 签发续期面发生降级

THEN 复用同一计数指标（tag 追加六个签发续期侧代码常量，基数有界），零新指标零新告警资产

AND 不新增第二套降级哲学

#### Scenario: 变异击红 (harden-token-store-redis-degradation)

GIVEN 还原为无容错旧形态（Redis 异常直接上抛）

WHEN 运行签发续期降级单测

THEN 以确定性断言失败（先红后绿留证；等价变异如实登记不强杀）

# spec-delta：reliability（Redis 依赖口径与故障 runbook）

## 变更类型

ADDED（新增 Requirement「Redis 依赖口径与故障 runbook」，四 Scenario；既有 Requirement 与 Scenario 零改动）

### Requirement: Redis 依赖口径与故障 runbook

WHEN 运维或开发需要回答「Redis 故障时系统各面如何表现、如何恢复」,

系统 SHALL 以 canonical runbook 文档承载裁决口径、键族降级矩阵、告警映射与恢复流程, AND SHALL 使每个降级计数指标都有对应告警规则。

#### Scenario: 键族降级矩阵文档化且与代码对齐 (add-redis-dependency-runbook)

GIVEN 仓库存在多族 Redis 键（会话黑名单/版本、登录守卫、限流、交卷锁、行为计数、在线状态、错题判定缓存、草稿、令牌签发续期、找回码、Spring Cache 快照与详情）

WHEN 查阅 docs/redis-dependency-runbook.md 的键族矩阵

THEN 每族故障行为（降级放行/fail-open/既有降级/硬依赖失败）与代码实态一致

AND 硬依赖面（草稿暂存、令牌签发与续期、Spring Cache 快照与详情、找回验证码）如实标注为硬依赖，不得写成已降级

#### Scenario: 降级计数告警映射完整 (add-redis-dependency-runbook)

GIVEN 仓库已有 exam.ratelimit.degraded / exam.auth.degraded 降级计数告警先例

WHEN 出现新的降级计数指标（如 D1 的 exam.submit.lock.degraded）

THEN 同族收口卡 SHALL 补齐对应告警规则（SubmitLockDegraded 照抄 AuthDegraded 形态）

AND AlertAssetsTest 以 EXPECTED_ALERTS 集合断言与 known 指标名断言守护规则与代码不漂移

#### Scenario: 恢复流程与学生端指引文档化 (add-redis-dependency-runbook)

GIVEN Redis 故障恢复后各降级面按键族 TTL 自愈、交卷对账扫描自动补发

WHEN 查阅 runbook §四

THEN 恢复流程按「确认告警 → 恢复实例（不清数据）→ 观察自愈 → 复核降级半径申诉」给出可执行步骤

AND 故障期学生端行为指引（不刷新以防草稿丢失、交卷口径、新登录失败预期、找回密码不可用）与矩阵口径一致

#### Scenario: 口径变更须走变更链 (add-redis-dependency-runbook)

GIVEN runbook 是 Redis 依赖口径的 canonical 事实源

WHEN 任何触碰 Redis 调用点的变更（新增键族/降级/容错）

THEN 先读 runbook 并在其矩阵中登记新键族行为

AND 新降级计数指标必须有对应告警规则并过 AlertAssetsTest 守护

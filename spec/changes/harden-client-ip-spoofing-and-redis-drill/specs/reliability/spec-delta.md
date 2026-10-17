# reliability 规范变更

## ADDED Requirements

### Requirement: Redis 依赖降级运行态演练证据与台账

系统 SHALL 在演练环境下实测检验 Redis 故障与恢复场景，验证核心链路降级、硬依赖阻断以及自动自愈能力，形成正式演练台账留档。

#### Scenario: Redis 停机全口径降级实测与台账留档 (harden-client-ip-spoofing-and-redis-drill)

GIVEN Redis 实例不可用（模拟宕机或网络中断）
WHEN 客户端执行登录、拉卷、交卷等已降级业务请求
THEN 核心流程 SHALL 正常返回成功，降级计数器递增，日志输出明确降级标记
AND 客户端执行草稿保存、验证码找回等硬依赖请求时，系统 SHALL 诚实返回 500 异常
AND 当 Redis 恢复后，系统 SHALL 自动恢复重连，降级计数停止增长，演练全流程证据沉淀至 `docs/redis-drill-evidence.md`

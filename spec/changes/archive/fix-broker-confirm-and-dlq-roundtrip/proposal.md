# 提案：发布确认作用域修复与 DLQ 真往返验证（前后端优化组合·提案⑦，P2）

## Why

两处同源的「测试绿但真环境坏」（遗留 #10 / #6）：

1. `ExamSubmitSender` 在 `RabbitTemplate.invoke()` 作用域**外**调用 `waitForConfirmsOrDie`——真 dev 实例启动即抛 `IllegalStateException`，启动期「答案补发对账」实际未补发成功（日志「待补=2 已补=0」）。根因：测试环境 RabbitMQ 是 mock 且 `auto-startup: false`，这条路径**从未在真 broker 下跑过**（与遗留 #6 同源）；
2. DLQ 的指标/告警/重投服务在阶段 14 已交付，但「真发一条坏消息 → 真进 DLQ → 真重投」的端到端往返从未验证（阶段 16 观测验证时已明确 firing ≠ 重投闭环，不得据此声称遗留 #6 完成）。

**前置**：无业务前置；真跑需要真 dev 实例（MySQL + RabbitMQ + Redis）。属后端独立立项，不塞进前端阶段 19–23 串行链。

## What Changes

1. 修 `ExamSubmitSender` 的 publisher confirm 用法：`waitForConfirmsOrDie` 移入 `RabbitTemplate.invoke()` 作用域（或等价正确用法），保持既有超时与失败日志/指标行为；
2. 真 dev 实例启动验证：启动期答案补发对账正常执行、日志无 `IllegalStateException`、待补发消息实际送达；
3. DLQ 端到端真往返：真发一条必死消息 → 确认进 DLQ（`exam.mq.dlq.depth` / `exam.mq.dlq.entered` 指标变化）→ 经 `DlqReplayService` 重投 → 消费成功或留档，全程真 broker 无 mock；
4. 补回归护栏：用单测或架构手段守住「confirm 类调用必须处于 invoke 作用域」这类 **mock 测不出**的违规，防同类回归；
5. 验证记录（实际执行的命令 + 该次原始输出 + 当时短 revision）入 `docs/**`；结果登记回遗留清单（收口 #6/#10）。

## Impact

### 受影响的规范
- `spec/specs/reliability/spec.md` — ADDED：发布确认作用域与死信真往返。
- `spec/specs/observability/spec.md` 不动（其「死信队列的指标与告警覆盖」Requirement 已合入，本变更是可靠性/正确性收口，不改观测语义）。

### 受影响的文件
- `src/main/java/com/exam/submission/mq/ExamSubmitSender.java`
- `src/test/**`（新增回归测试；surefire 计数只增不减）
- `docs/**` 验证记录

### 需要迁移
- [ ] 数据库迁移
- [ ] 业务 API

## 时间线评估

中：约 2 天（修复 + 单测 0.5–1 天，真环境验证 1 天，护栏 0.5 天）。

## 风险

- confirm 用法改动须保持既有失败语义：补发对账失败仍要有日志与指标，**不静默**；
- DLQ 验证用的坏消息须可控、验证后清理，不污染 dev 数据；重投走既有 ADMIN 路径，不新造重投机制；
- mock 环境测不出的正是本变更要堵的口子：护栏测试本身须能在**无真 broker 的 CI** 里跑（静态/结构断言），真往返验证留在真 dev 实例做并留档；
- 验证中发现**新的**独立缺陷：停下回报另立项，不顺手修。

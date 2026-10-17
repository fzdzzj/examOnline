# 规范增量：exam-taking

## MODIFIED Requirement: 开考提醒行级幂等投递（追加 AND 条款）

> 合入注记：由 fix-reservation-remind-transaction 追加（2026-10-17）。背景：`deliverIfAbsent` 的 `@Transactional` 是自调用死注解（唯一调用点为本类 `remind()` 的 `this.deliverIfAbsent`，`remind` 由 `@Scheduled`/`@EventListener` 无事务直入）——认领回填与消息插入各自 auto-commit，消息插入瞬时失败时认领已提交、`reminded_time` 非空使后续扫描永不再扫到该行，提醒永久丢失不可自愈。修复：改 TransactionTemplate 编程式事务（不依赖调用路径），单条投递的认领 + 写消息两写一个事务；幂等口径经确认无重投风险（`user_messages` 无唯一键，幂等完全由认领闸承担——认领与消息同生共死，重试重新认领恰投一条）。

#### Scenario: 开考提醒行级幂等投递（既有 Scenario 追加一条 AND，既有条款零改写）

- **AND**: 单条预约的投递（认领回填 + 消息插入）SHALL 在同一数据库事务内完成，且事务 SHALL 以不依赖 Spring 代理调用路径的方式生效（定时/启动入口的同类自调用不得绕过事务边界）——消息插入失败时认领回填一并回滚（`reminded_time` 回到 NULL），由下一轮扫描按行级幂等语义重试补偿，重试不产生重复投递

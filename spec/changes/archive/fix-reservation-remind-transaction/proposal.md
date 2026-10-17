# 变更提案：fix-reservation-remind-transaction

## 为什么（Why）

2026-10-16/17 后端横切首扫中危发现 M1（主 Agent 亲验，发现台账 `docs/功能待办清单.md` B.2 L95 勘正行 + 中 4 列表）：

- `ReservationRemindService.deliverIfAbsent`（L106）的 `@Transactional(rollbackFor = Exception.class)` 是**死注解**：唯一调用点是本类 `remind()`（L93）的 `this.deliverIfAbsent(...)` **同类自调用**——`remind` 自身无事务，由 `@Scheduled`（定时扫表）与 `@EventListener(ApplicationReadyEvent)`（启动补偿）无事务直入。Spring 的注解式事务只在请求经过代理时生效，自调用绕过代理，注解在生产定时路径**静默失效**（与 fix-exam-state-machine-transaction 同机理，且同被"单测经代理调用掩盖"的形态掩盖）。
- 后果：认领回填（`markRemindedIfNull` 的 `UPDATE ... WHERE reminded_time IS NULL`）与消息插入（`user_messages`）各自 auto-commit。**消息插入瞬时失败（DB 抖动等）时认领已提交**——`reminded_time` 非空使后续所有扫描（`reminded_time IS NULL` 过滤）永不再扫到该行，且消息未落库：**该提醒永久丢失，不可自愈**。

## 幂等口径先行确认（提案期完成，修复不以吞掉风险为代价）

- `user_messages` **无唯一键**（仅 `PRIMARY KEY (id)` + `idx_user_messages_user (user_id, id)` 普通索引）——消息插入本身不幂等；投递幂等完全由 `markRemindedIfNull` 认领闸承担（影响行数=1 才写消息）。
- **事务化后无重投风险**：认领回填与消息插入同一事务、同生共死——插入失败则认领一并回滚（`reminded_time` 回到 NULL），下一轮扫描按既有幂等语义重新认领、重投恰好一条；不存在"认领在而消息不在"或"重复投递"窗口。重试补偿依赖的恰是认领闸本身，不是消息表唯一键。
- **多实例并发口径不变**：事务化后认领 UPDATE 的行锁持有至本事务提交（原来 auto-commit 立即释放），竞争实例在提交后重评估 `WHERE reminded_time IS NULL` 影响行数为 0，照常静默跳过——与现行形态同结果，恰一条消息。
- 反差结论：现行**缺陷形态**才是丢消息的根源（认领提交而消息丢失，无任何自愈路径）；修复形态把"丢失"改为"当轮回滚 + 下轮自愈"，幂等语义零变化。

## 改什么（What）

1. `deliverIfAbsent` 改**编程式事务**：构造注入 `PlatformTransactionManager`（与 `ExamStateMachineService` / `RetentionService` / `QuestionImportExportService` / `UserImportExportService` 同款先例），方法体包入 `TransactionTemplate.execute`；**移除死注解**并在 javadoc 注明自调用绕代理原因（勿改回注解）。
2. 事务边界 = **单条投递的认领 + 写消息两写一个事务**（与先例"整轮一事务"刻意不同）：插入失败仅回滚本行认领，当轮其他行照常投递——某一行永久性失败（如数据超长）不阻塞同轮其余投递（毒丸防护），重试补偿半径最小（只重投失败行）；逐行投递循环、`remind()` 返回值、日志口径、`@Scheduled`/启动补偿调用点零改动。
3. 幂等语义零变化：认领闸（`markRemindedIfNull` 影响行数=1 才写消息）与"影响行数=0 静默跳过"逐字不动。

## 影响面（Impact）

- 单类（`ReservationRemindService`）+ 测试；零 DDL（schema.sql 零 diff）、零契约变更（openapi paths 97 不变）、零前端（frontend 零 diff）。
- 既有不变式保全：行级幂等语义不变（exam-taking 基线「开考提醒行级幂等投递」Scenario 四条 AND 全部保持）；多实例竞争恰一条消息不变；扫描间隔/启动补偿不变。
- 门禁基线：仅 backendTests 预期增长；S 级。

## 技术注记

- `TransactionTemplate` 默认仅 RuntimeException/Error 回滚；本路径异常均为运行时异常（MyBatis `DataAccessException` 系），无受检异常路径，与原 `rollbackFor = Exception.class` 意图等价。
- 修复选型否决"整轮一事务"（毒丸风险：单行永久失败阻塞全轮）与"自注入代理/拆独立 bean"（自引用坏味道/重构面更大）；编程式事务对齐仓内四处既有惯例且永久免疫调用路径问题。

## 任务阶段

- 阶段 0 & 1：立项三件套与在途总账登记（同笔勘正 B.2 L95 勘正行——本卡即该勘正所指案件）。
- 阶段 2：实施 + 单测先红后绿（注入消息插入故障：修复前认领残留即缺陷存在性证明）+ 变异击红留证（还原死注解形态）。
- 阶段 3：全量门禁实测、基线上调、规范合入、归档、`--no-ff` 合并入 main。

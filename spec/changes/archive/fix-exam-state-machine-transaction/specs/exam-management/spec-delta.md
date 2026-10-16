# 规范增量：exam-management

## MODIFIED Requirement: 考试状态机（追加 Scenario）

> 合入注记：由 fix-exam-state-machine-transaction 追加（2026-10-16）。背景：`@Scheduled`/`@EventListener` 入口对 `autoAdvance` 的同类自调用绕过 Spring 代理，原 `@Transactional` 在生产定时路径不生效（测试经代理调用掩盖）——CAS 与缺考标记各自提交，缺考漏标不可自愈。修复：`autoAdvance` 改 TransactionTemplate 编程式事务（不依赖调用路径），缓存清除移至提交后。

### ADDED Scenario: 定时流转与缺考标记同事务

- **WHEN**: 定时扫表或启动补偿触发自动流转（未开始→进行中、进行中→已结束批量迁移）
- **THEN**: 当轮全部状态迁移 CAS 与缺考标记（`markAbsence`）SHALL 在同一事务内——任一 `markAbsence` 失败时当轮 CAS 全部回滚，受影响考试保持原状态，由下一轮扫表重试补偿（CAS 幂等 + `exam_absence` 唯一索引 INSERT IGNORE 幂等，重试不产生重复数据）
- **AND**: 事务 SHALL 以不依赖 Spring 代理调用路径的方式生效——定时/启动入口的同类自调用不得绕过事务边界
- **AND**: 详情缓存清除（`CACHE_EXAM_DETAIL`，H-04）SHALL 在事务提交之后执行，杜绝 clear 与 commit 之间并发读回填旧状态的窗口

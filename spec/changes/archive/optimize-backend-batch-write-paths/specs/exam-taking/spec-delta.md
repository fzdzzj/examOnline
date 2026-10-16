# 规范增量：exam-taking

## MODIFIED Scenario: 开考提醒行级幂等投递

原 Scenario（基线「考试预约与开考提醒」Requirement）语义保全声明：行级幂等核心（`markRemindedIfNull` 单行原子 claim，影响行数=1 才写消息；重复扫描/重启/多实例竞争均不重发）、事务边界、扫描间隔、启动补偿全部不变。本笔仅修改候选查询方式：

- **WHEN**: 每轮提醒扫描发现多场候选考试
- **THEN**: 未投递提醒的预约行 SHALL 以一次 `IN (examIds)` + `reminded_time IS NULL` 批量查询取回，内存按 examId 分组，不得逐场循环查询（N+1）
- **AND**: 后续逐行投递链路（claim + 写消息）SHALL 逐字保持行级幂等语义不变

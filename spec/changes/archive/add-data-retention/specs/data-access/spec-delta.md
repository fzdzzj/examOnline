# 规范差异：data-access（数据保留策略与清理边界）

本文件包含对 `spec/specs/data-access/spec.md` 的规范变更（数据保留策略与清理边界，新增）。

## ADDED Requirements

### Requirement: 数据保留策略

WHEN 系统长期运行并持续产生诊断性与幂等性辅助数据,

系统 SHALL 提供按**业务生命周期**（而非单纯数据年龄）驱动的保留策略，且 SHALL 保证清理动作默认不发生、发生时可见、且不越界到业务事实。

#### Scenario: 按考试终结状态确定清理范围

GIVEN 一场考试尚未终结

WHEN 清理任务运行

THEN 该考试关联的辅助数据不被清理

#### Scenario: 清理只触达辅助数据

GIVEN 一场早已终结的考试

WHEN 清理任务运行

THEN 仅清理诊断性与幂等性辅助数据

AND 答卷、成绩、考试快照、班级归属等业务事实一律不被清理

#### Scenario: 清理默认不发生

GIVEN 系统以默认配置启动

WHEN 清理任务被调度

THEN 不删除任何数据

AND 仅在被显式开启后才可能发生删除

#### Scenario: 可先试算再删除

GIVEN 需要评估清理策略的影响

WHEN 以试算模式运行

THEN 系统报告将被清理的候选行数

AND 实际不删除任何一行

#### Scenario: 删除有界

GIVEN 待清理的数据量远大于单次处理上限

WHEN 清理任务运行

THEN 单次运行处理量受行数与批数上限约束

AND 不产生长时间持锁的超大事务

#### Scenario: 清理可观测

GIVEN 清理任务运行完成

WHEN 查看指标

THEN 候选行数与实际删除行数分别可采集

AND 试算模式下删除行数恒为零

#### Scenario: 清理不因单点异常而整体停摆

GIVEN 某一场考试的数据清理失败

WHEN 清理任务继续运行

THEN 其余考试的清理照常进行

AND 失败被记录并可追溯

### Requirement: 清理不得依赖新增索引

WHEN 实现按生命周期驱动的清理,

系统 SHALL 使删除条件**只用已存在的索引**即可高效执行，且 SHALL NOT 为清理引入全表扫描或新增索引。

#### Scenario: 删除条件命中既有索引

GIVEN 辅助表的既有索引最左前缀为考试维度

WHEN 清理按考试维度删除

THEN 删除走既有索引

AND 不需要新增索引或结构变更

#### Scenario: 拒绝全表扫描式删除

GIVEN 辅助表的时间列没有索引

WHEN 选择删除条件

THEN 不以无索引的时间列作为全局删除条件

AND 该取舍被记录在案

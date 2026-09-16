# data-access 规范

> 能力域：数据访问（阶段 8 + 15，W9-W10 / W15）。
> 来源：`spec/changes/archive/add-performance-deepening` 合入（读写分离、读己之写）；
> `spec/changes/archive/add-data-retention` 合入（数据保留策略与清理边界）。
> 实施注记：
> - 强一致读（答卷详情、成绩查询）**不标** `@DS("slave")`，走默认主库；`@DS("slave")` 只挂在可容忍主从延迟的快照类读上（`PaperSnapshotService`/`ExamSnapshotService`/`GradingQueryService`/`ScoreService` 等）。
> - 数据保留（`RetentionService`，f42adba）：仅三张辅助表 `exam_behavior_logs` / `exam_submit_dedups` / `score_audit_logs`；删除条件只带 `exam_id`（命中既有索引最左前缀）；**零 DDL**；默认 `enabled=false` + `dry-run=true`；**不纳入** `exam_dlq_messages`（表存在但无 `exam_id`）；用 `end_time` 不用 `updated_time`；**不声称磁盘释放**。单次运行另有 `max-exams-per-run`（默认 100）与候选 `ORDER BY end_time ASC, id ASC` 有界。

## Requirements

### Requirement: 读写分离

WHEN 系统访问数据库,

系统 SHALL 写操作走主库、读操作走从库，并 SHALL 提供按注解切换数据源的能力。

#### Scenario: 写走主库

GIVEN 一个写操作（交卷/发布/批改）

WHEN 系统执行

THEN 路由到主库

#### Scenario: 读走从库

GIVEN 一个非强一致的读操作（快照查询）

WHEN 系统执行

THEN 路由到从库

---

### Requirement: 读己之写

WHEN 写操作后立即读取,

系统 SHALL 使该读取强制走主库，避免主从延迟读到旧数据。

#### Scenario: 刚交卷立即查询

GIVEN 学生刚交卷

WHEN 随即查询答卷

THEN 系统强制路由主库

AND 返回最新交卷结果

#### Scenario: 写后标记窗口

GIVEN 写操作完成后

WHEN 短时间窗口内的读请求

THEN 该请求路由主库

AND 窗口过后读请求可回从库

---

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

THEN 仅清理诊断性与幂等性辅助数据（实施范围：`exam_behavior_logs` / `exam_submit_dedups` / `score_audit_logs`，按 `exam_id` 分批删除）

AND 答卷、成绩、考试快照、班级归属等业务事实一律不被清理

AND `exam_dlq_messages` 因无 `exam_id`、无法按考试生命周期有界清理，明确不纳入

#### Scenario: 清理默认不发生

GIVEN 系统以默认配置启动

WHEN 清理任务被调度

THEN 不删除任何数据（`enabled=false` 且 `dry-run=true` 双关默认）

AND 仅在被显式开启后才可能发生删除

#### Scenario: 可先试算再删除

GIVEN 需要评估清理策略的影响

WHEN 以试算模式运行

THEN 系统报告将被清理的候选行数

AND 实际不删除任何一行

#### Scenario: 删除有界

GIVEN 待清理的数据量远大于单次处理上限

WHEN 清理任务运行

THEN 单次运行处理量受行数、批数与候选考试数上限约束（含 `max-exams-per-run`，候选按 `end_time,id` 优先最老）

AND 不产生长时间持锁的超大事务

#### Scenario: 清理可观测

GIVEN 清理任务运行完成

WHEN 查看指标

THEN 候选行数与实际删除行数分别可采集（`exam.retention.rows`，tag `table` + `action`）

AND 试算模式下删除行数恒为零

#### Scenario: 清理不因单点异常而整体停摆

GIVEN 某一场考试的数据清理失败

WHEN 清理任务继续运行

THEN 其余考试的清理照常进行

AND 失败被记录并可追溯

#### Scenario: 终结时刻代理用 end_time

GIVEN `exams` 表没有独立的 `ended_time` 列，且 `updated_time` 会被任意更新刷新

WHEN 判定考试是否已超过保留窗口

THEN 使用 `end_time`（时间窗终点）作为终结代理

AND 误差方向为晚删而非早删（不声称磁盘释放）

---

### Requirement: 清理不得依赖新增索引

WHEN 实现按生命周期驱动的清理,

系统 SHALL 使删除条件**只用已存在的索引**即可高效执行，且 SHALL NOT 为清理引入全表扫描或新增索引（零 DDL）。

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

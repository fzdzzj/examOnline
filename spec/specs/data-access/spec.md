# data-access 规范

> 能力域：数据访问（阶段 8，W9-W10）。
> 来源：`spec/changes/archive/add-performance-deepening` 合入（读写分离、读己之写）。
> 实施注记：强一致读（答卷详情、成绩查询）**不标** `@DS("slave")`，走默认主库；`@DS("slave")` 只挂在可容忍主从延迟的快照类读上（`PaperSnapshotService`/`ExamSnapshotService`/`GradingQueryService`/`ScoreService` 等）。

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

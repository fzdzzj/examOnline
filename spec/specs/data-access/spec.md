# data-access 规范

> 能力域：数据访问（阶段 8 + 15 + 17，W9-W10 / W15 / W16；2026-09-20 补动态条件与索引核查）。
> 来源：`spec/changes/archive/add-performance-deepening` 合入（读写分离、读己之写）；
> `spec/changes/archive/add-data-retention` 合入（数据保留策略与清理边界）；
> `spec/changes/archive/fix-schema-mysql-pk` 合入（新库建表 MySQL 8 兼容、AUTO_INCREMENT 必须有主键，83bc9ca）；
> `spec/changes/archive/optimize-sql-performance` 合入（动态条件不得拼接、空集合必须短路、索引变更先行核查）。
> 2026-09-21 直补（非变更提案，240c2d2 与其后续）：「周期扫描的代价由结果集决定而非表大小」
> 「两端共用的 DDL 必须落在共同语法子集内」两条，以及下面 optimize-sql-performance 那条注记的更正。
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

---

### Requirement: 新库建表可在 MySQL 8 执行

WHEN 用正式建表脚本在空的 MySQL 8 实例初始化数据库,

系统 SHALL 使脚本执行成功并建出全部业务表，且 SHALL 与测试所用建表脚本为同一份。

#### Scenario: 空库可建全

GIVEN 一份空的 MySQL 8 库

WHEN 执行 schema.sql

THEN 全部表创建成功

AND 不因 AUTO_INCREMENT 未定义为 key 而失败

#### Scenario: 测试与新库同源

GIVEN 集成测试通过加载 schema.sql 建表

WHEN 新环境使用同一文件

THEN 不会出现「测试绿、MySQL 空库建不起来」

---

### Requirement: AUTO_INCREMENT 列必须有主键

WHEN 在建表定义中使用 AUTO_INCREMENT,

系统 SHALL 同时声明 PRIMARY KEY（或等价的 key），且 SHALL 用自动化手段防止回归。

#### Scenario: 每张自增表都有主键

GIVEN schema.sql 中任意一张含 AUTO_INCREMENT 的表

WHEN 检查其建表块

THEN 同一块内存在 PRIMARY KEY

AND 该性质由测试守住

#### Scenario: 存量库可补主键

GIVEN 已存在但缺少主键的表

WHEN 执行对应迁移脚本

THEN 为 id 补上 PRIMARY KEY

AND 若主键已存在，重复执行失败可忽略且不改业务数据

---

### Requirement: 动态条件不得拼接 SQL 字面量

WHEN 依据运行时集合构造查询条件,

系统 SHALL 走参数化绑定，不把集合元素拼进 SQL 片段。

#### Scenario: 集合条件参数化

GIVEN 一组标签 ID 用于筛选题目

WHEN 构造"命中任一标签"的条件

THEN 以参数化 `IN` 绑定，不出现字符串拼接的 SQL 片段

#### Scenario: 空集合必须短路

GIVEN 参数化子查询返回空集合

WHEN 该集合要被交给 `in()` 作为条件

THEN 必须先短路为"零候选"并走既有的数据不足分支

AND 不得把空集合交给 `in()`——会拼出 `id IN ()` 触发数据库语法错误（表现为 500）；
部分 ORM 版本还会跳过该条件，变成"过滤形同不存在"的静默错误结果

---

### Requirement: 索引变更先行核查

WHEN 为查询新增索引,

系统 SHALL 先核对该查询所需列组合是否已被既有索引覆盖，避免新增重复索引。

#### Scenario: 重复索引被拦下

GIVEN 拟新增的复合索引与既有索引列组合完全相同

WHEN 评审该变更

THEN 判定为重复索引，不新增

AND 只记录写放大成本而无读取收益

---

### Requirement: 周期扫描的代价由结果集决定而非表大小

WHEN 一条查询被定时任务按秒级周期反复执行,

系统 SHALL 保证其执行代价与**命中行数**相关，而非与被扫描区间行数相关。

#### Scenario: 空结果也要付全表的钱，就不合格

GIVEN 一条稳态下恒返回 0 行的扫描查询（如对账"未落库"行）

WHEN 其谓词无法被任何索引定位

THEN 数据库必须读穿整个候选区间才敢返回空集

AND 该成本随历史数据线性增长，最终吃掉任务自身的调度预算

#### Scenario: 不可索引的谓词物化为生成列

GIVEN 需要索引的判据落在 LONGTEXT 上（如 `answers IS NULL`），MySQL 只能建前缀索引

WHEN 设计该索引

THEN 改用一个由该列推导的生成列并索引它，而不是把判据留在扫描里

AND 生成列的取值由其依赖列算出，与依赖列不可能不一致，也不需要任何代码路径负责维护

### Requirement: 两端共用的 DDL 必须落在共同语法子集内

WHEN 新增的 DDL 需要同时被 MySQL（dev/生产）与 H2（集成测试）执行,

系统 SHALL 只使用两端都能解析的语法；MySQL 专有写法不得进入共用的 `schema.sql`。

#### Scenario: 前缀索引不进共用 DDL

GIVEN 拟写入 `schema.sql` 的索引使用了 `col(n)` 前缀语法

WHEN H2 解析该建表语句

THEN 直接抛语法错误，且因 `continue-on-error=false` 导致全部集成测试无法启动

AND 改用生成列 + 普通索引等等价的可移植写法

#### Scenario: 索引提示用注释形态

GIVEN 需要在 SQL 里钉住执行计划

WHEN 该 SQL 同时跑在两端

THEN 使用优化器提示注释，不使用 `FORCE INDEX`（H2 不认其语法）

---

> 合入注记（2026-09-20，`optimize-sql-performance`）：只合入上面两条（抽题标签筛选已参数化，
> 且空命中短路已修复并有回归测试）。**未合入**其"OR 条件改 UNION ALL"——提案附带的
> "1 万 800ms→50ms / 10 万 5.2s→120ms"数字系撰写时虚构、从未实测，本机主库容器当前 exited，
> 拿不到真实执行计划；在无测量证据下改写一条仅有功能校验的兜底查询，风险大于收益。
> 其"新建 `idx_sweep_candidate`"经核实与既有 `idx_submissions_sweep(status, deadline_time)`
> 列组合完全相同，属重复索引，对应的一次性迁移脚本已删除（本项目未接 Flyway，该脚本从未执行）。
>
> 更正（2026-09-21，240c2d2 / 本次）：上面"主库容器 exited 拿不到执行计划"的前提已解除
> （`docker start exam-mysql-master`，端口 13316）。在 10 万答卷的独立 scratch 库上实测后，
> **UNION ALL 主张被否证**：每个测点都不比现状快、多数慢 2–17 倍（派生表须先物化全部命中行
> 才套得上 `LIMIT`，恰好摧毁原查询"沿索引边扫边提前停止"的性质），且不写互斥守卫会多返回
> 36% 重复行。真正的问题是优化器翻驱动表，已由 `JOIN_INDEX` 提示解决（常态忙轮 41–50ms →
> 1.7–2.7ms）；"周期扫描"与"共用 DDL"两条 Requirement 即由此而来，数字见
> `spec/changes/IMPLEMENTATION_STATUS.md`。

# data-access 规范

> 能力域：数据访问（阶段 8 + 15 + 17，W9-W10 / W15 / W16；2026-09-20 补动态条件与索引核查）。
> 来源：`spec/changes/archive/add-performance-deepening` 合入（读写分离、读己之写）；
> `spec/changes/archive/add-data-retention` 合入（数据保留策略与清理边界）；
> `spec/changes/archive/fix-schema-mysql-pk` 合入（新库建表 MySQL 8 兼容、AUTO_INCREMENT 必须有主键，83bc9ca）；
> `spec/changes/archive/optimize-sql-performance` 合入（动态条件不得拼接、空集合必须短路、索引变更先行核查）。
> `spec/changes/archive/project-scalar-only-submission-reads` 合入（标量读取的列投影；**仅 GO 站点实施**——学生考试列表与缺考标记，其余三个站点按「不稳定/无净收益」未实施，逐站点裁决与验收边界见尾部注记）。
> `spec/changes/archive/attribute-my-exams-list-index` 合入（学生考试列表答卷渠道索引；真 MySQL 8 容器实测裁决 GO，实施 A1 臂 idx_submissions_student；补考渠道 C1 因基线未达 5ms 代价门槛未采纳；详细裁决见尾部注记）。
> 2026-09-21 直补（非变更提案，240c2d2 与其后续）：「周期扫描的代价由结果集决定而非表大小」
> 「两端共用的 DDL 必须落在共同语法子集内」两条，以及下面 optimize-sql-performance 那条注记的更正。
> 同批（36ae7ca 直补）：「清理的删除条件必须索引可达」——原名"清理不得依赖新增索引"，
> 与「无生命周期键的表须按年龄清理」互斥（两条同时成立等于宣布这类表永远不可清理），
> 故改写为以实测代价为判据；「数据保留策略」新增 audit_log 按自身窗口清理一条。
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

#### Scenario: 无业务生命周期键的审计表按自身窗口清理

GIVEN `audit_log` 记录登录与账户锁定等安全事件，没有 `exam_id`，不参与考试生命周期

WHEN 清理任务运行

THEN 它按自身保留窗口（`audit-log.retention-days`，独立于前三张表）只按年龄有界清理

AND 该窗口默认值只是"给增长一个上界"的占位口径，真实数值属合规决策，须确认后才改

AND 确认前由 `enabled=false` + `dry-run=true` 两道默认闸保证不会静默删掉审计证据

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

### Requirement: 清理的删除条件必须索引可达

WHEN 实现清理的删除条件,

系统 SHALL 使该条件命中索引、其执行代价与**待删行数**相关而非与**表大小**相关；
SHALL NOT 以无索引的时间列作为删除条件；新增一条索引只有在它能把上述代价从
"与表大小相关"降到"与结果集相关"时才被允许。

#### Scenario: 既有索引即可命中时不新增

GIVEN 辅助表的既有索引最左前缀为考试维度

WHEN 清理按考试维度删除

THEN 删除走既有索引

AND 不需要结构变更——此处的"零 DDL"是指**不为了清理而重构表结构**，不是禁止一切索引

#### Scenario: 拒绝全表扫描式删除

GIVEN 辅助表的时间列没有索引

WHEN 选择删除条件

THEN 不以无索引的时间列作为全局删除条件

AND 该取舍被记录在案

#### Scenario: 无业务生命周期键的表先补索引再按年龄清理

GIVEN 一张只增不减、且没有考试维度可供有界删除的表（如安全审计 `audit_log`）

WHEN 需要为其引入按年龄的清理

THEN 先补一条时间列索引，再让删除条件走该索引

AND 判据是实测：10 万行时"无可删内容"的稳态在无索引下仍需读穿全表（34ms，随行数线性增长），
     有索引后 0.16–0.7ms 且与表大小无关

AND 原先"不得为清理新增索引"的表述与本场景互斥时，以本场景为准——
     两条同时字面成立等于宣布这类表永远不可清理

---

### Requirement: 新库建表可在 MySQL 8 执行

WHEN 用正式建表脚本在空的 MySQL 8 实例初始化数据库,

系统 SHALL 使脚本执行成功并建出全部业务表，且 SHALL 与测试所用建表脚本为同一份，并 SHALL 有真实 MySQL 8 实例上的执行证据。

#### Scenario: 空库可建全

GIVEN 一份空的 MySQL 8 库

WHEN 执行 schema.sql

THEN 全部表创建成功

AND 不因 AUTO_INCREMENT 未定义为 key 而失败

#### Scenario: 测试与新库同源

GIVEN 集成测试通过加载 schema.sql 建表

WHEN 新环境使用同一文件

THEN 不会出现「测试绿、MySQL 空库建不起来」

#### Scenario: 真机空库初始化实测

GIVEN 真实空 MySQL 8 实例

WHEN 人为执行 schema.sql

THEN 全部业务表建成且无报错

AND 证据含实际执行的命令、该次原始输出与当时短 revision

AND 该证据不得以 H2 结果或文本约定测试替代

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

#### Scenario: 存量迁移真机实测

GIVEN 按迁移前形态构造的真实 MySQL 8 存量库

WHEN 人为执行补主键迁移脚本

THEN 缺主键的表补上 PRIMARY KEY

AND 重复执行的可忽略失败行为与脚本头注声明一致

AND 证据含实际执行的命令与该次原始输出

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

系统 SHALL 先核对该查询所需列组合是否已被既有索引覆盖，避免新增重复索引；且新增性能类索引 SHALL 在真实数据库引擎上以两形状多臂轮转测量进行实测归因，经确定性扫描行数比（收益倍数）与时间侧无回归、热写写放大检验后方可采纳，并 SHALL 遵循最小充分索引优先原则。

#### Scenario: 重复索引被拦下

GIVEN 拟新增的复合索引与既有索引列组合完全相同

WHEN 评审该变更

THEN 判定为重复索引，不新增

AND 只记录写放大成本而无读取收益

#### Scenario: 索引引入须先归因后裁决且最小充分优先

GIVEN 线上查询因结构上缺失打头索引而面临全表扫描风险（如 `exam_submissions` 按 `student_id` 单列查询）

WHEN 评估是否新增索引

THEN 必须先在真实引擎上测量基线代价（事实认定），且仅在基线证实为性能瓶颈（最快轮耗时超过冻结门槛）时才进入候选臂比对

AND 候选索引必须通过确定性扫描行数比（收益倍数）与时间侧无回归检验，并经热写批测量证实写放大在噪声带阈值内

AND 选臂遵循最小充分索引优先原则：单列索引已充分满足时，不得采用包含冗余列的复合索引

AND 若测量表明小表基线耗时未达瓶颈门槛，则该索引不予采用、不增加写放大开销

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

### Requirement: 标量读取的列投影

WHEN 读取路径只消费答卷行（`exam_submissions`）的标量字段,

系统 SHALL 在取该行集时限定 SELECT 列到该路径实际使用的字段，SHALL NOT 载入 `paper_json` / `answers` 等长字段。本条的适用范围是**完成归因且归因为 GO 的站点**（学生考试列表、缺考标记已据此实施；监考总览同类条款见 `spec/specs/anti-cheat/spec.md` 监考大屏 Requirement）；未 GO 站点不实施、不据此判为违规。列投影的收益 SHALL 在真实数据库引擎上以同口径基线的**应传字节**为主判据、以单侧无回归为边界判据先行归因；仅凭静态结构或进程内计时 SHALL NOT 宣称收益，测量环境 SHALL NOT 外推生产性能。投影后的服务输出（响应/生效结果）SHALL 与全列读取逐字段等价（仅由调用时钟决定的字段按调用时刻区间校验），不改变任何用户可见口径。一次只改取数形态这一类：SHALL NOT 顺带修改 SQL 谓词与排序、索引、缓存、JVM、线程池、连接池、MQ 或前端。归因未达标的站点（字节收益不成立，或投影侧时间不稳定/无净收益）或改后语义不等价的站点 SHALL NOT 实施，并如实登记未采用。

#### Scenario: 标量站点以投影取数

GIVEN 某读取路径对答卷行只用标量字段（如学生考试列表、缺考标记、本人成绩、复核调分定位、补考成绩合并）

WHEN 该路径执行取数

THEN SELECT 列表仅含该路径实际使用的列，不含 `paper_json` / `answers`

AND 返回行数、SQL 条数与全列读取相同，谓词与排序不变

#### Scenario: 收益先归因且以字节为主判据

GIVEN 对某标量站点提出列投影优化

WHEN 评估是否实施

THEN 在真实引擎上以同口径两臂（全列 / 投影）+ 等价副本噪声带完成 ≥5 轮轮转测量

AND 判据为：应传字节相对全列下降达到冻结倍数 ∧ 投影臂每轮 wall-clock 不超过全列噪声带上界 ∧ 返回行数与 SQL 条数不变

AND 措辞不混用：字节不达标写「字节收益不成立」，时间侧不达标写「不稳定/无净收益」，不为凑 GO 改用中位数或下调冻结倍数

#### Scenario: 投影后语义逐字段等价

GIVEN 同一隔离数据与同一生产调用链

WHEN 分别以全列与投影取数执行该路径

THEN 两臂的响应/生效结果逐字段相同（仅调用时钟决定的字段按区间校验）

AND 投影返回的实体长字段为 null 而库内该行非 null，且路径真读长字段即失败

#### Scenario: 长字段消费者不受影响

GIVEN 判分、成绩预览、导出个人报告、复核展示、补发扫描等需要 `paper_json` / `answers` 的路径

WHEN 标量站点实施列投影

THEN 上述路径的取数语句不变、仍能读到长字段

AND 不为统一风格把需要长字段的路径一并投影

#### Scenario: 缺少归因或改后不等价

GIVEN 测量不可用、字节收益不达标、投影臂出现回归、语义不等价或改后无净收益

WHEN 评估是否合入本变更

THEN 该站点不实施、不宣称已解决该路径的取数成本，如实登记未采用或回退

AND 逐站点独立裁决，允许部分站点 GO；未 GO 站点的拟实施条款不作为已实现规范合入

---

> 合入注记（2026-09-30，`attribute-my-exams-list-index`，整卡裁决 **GO**，分渠道采用 **submissions=A1, candidates=未采纳**）：判据测量前冻结于该变更 `evidence/PREREGISTRATION.md`（sha256 `1b3a5f0e…2ea9d`），机械裁决脚本 `analyze-index-arms.cjs` 按 B1–B5 算子出裁决。**答卷渠道（T1，`exam_submissions` 按 `student_id` 单列查询）实施 A1 臂单列索引 `idx_submissions_student (student_id)`**：基线 A0 为全表扫且耗时远超门槛（B1 过）；A1 臂确定性扫描行数比大幅缩减（n1 下比值在数百倍区间、n2 下比值在数千倍区间，远超下界要求，B2 过）；单次耗时降至亚毫秒级且无单轮回归（B3 过）；热写批耗时均在基线噪声带上界内，未见写放大（B4 过）；选臂遵循最小充分原则（A1 与 A2 读端收益等价，单列 A1 维护成本低于复合索引 A2）。**补考渠道（T2，`exam_candidates` 按 `student_id` 单列查询）C1 未采纳**：基线 C0 最快轮耗时低于瓶颈门槛（亚毫秒级），小表场景无需增建索引增加写负担；按分渠道 gate 规则，C0 不满足瓶颈门槛只否决 C1 采纳，不否决整卡。实施提交为 `02d933e`，仓库根全量门禁已通过且测试用例全部正常执行无失败。**验收边界＝一次性本地 MySQL 8 容器（tmpfs 数据目录、高位端口、独立库，用毕 `docker rm -f` 销毁）+ 单机 + 空并发串行测量 + H2 测试上下文——不外推生产参数、真实数据量与并发负载。**

> 合入注记（2026-09-29，`project-scalar-only-submission-reads`，逐站点裁决 **GO 2/5**）：判据测量前冻结于该变更 `evidence/PREREGISTRATION.md`（sha256 `3366b1eb…3805`），机械复算脚本按 M1–M3/S1–S4 算子出裁决。**s1 学生考试列表（`myExams`）与 s2 缺考标记（`markAbsence`）GO 并已实施**（`.select(...)` 列投影为 `exam_id,status,deadline_time` 与 `student_id`；应传字节比 780.2/780.24/780.28 与 2516.22/2516.33/2516.33，逐轮 wall-clock 均在 OLD∪OLDrep 噪声带内）；**s3 学生查分、s4 复核同意调分、s5 补考合并取分 NO-GO 未实施**——字节收益成立（≥93 倍）但时间侧「**不稳定/无净收益**」（s3 n=3000、s4 三形状、s5 n=200 各有投影臂单轮超噪声带上界，按冻结算子逐轮判、不挑轮、不取中位数）。`ScalarProjectionGuardTest` 先红后绿（旧实现 `expected: <0> but was: <2>/<1>`，投影后绿）常驻全量门禁；仓库根 `mvnw.cmd clean test` @ `9182a5d` → 325/0/0/1 BUILD SUCCESS 退出码 0。**验收边界＝一次性本地 MySQL 8 容器（tmpfs 数据目录、127.0.0.1 高位端口、独立库，用毕 `docker rm -f` 销毁）+ 单机 + 空并发（逐条语句串行计时）+ H2 测试上下文（等价性在 MockMvc / 进程内直调 Service 层）——不外推生产 MySQL/Tomcat，不构成交卷或监考 P99 结论。**

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

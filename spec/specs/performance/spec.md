# performance 规范

> 能力域：性能（阶段 8，W9-W10；2026-09-23 补交卷容量调整与压测方法学）。
> 来源：`spec/changes/archive/add-performance-deepening` 合入（热点只读缓存、缓存穿透/击穿/雪崩三防）+ `spec/changes/archive/tune-submit-capacity` 合入（交卷容量可由运行参数调整且达标判定可复现）+ `spec/changes/archive/gate-java-change-on-measured-factor` 合入（Java 优化先归因、一次只改一类）+ `spec/changes/archive/update-question-stats-paging-resolve-attribution` 合入（题目统计导出优化先拆分分页与解析）。
> 归并说明：原提案 performance spec-delta 中的「慢查询识别」与 observability spec-delta 的「慢 SQL 识别」是同一需求，**不重复登记**——统一归入 `spec/specs/observability/spec.md`（日志与请求关联属可观测性能力域），本文件不再重复。

## Requirements

### Requirement: 热点只读缓存

WHEN 系统读取试卷快照、考试快照等只读数据,

系统 SHALL 优先从缓存返回，命中则 SHALL 不访问数据库。

#### Scenario: 缓存命中

GIVEN 试卷快照已缓存

WHEN 学生读取该快照

THEN 系统从缓存返回

AND 不访问数据库

#### Scenario: 缓存未命中回源

GIVEN 快照未缓存

WHEN 学生读取

THEN 系统访问数据库取回

AND 回填缓存

---

### Requirement: 缓存穿透防护

WHEN 查询不存在的键,

系统 SHALL 缓存空结果以避免缓存穿透直击数据库。

#### Scenario: 空结果缓存

GIVEN 查询一个不存在的快照 ID

WHEN 系统处理

THEN 缓存空结果（短 TTL）

AND 后续同样查询命中空缓存而非直击 DB

---

### Requirement: 缓存击穿防护

WHEN 热点缓存失效瞬间,

系统 SHALL 以互斥锁保证仅一个线程回源重建，其余 SHALL 等待或返回旧值。

#### Scenario: 单线程重建

GIVEN 热点 key 已失效

AND 多线程同时请求该 key

WHEN 缓存击穿

THEN 仅一个线程回源

AND 其余线程等待或取旧值

---

### Requirement: 缓存雪崩防护

WHEN 大量缓存同时过期,

系统 SHALL 以基础 TTL 加上随机抖动避免集中失效。

#### Scenario: TTL 随机抖动

GIVEN 大量 key 设定相同基础 TTL

WHEN 缓存写入

THEN 每 key 的 TTL 叠加随机抖动

AND 避免同一时刻集体失效

---

> 撤回说明（2026-09-20，`add-thread-pool-isolation`）：本能力域**未**从该提案合入任何需求——
> 其实现经核实为纯空转并已整体删除（`46d7004` 建池 + `@EnableAsync`，`df5f311` 全部移除）。
> 三点事实供后来者避坑：
> ① 兜底扫描与行为采集被 `@Async` 挂在**有返回值且返回值被调用方使用**的方法上，运行期直接抛
> 非法返回类型异常，行为上报接口全量 500；且扫描方法由同 bean 自调用，本就绕过代理，注解永不可能生效。
> ② 摘掉误用后三个池一个租户不剩（异步交卷入口零调用方），"隔离"实际从未发生。
> ③ 断言池参数与"三池互不相同实例"的测试在零租户下依旧全绿——**代理型注解必须验证其真生效**，
> 该规则已登记到 `spec/specs/data-consistency/spec.md`「声明式增强必须真的生效」。

---

### Requirement: 交卷容量可由运行参数调整且达标判定可复现

WHEN 交卷链路的容量参数（如 Tomcat 线程上限）被调整,

系统 SHALL 以可复现的压测方法学验证调整效果，且 SHALL NOT 以单轮或未预热的采样作为容量结论。

#### Scenario: 容量调整前有数字裁决

GIVEN 压测暴露容量瓶颈

WHEN 决定调整并发运行参数

THEN 方案与代价以量化对比为依据（如服务时长与请求速率的容量估算）

AND 不做无数字依据的拍脑袋调参

#### Scenario: 注入不改代码

GIVEN 运行参数可由环境变量注入

WHEN 应用参数调整

THEN 通过框架的宽松绑定机制注入（零代码改动）

AND 改后给出新的容量估算与下一个瓶颈位

#### Scenario: 压测方法学可复现

GIVEN 调整后的配置需复验

WHEN 执行压测

THEN 每臂至少三轮取中位数、每轮前预热、轮间等连接排空

AND 连接池水位在压测期间持续采样（而非突发后快照）

#### Scenario: 达标判定不因未达标而放宽

GIVEN 复验结果

WHEN 判定硬指标

THEN 按既有指标定义判定（P99 / 丢单 / 落库时效）

AND 未达标时如实登记与下一步瓶颈位，不修改指标定义凑数

---

> 合入注记（2026-09-23，`tune-submit-capacity`）：G1 裁决先量化两路线（均值语义=对外承诺降级，
> 否），选提高线程上限；复验又证伪了 N÷T 公式的「T 与 N 无关」前提（线程 400 单独调时
> 服务端均值 460→1224ms），最终选型 = 线程 400 + DB_POOL_MAX=100（池大小依据 3000 次/s ×
> 32ms ≈ 96）。G2 注入经 relaxed binding 环境变量（`SERVER_TOMCAT_THREADS_MAX` /
> `DB_POOL_MAX`），运行期读回生效（200/20 → 400/100）。G4/G5 方法学固化进 `loadtest/`
> 脚本（`run-arm.sh` 一臂 N 轮 + 满规模预热 + TIME_WAIT 排空轮询、`sample-*.csv` 持续采样）。
> **复验结论（口径一字未改）**：0 丢单三臂达标；P99<2s 三臂全不达标（最好 2293ms，中位
> 判定）；批量落库 <30s 仅默认臂达标。新瓶颈位已跳出应用：MySQL Threads_running 19–23→
> 57–103、最差连接持有 2–3s→6.9–7.7s（SQL 在 DB 内排队），且 JMeter/MySQL 与 SUT 同机
> 抢 20 核（system_cpu 峰值 0.976–1.000 而应用仅占 0.43 核）——再调应用参数无进一步收益。
> 证据见 `docs/submit-capacity-tuning-report.md` 与 `loadtest/evidence-sha256.txt`（227 个
> 机器可读产物 sha256 清单）。

> 评估结论注记（2026-09-25，`audit-log-off-critical-path` 归档）：判据 G6 经评估**不移出**，
> 本基线**未合入**该提案的任何 Requirement——其 spec-delta「审计写入不占请求关键路径」按
> 未采用草案随目录归档。要点：audit_log 写入仅登录链路（登录成功/失败、账户锁定），交卷
> 路径不写审计；审计写失败已旁路（catch 吞掉 + ERROR，不回滚业务）；秒级慢 INSERT 仅旧压测
> run2 偶发（4c3b7d7）、修订方法学 9 轮复验（b1fa8a2）未复现，登录时延由 BCrypt CPU 主导。
> 数字与依据见 `spec/changes/archive/audit-log-off-critical-path/tasks.json` 阶段 1 evidence。

---

### Requirement: Java 优化先归因、一次只改一类

WHEN 对 Java 路径做性能优化,

系统 SHALL 先在同一负载下量出延迟、吞吐和资源占用，并确认时间主要耗在业务规则、数据库、远程调用、锁、CPU 或 GC 中的哪一类；每次 SHALL 只改占比最高的一类，并用同一基线验收。调用次数和事务范围没有下降之前，系统 SHALL NOT 先调整 JVM 参数。

#### Scenario: 没有剖面不得改参数

GIVEN 还没有同一负载下的延迟、吞吐和资源剖面

WHEN 准备优化

THEN 先补测量

AND 不修改 JVM、堆、GC 或容器内存参数

#### Scenario: 一次只改一类

GIVEN 剖面显示某一类占比最高

WHEN 实施优化

THEN 该次只改这一类因素

AND 分数、名额、权限和交卷幂等语义保持不变

AND 用同一基线复测；指标没有变化就回到度量，不继续叠加别的参数

#### Scenario: 交卷入口的已知结构不是延迟占比

GIVEN 交卷入口包含 Redis 锁、幂等读写、状态迁移和消息发送，答案落库使用 JDBC batch

WHEN 引用代码结构

THEN 只把它当作调用次数和事务范围的清单

AND 不把它当成已经测得的延迟占比

AND 同机压测的历史结果不能单独授权 JVM 或热点改写

> 合入注记（2026-09-25，`gate-java-change-on-measured-factor`）：本变更只把上述门禁合入基线，
> **未实施** JVM/堆/GC/容器内存、线程池、SQL 或业务代码改动（`src/main` 零触碰），未跑压测，
> 不是交卷 P99 修复。它引用的历史基线是 `docs/submit-capacity-tuning-report.md`
> （`tune-submit-capacity` 同机复验）：同机抢核（压测进程与被测应用共用宿主）、应用 CPU 远非
> 主体而整机饱和、MySQL 在库内排队、线程上限单独调整曾是负优化、线程与连接池同调后 P99 仍
> 不达标——那次结果只证明「应用参数已用尽、约束已跳出应用」，**不能授权 JVM 或热点改写**。
> 交卷入口的 Redis 锁、幂等读写、CAS、MQ 发送与消费端 JDBC batch 只是调用次数与事务范围的
> 清单，不是已测得的延迟占比；近期归档的 `batch-summary-subjective-reads` /
> `batch-grading-subjective-upserts` 只降低教师侧判分/汇总的调用次数，与交卷 P99 无关。
> 下一刀必须先有压测机分离后的同口径新剖面（依赖 `isolate-submit-load-generator`，
> 截至本变更归档仍待审批未实施）。

---

### Requirement: 题目统计导出优化先拆分分页与解析

WHEN 针对题目统计导出的 `pagingResolve` 路径提出代码优化,

系统 SHALL 在相同隔离负载、同一次导出页内先分别记录答卷分页取数与 `resolveQuietly` 的耗时、分配和 SQL 交互；若解析段稳定占主导，SHALL 再将其中主观分读取与答案解析/逐题评分分账，并说明嵌套计时与探针开销；SHALL 先核对导出语义与测量开销，再决定下一次只研究哪一类因素。系统 SHALL NOT 将跨轮、跨窗口的比值或独立分位数差额当成请求内精确占比，亦 SHALL NOT 将隔离 H2 结果宣称为生产 MySQL/Tomcat 瓶颈。

#### Scenario: 可重复的单一因素主导

GIVEN 同一数据和负载已分别记录分页与解析的成本，解析为主导时又记录其主观分读取与解析/评分成本，且生产导出与测量输出语义一致

WHEN 多轮观测中某一因素高于轮间波动并稳定占主导

THEN 该因素仅成为下一份独立优化提案的候选

AND 不在本次归因变更中同时改 SQL、解析业务逻辑或 JVM 参数

#### Scenario: 归因未定或测量改变行为

GIVEN 探针开销不可忽略、坏卷降级与正常页混算、输出不等价，或多轮主导因素反复变化

WHEN 评估是否实施代码优化

THEN 标记归因未定并保留原始证据

AND 不因先前合并相位的分配量较高便指定 SQL 或解析为已证实的生产瓶颈

> 合入注记（2026-09-27，`update-question-stats-paging-resolve-attribution`；同日证据返修，按归档 JSON 复算校正口径）：
> 本变更只扩展测试侧隔离测量工具（`src/test/.../QuestionStatsExportAttributionMeasureIT`），`src/main`、Mapper SQL、
> schema、JVM、线程池与导出行为零触碰。三次独立 JVM 调用（revision `94ca842`）在 H2 进程内测得：5 个数据形状、
> 每形状 9 个样本（共 45）中 `resolveQuietly` 份额稳定高于分页取数（全局 min 解析份额 0.510 > 全局 max 分页份额
> 0.490）；进入嵌套后 27/27 个 normal 桶样本中 **`answerParse` 复合段**（`parseAnswers` 循环 + 主观分映射构建，
> 不等同于纯解析 CPU）为最大段（份额 0.381–0.656，主观分读取 0.073–0.370、评分 0.200–0.359）。内容语义校验
> 45/45 通过（逐页 `result.equals`、`cellsEqualProduction` 逐格文本、排除 `docProps/core.xml` 的 `zipContentEqual`
> 逐条目），均为内容/语义等价而非整个 XLSX 原始字节相等；原始长度校验 `outputBytesEqual` 只比长度、39/45 true
> （6 次长度差恰 1 字节，成因未定）。副本内部未归属段约占副本自身 resolve 总耗时 0.28%–2.24%；额外一遍受控副本
> resolve 的探针成本单列，不是未归属段、不并入生产 `resolveTotal`、不与其跨调用作精确占比。稳定主导因素仅作
> 下一份独立提案候选，本变更不实施优化，也不外推生产 P99。证据见
> `spec/changes/archive/update-question-stats-paging-resolve-attribution/evidence/measurement-rounds.md`
> 与同目录 `evidence-sha256.txt`。

> 评估结论注记（NO-GO，2026-09-27，`update-answer-json-parse-hotspot` 归档）：本基线**未合入**该提案的任何
> Requirement——其 spec-delta 按**未采用草案**随目录归档。该提案按上行判据把 `answerParse` 复合段在测试侧
> 进一步拆为 `parseAnswers` 调用与主观分映射构建（工具提交 revision `e233936`，`src/main` 零改动），三次独立
> JVM 调用（同夹具/预热/口径，45 样本）测得：`parseAnswers` 相对映射构建稳定主导（占复合段 0.8454–0.9758，
> 映射构建 ≤0.0553，比值 15.3 倍起），但作为副本 resolve 的并列嵌套段 {`parseAnswers`, 主观分读取, 评分}
> 比较时**不满足「单独稳定主导」**——3/27 个 normal 样本（均为 s1000-q20）中主观分读取高于 `parseAnswers`
> （pa/次大段比值 min 0.61），最窄余量 −1.230–+0.203 ms 落在同轮抖动 0.44–1.44 ms 内（不可辨），全局份额
> 区间交叠（pa min 0.2896 < 主观分读取 max 0.4778）。测量有效性：逐页 `result.equals` 不等 0/45、
> `cellsEqualProduction` 与 `zipContentEqual`（排除 `docProps/core.xml`）45/45 通过、独立 oracle 15/15，
> 坏卷分桶与额外 SQL 与既有归档一致（98/2）；长度-only 的 `outputBytesEqual` 38/45 true（7 次差 1 字节）
> 不作字节/内容等价。按判据裁决 **NO-GO 并停止实现**：未改 `GradingPaperReader.parseAnswers` 及任何生产
> 代码、未改主观分映射/SQL/schema/JVM/线程池；隔离 H2 进程内结果不外推生产 MySQL/Tomcat 与 P99。后续若
> 再议，须按本基线「先归因、一次只改一类」另立变更。证据见
> `spec/changes/archive/update-answer-json-parse-hotspot/evidence/measurement-rounds.md` 与同目录
> `evidence-sha256.txt`。

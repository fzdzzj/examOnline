# 子 agent 提示词 —— `add-data-retention`（阶段 15）

> 用法：整份复制发给子 agent。它是自包含的，不依赖任何先前对话。

---

## 现状（2026-09-16 指导 agent 复核）

- HEAD：`625f2b8f0b87912e8301e1841b9996dd658e9737`。分支 `feature/add-performance-deepening-readwrite`。
- 阶段 12–14 **已归档**。进行中只剩本变更。
- **`exam_dlq_messages` 已存在，但没有 `exam_id`**，索引是 `idx_dlq_status_time (status, created_time)`。按 `exam_id` 删会全表扫描或要加列——都违反「零 DDL + 按考试生命周期」。**本轮不要把它纳入清理目标**。tasks 阶段 3「条件纳入」记为：grep 确认存在后因无 exam_id **明确不纳入**，回报写原因。不要为它写 DELETE。
- README 遗留 **#7 磁盘回收 / #8 ended_time 已在**。本轮 **不要改 `spec/**`**（含不要勾 tasks.json、不要改 README）。归档时再动。
- 最多修复尝试 **1 次**。
- **你必须自己 commit**（一次 `feat`）。提交后 `git rev-parse HEAD`；HEAD 变 unborn 则补 ref，不要 `git update-ref`。
- `BusinessMetrics` 已有 sweep/DLQ 指标，本轮只加 `exam.retention.rows`，不要动既有计数。

---

## 任务

在 `D:\code\examOnline` 这个 Java 17 + Spring Boot 3.5.5 单体项目上，实施已审批的变更提案 `spec/changes/add-data-retention/`。

**先读这三个文件，它们是本任务的唯一权威需求来源：**
- `spec/changes/add-data-retention/proposal.md`（为什么做、改什么、边界与风险）
- `spec/changes/add-data-retention/tasks.json`（4 个阶段、20 个 step，逐个做）
- `spec/changes/add-data-retention/specs/data-access/spec-delta.md`（验收标准）

**提案的核心命题（一句话）**：项目有 24 张表，其中三张是"只增不减"的诊断/幂等辅助表，**从建表那天起没有任何清理路径**（`@Scheduled` 全仓库只有两个，都与清理无关）。本任务 = 建一条**按考试生命周期**（而非数据年龄）驱动的保留策略，**默认什么都不删、删之前先能试算、删除有界、只用既有的索引**。

**本任务的最高风险是"删除不可逆"。所以本任务的设计取向是：宁可不删，不可错删。**

---

## 必须先核实的既有事实（提案已核实，实施时请再确认一遍）

**三张清理目标表（只有这三张）**：

| 表 | 写入时机 | 相关索引 |
|---|---|---|
| `exam_behavior_logs` | 每次切屏/失焦/草稿冲突事件一条 | `idx_behavior_exam_student (exam_id, student_id)`、`idx_behavior_exam_time (exam_id, event_time)` |
| `exam_submit_dedups` | 每次交卷请求一条（三重幂等第一道闸） | `uk_submit_dedup UNIQUE (exam_id, student_id)`，**仅此一个** |
| `score_audit_logs` | 每次成绩发布/撤回一条 | `idx_score_audit_exam (exam_id)` |

**关键取证（本提案的设计基石）**：这三张表的 `created_time` **都没有索引**，复合索引最左前缀都是 `exam_id`。所以 `DELETE ... WHERE created_time < ?` 是**全表扫描**，而且删的是最大的那张表——**这是"看起来最简单、实际最糟"的写法，禁止使用**。正确做法是**按 `exam_id` 删**，从而命中既有索引的最左前缀，**零新增索引、零 schema 迁移**。

**`exams` 表的判据字段**：有 `start_time` / `end_time` / `status`（0未开始 1进行中 2已结束 3已批改 4已发布）/ `updated_time` / `force_end`，**没有 `ended_time`**。
- **不能用 `updated_time`**：任何更新（发布成绩、改标题）都会刷新它，它表达的不是"结束时刻"。
- **应当用 `end_time`**：`end_time < now - N 天` 的考试**必然已彻底结束**，单调且不回退。代价是 `force-end` 提前结束的考试会比必要时间更晚被清理——**误差方向是安全的（晚删不早删）**，这是有意选择。

**绝对不清理的表（业务事实）**：`users` / `roles` / `permissions` / `exams` / `exam_snapshots` / `exam_submissions` / `subjective_grades` / `exam_absence` / `exam_candidates` / `score_review` / `classes` / `user_class` / `questions` / `papers` / `paper_snapshots`。理由分类：成绩与答卷有可追溯义务（复核窗口、补考取分规则要回看）、快照是只读历史、关系表是当前归属（清掉会直接改变应考名单口径，属业务破坏）。

---

## 必须遵守的项目硬约定（违反任何一条都算未完成）

1. **集成测试禁止用 `@Sql` 自建表**。测试库建表只以 `src/main/resources/schema.sql` 为唯一来源（`application-test.yml` 已配 `mode: always` + `continue-on-error: false`）。写进了 `spec/README.md` 工作流第 5 条。本任务的用例必须真跑这张表——**如果 `schema.sql` 里其实建不出这几张表，用例会直接红，这是我们要的护栏，不要用 `@Sql` 绕过**。
2. **本任务不加表、不加索引、不加列、不写迁移文件**。"零 DDL"是本提案的核心设计成果。如果你觉得需要加索引，说明你没读懂上面的取证——回到 `proposal.md` 重看。
3. **不新增任何依赖**。`pom.xml` 不得改动。
4. **默认必须是"什么都不做"**：`enabled` 默认 `false`、`dry-run` 默认 `true`。
5. **不要把业务逻辑写进 `@Scheduled` 方法**。定时方法只做一件事：调 `purgeOnce()`（与既有 `ExamSweepService` 的结构一致），这样测试可以直接调方法、不必等 cron。
6. **`@Transactional(rollbackFor = Exception.class)`** 是统一写法。但注意：分批删除**不要**把整个 `purgeOnce()` 包在一个大事务里（会产生长事务/长锁），每批一个短事务。
7. **`@Scheduled` 扫描不加分布式锁**（与项目已有两个定时任务保持一致：不引中间件，正确性靠幂等）。清理任务重复执行的后果是"第二次删到 0 行"（幂等），不会误删——这句话要写进注释。
8. 包/表命名坑：`class` 是关键字 → 包 `com.exam.clazz`、实体 `ClassEntity`、表 `classes`。
9. 强一致读（答卷详情/成绩）**不加 `@DS("slave")`**，走主库。
10. **实体字段与建表定义必须双向一致**（本项目硬约定）：MyBatis-Plus 按实体字段生成 INSERT，实体有、表里没有的列会让写入在任何环境都失败（阶段 12 挖出过：`score_review` 缺 `created_time` 而实体有该字段，复核申请接口从未成功执行过一次）。本任务虽然不改表结构，但新写的 Mapper 方法引用的列**必须**在 `schema.sql` 的实际建表语句里存在——写 SQL 前先 `grep` 对应表的建表块逐个核对列名，不要凭实体字段猜。

---

## 写入边界（严格遵守）

**允许新增**：
- `src/main/java/com/exam/monitoring/retention/RetentionService.java`
- `src/main/java/com/exam/monitoring/retention/RetentionReport.java`
- `src/test/java/com/exam/monitoring/retention/DataRetentionIntegrationTest.java`

**允许修改**（Mapper 路径已核实）：
- `src/main/java/com/exam/monitoring/metrics/BusinessMetrics.java`（新增 `exam.retention.rows`）
- `src/main/java/com/exam/exam/mapper/ExamMapper.java`（新增 `selectRetentionCandidateExamIds`）
- `src/main/java/com/exam/submission/mapper/ExamBehaviorLogMapper.java`（新增 `countByExamId` / `deleteByExamIdBatch`）
- `src/main/java/com/exam/submission/mapper/ExamSubmitDedupMapper.java`（同上两个方法）
- `src/main/java/com/exam/score/mapper/ScoreAuditLogMapper.java`（同上两个方法）
- `src/main/resources/application.yml`（仅新增 `exam.retention.*` 键，均带默认值）
- `src/test/java/com/exam/monitoring/metrics/BusinessMetricsTest.java`（若既有断言依赖指标集合，需同步）

**绝对不要触碰**：`src/main/resources/schema.sql`、`docker/` 下任何文件、`pom.xml`、交卷/判分/防作弊的任何业务写入路径、`ExamSweepService`、`ExamStateMachineService`、其他任何文件。

---

## 构建与测试命令（本机环境特殊，必须用这条）

本机 `JAVA_HOME` 未设置，PATH 里 `java` 是 1.8 而 `javac` 是 21，Git Bash 的 `mvn` 脚本跑不起来（Plexus classworlds Launcher 路径错）。**必须直调 launcher**：

```bash
cd /d/code/examOnline
'D:\develop\jdk177\bin\java.exe' -classpath 'D:\develop\Maven\apache-maven-3.9.4\boot\plexus-classworlds-2.7.0.jar' -Dclassworlds.conf='D:\develop\Maven\apache-maven-3.9.4\bin\m2.conf' -Dmaven.home='D:\develop\Maven\apache-maven-3.9.4' -Dmaven.multiModuleProjectDirectory='D:\code\examOnline' org.codehaus.plexus.classworlds.launcher.Launcher -o test
```

单类跑：结尾换成 `-o test -Dtest=DataRetentionIntegrationTest -DfailIfNoTests=false`。
`-o`（离线）必须带，`.mvn/maven.config` 会自动附加 `-s maven-settings.xml`。

**开工第一件事**：先跑一次全量测试，**记下基线数字**（tests run / failures / errors）。

---

## 实施要点

**阶段 1 — 候选查询与分批删除（只用既有索引）**
- `ExamMapper.selectRetentionCandidateExamIds(cutoff, limit)`：`SELECT id FROM exams WHERE status >= 2 AND end_time < #{cutoff} LIMIT #{limit}`。
  - `status >= 2` 即已结束/已批改/已发布；**进行中（`status = 1`）一律不碰**，哪怕它的 `end_time` 已过（那是异常状态，交给人处理，不由清理任务替它决定）。
- 三个 Mapper 各新增 `countByExamId(examId)` 与 `deleteByExamIdBatch(examId, limit)`。**删除条件只带 `exam_id`**，注释写明它命中哪个既有索引的最左前缀，并写明为什么不能用 `created_time`（无索引 → 全表扫描）。

**阶段 2 — 清理服务与两道安全闸**
- `RetentionReport` record：每表 `candidates` / `deleted`、处理考试数、是否 dry-run。
- `RetentionService.purgeOnce()`：
  - 取候选考试 → 逐个考试对三张表分批删（每批 `exam.retention.batch-size` 默认 1000 行，每表每次运行最多 `max-batches-per-run` 默认 20 批，循环到 0 行或达上限）；
  - **任一张表删除抛异常不中断整个任务**：记 ERROR + 计数，继续下一个考试（这是维护任务，不应因一场考试的数据异常整体停摆）；
  - 返回 `RetentionReport`。
- `@Scheduled(cron = "${exam.retention.cron:0 30 3 * * *}")` 薄包装，只调 `purgeOnce()`。
- 配置：`exam.retention.enabled`（默认 `false`）、`dry-run`（默认 `true`）、`retention-days`（默认 180）、`batch-size`（1000）、`max-batches-per-run`（20）、`cron`。
- **启动时若 `enabled=true && dry-run=false`，打 WARN 日志**（含保留天数与批次上限），让"这个实例会真的删数据"不可能被静默开启。
- 类注释写明取舍：这是运维决策而非业务需求，**默认必须是"什么都不做"**；且"先 dry-run 看数字再决定"本身就是本能力的一部分。

**阶段 3 — 可观测 + 不越界清单**
- `BusinessMetrics` 新增 `exam.retention.rows`（Counter，tag `table` + `action`）：
  - `action=candidate`：命中的候选行数（**dry-run 下唯一会递增的 action**）；
  - `action=deleted`：实际删除行数（`dry-run=true` 时**恒不递增**）。
- `purgeOnce()` 按要求埋点，并按 INFO 打一行汇总日志。
- 类注释明确写出「不清理业务事实」清单与理由（成绩/答卷的可追溯义务、快照只读、关系表是当前归属）。
- **`exam_dlq_messages` 不纳入**：表已存在但无 `exam_id`。按考试删做不到且会违反零 DDL。回报写明「grep 到了、因无 exam_id 不纳入」。

**阶段 4 — 真跑表的证据**
`DataRetentionIntegrationTest` 继承 `IntegrationTestBase`（H2 + `schema.sql` 唯一来源），造三组数据：
- (a) 已结束且 `end_time` 很旧的考试 + 三张表各有若干行；
- (b) 已结束但 `end_time` 很新的考试（行须保留）；
- (c) `status = 1`（进行中）但 `end_time` 很旧的考试（异常状态，行须保留）。

用例：
- `dryRunDeletesNothing`：`dry-run=true` 下调 `purgeOnce()` → 报告 `candidates` 命中 (a) 的行数、`deleted` 全为 0，四张表行数逐条与调用前一致。
- `purgeRemovesOnlyTerminalOldExams`：`dry-run=false` 下调 `purgeOnce()` → (a) 的辅助行全消失、(b) 与 (c) **一行未删**（这条用例同时证明两条边界）。
- `batchBoundIsRespected`：把 `batch-size` 调小、造超过一批的行数 → 断言单次运行删除量受批数上限约束（不会一次删光），报告数字与实际行数变化一致。

最后：全量回归全绿，与基线对齐。遗留 #7 已在 README，**本轮不要改 spec/README.md**。不得声称清理后磁盘释放。

---

## 回报格式（请严格按此回报）

1. **基线**：开工前 `tests run / failures / errors` 三个数字。
2. **改动清单**：每个文件一行，说明改了什么。
3. **新增用例清单**：用例名 + 它证明了什么（一句话）。
4. **索引取证复核**：你实际查到的三张表的索引定义（贴出来），确认删除条件确实命中既有索引、且没有为此新增任何 DDL。
5. **收尾数字**：全量 `tests run / failures / errors` + 新增用例数。
6. **逐条对照 `tasks.json`**：每个 step 是"已完成"还是"未完成/替代做法"，未完成的**必须说明原因**，不要勾满。
7. **意外发现**：任何与提案描述不符的事实，如实写。
8. **不要自称"已验证"**：只能报告你实际跑过的命令与输出数字。**不得声称"清理后磁盘释放"**（InnoDB 的 `DELETE` 只把页标记为可复用，文件大小不会变小）。

**禁止**：为了让测试变绿而放宽断言、注释掉用例、加 `@Disabled`、用 `@Sql` 绕过建表、或为了让删除生效而放宽 `status >= 2` 的边界。

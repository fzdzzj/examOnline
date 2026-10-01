# 提案：标量只读站点的列投影（先归因、按站点条件实施）

> 状态：已收口并归档（2026-09-29）。逐站点裁决 **GO 2/5**：s1 学生考试列表（`myExams`）与 s2 缺考标记（`markAbsence`）实施 `.select(...)` 列投影（实施提交 `9182a5d`），s3/s4/s5 按「不稳定/无净收益」NO-GO、未实施未采用；仓库根 `mvnw.cmd clean test` @ `9182a5d` → 325/0/0/1 BUILD SUCCESS 退出码 0；data-access 基线按 GO 站点范围合入 ADDED Requirement「标量读取的列投影」（逐站点裁决与验收边界见基线合入注记与 `spec/README.md` 归档行）。代码与调用边界核对基于 `f6e336c`（2026-09-29），分支 `feature/update-monitor-overview-submission-projection`。
> 本提案不是已测得的性能缺陷：收益须由一次性本地 MySQL 8 容器的同口径分账（S1–S4）逐站点裁决，GO 的站点才进入实施；NO-GO 的站点停，不实施、不改代码。
> `docs/backend-optimization-candidates.md` 是更早 revision 的未跟踪候选台账，本轮**全程不读、不改、不 add、不提交**；本轮产出的候选清单落在本目录 `evidence/candidate-ledger.md`（受版本控制）。

## Why

答卷表 `exam_submissions` 有两个 LONGTEXT：`paper_json`（个人快照）与 `answers`（最终答案）。生产里已批改/已交卷行的这两列非空（快照约 20KB/行、答案约 2KB/行量级）。有五个读取站点只用行上的**标量字段**，却按实体默认全列取数，把用不到的长字段整行搬过网络并做逐行映射：

| # | 站点 | 入口 | 被读实体 | 实际调用的 getter |
|---|------|------|----------|-------------------|
| 1 | `ExamTakingService.myExams` | 学生考试列表 | `ExamSubmission` | `getExamId` / `getStatus` / `getDeadlineTime` |
| 2 | `AbsenceService.markAbsence` | 缺考标记（状态机 toEnd） | `ExamSubmission` | `getStudentId` |
| 3 | `ScoreService.myScore` | 学生查本人成绩 | `GradingSubmission` | `getTotalScore` / `getStatus` / `getObjectiveScore` / `getSubjectiveScore` / `getPartialGraded` |
| 4 | `ScoreReviewService.handle`（AGREE 分支） | 复核同意调分 | `GradingSubmission` | `getId` |
| 5 | `MakeupScoreService.collectFamilyScores` | 补考最终成绩合并 | `GradingSubmission` | `getTotalScore` / `getSubmitTime` |

前序 `update-monitor-overview-submission-projection`（NO-GO，已归档）的教训：在 H2 + MockMvc 同进程里，长字段读取增量只有 1.17–2.52%，且增量的轮间波动与自身同量级——**进程内计时分辨不出这类差额**；监考路径的主导因素是逐人 Redis GET，与本轮站点无关。因此本轮换判据：真引擎（一次性 MySQL 8 容器）上按 **LENGTH 机械计算的应传字节**（S2）为主判据，wall-clock 只做单侧无回归（S3）；语义等价（S1）在测试上下文中用同一条生产调用链两臂对拍。

**未知**：五个站点在真实 MySQL 上的应传字节降幅、投影后服务语义是否逐字段不变、以及各站点是否值得实施（允许部分 GO）。**排除项**（不评估、不改动）：`MonitorService.overview`（已 NO-GO，结论有效）、一切需要 `answers` 做逐题得分的路径（判分、成绩预览、导出个人报告、复核展示、补发扫描）。

## What Changes

1. **阶段 0（先冻结，再测量）**：本目录写定 `evidence/PREREGISTRATION.md`——站点清单、造数形状与分布、臂定义与轮数、S1–S4 判据算子与冻结倍数、措辞纪律、证据落位；冻结后 sha256 入库，测量开始后不再修改。`specs/data-access/spec-delta.md` 只是草案：未 GO 不得合入基线。
2. **阶段 1（静态归因，只读，零 `src/main` 改动）**：逐站点产出入口与调用链、实际调用的 getter 机械清单（附代码引用）、以 `StatementHandler.prepare` 拦截器捕获的 SQL 原文与 SELECT 列表；并在测试上下文加**运行时护栏**——PROJ 臂实体长字段为 null 而库内该行非 null，且返回实体被「读取即失败」包装（任何路径真读长字段即红）。产出受版本控制的候选清单。
3. **阶段 2（真引擎分账，一次性本地容器）**：`mysql:8.0` 一次性容器（tmpfs 数据目录、整库执行仓库 `schema.sql` 且退出码 0、记录镜像 digest 与 `SHOW CREATE TABLE exam_submissions`），造数 3 形状（n=200/1000/3000，`answers≈2KB`、`paper_json≈20KB`）；每站点两臂（OLD 全列 / PROJECTED 投影）+ OLD 等价副本臂作噪声带，臂序逐轮轮转、每臂 ≥5 轮；逐轮记录 wall-clock、应传字节、返回行数、SQL 条数、退出码与 ISO 时间戳；机械脚本按冻结算子输出逐站点裁决。用毕 `docker rm -f` 销毁并留 `docker ps -a` 零匹配证据。
4. **阶段 3（条件实施，仅 GO_site）**：仅对 GO 站点按仓库既有写法加 `.select(...)` 列投影（一次只改这一类：禁改 SQL 语义/谓词/排序、禁加索引、禁改 schema、禁加缓存、禁动 JVM/线程池/连接池/MQ/前端、禁改 `MonitorService.overview`、禁顺势批量化 `MakeupScoreService` 的家族 N+1）；同时补测试侧护栏（长字段为 null 断言 + 各站点响应等价断言），用例总数只增不减、Skipped 不增。仓库根 `mvnw.cmd clean test` 记录四计数 + BUILD + 退出码 + revision。
5. **阶段 4（收口）**：tasks.json 按真实结果回勾；evidence 出 sha256 清单并 `sha256sum -c` rc=0；`spec/README.md` 归档行写验收边界（一次性本地容器、单机、非生产、不外推生产 MySQL/Tomcat，不构成交卷或监考 P99 结论）与逐站点裁决；仅 GO 且验收成立才把 delta 合入 `data-access` 基线，否则按未采用草案归档；跑 `AGENTS.md` 四条自查与 wrapper 自查；仅显式暂存本任务文件。

## Impact

- **规范**：GO 且验收成立时，仅 `spec/specs/data-access/spec.md` 增加一条「标量读取的列投影」Requirement（草案见本目录 spec-delta）；NO-GO 时该 delta 按未采用草案随目录归档。
- **代码**：仅 GO 站点的取数路径加列投影（预计 `ExamTakingService` / `AbsenceService` / `ScoreService` / `ScoreReviewService` / `MakeupScoreService` 中的对应查询行）及针对性测试；不新增实体、不改 Mapper 接口、不改 schema。
- **用户/API**：不新增端点、不改响应契约；投影后长字段为 null 只影响服务内部读路径，不改变任何用户可见口径。
- **数据与部署**：不改 `schema.sql`、迁移、索引、JVM、线程池、连接池、MQ 或前端；不写共享 dev、不写 dev 库、不 push、不建 PR；Docker 仅一次性本地容器、用毕销毁。

## 验收与停止条件

- 测量证据须绑定具体 revision、一次性容器（digest + `SHOW CREATE TABLE`）、样本构造、命令、逐轮原始输出与 ISO 时间戳；判据由机械脚本按冻结算子复算，不人脑算；不挑轮、不跨轮相减求占比、失败轮保留不删。
- 判据（冻结于 `evidence/PREREGISTRATION.md`）：`GO_site ⇔ S1 语义等价（响应/生效结果逐字段相同，3 形状全过）∧ S2 应传字节相对 OLD 下降 ≥ 冻结倍数 ∧ S3 单侧无回归（投影臂每轮 wall-clock ≤ OLD 噪声带上界）∧ S4 SQL 条数与返回行数不变`；逐站点独立裁决，允许部分 GO。
- 措辞纪律：S2 不成立写「字节收益不成立」，S3 不成立写「不稳定/无净收益」，不得混用；不得为凑 GO 把「每一轮」换成中位数、不得下调冻结倍数。
- 任一站点语义不等价、PREREGISTRATION 无法在测量前冻结、Docker 不可用（只做阶段 1 并如实写「引擎段未执行」，不得实施）、或门禁重跑仍红：一律停手保留现场，不得自行发挥。
- 成功实施后在已提交的实现 revision 于仓库根执行 `mvnw.cmd clean test`，记录当次四计数、BUILD 结论与退出码；记录 `AGENTS.md` 的 schema/@Sql/实体一致性自查。归档提交 SHA 不能自嵌，只引用门禁所跑实现 revision。

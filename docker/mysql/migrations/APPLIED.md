# 迁移执行台账（APPLIED.md）——「环境 × 已应用脚本」的唯一结构化载体

> 本文件是 `docker/mysql/migrations/` 存量库迁移脚本在各环境的**执行台账**。放在本目录的脚本
> 没有自动执行者（见同目录 README「先说结论」），脚本入库 ≠ 迁移已生效；哪些脚本在哪个库真正
> 应用过、什么时候应用、当时怎么验证的，以本表为准。归档 evidence 与回报里的「已应用」声称
> 仅作对照，不构成登记真值——登记真值来自对目标库现场执行核验 SQL 的实测（验证对象而不是
> 「命令没报错」，口径见下文「核验 SQL」）。
> 本文件不写脚本计数：登记表脚本集与 `git ls-files docker/mysql/migrations` 恒对齐，新增脚本
> 的同一提交必须同笔加行（见「更新纪律」）。

## 更新纪律（三件套齐才算完成）

对存量库的一次人工迁移应用，**「应用 + 执行后验证 + 更新本表」三件套齐才构成完成**：

1. **应用**：按同目录 README「手工应用流程」对目标库执行（顺序按文件名周期升序；命令行用容器名）；
2. **验证**：按本表该行「核验 SQL」对对象实测（对象存在性与形状，不是退出码）；
3. **登记**：同笔更新本表对应行——状态、核验输出摘要、核验时间、当时 commit。

只提交脚本不应用、或只应用不登记，均不构成迁移完成。目录 README 与仓库根 AGENTS.md 只保留
指向本文件的指针，清单不复制（避免第三份副本漂移）。

## 登记表

登记真值产生方式：对 dev 主/从库现场执行核验 SQL（口径见下一节）。每行状态含核验输出摘要 +
核验时间 + 当时 commit。**dev 从库不由人工直接应用**：复制正常时由主库经复制同步，登记前已
逐对象复核（主从 GTID 对齐 + 对象级一致性）。

| 脚本 | 对象 | 幂等预期错误码 | 核验 SQL | dev 主库（exam-mysql-master） | dev 从库（exam-mysql-slave） |
|---|---|---|---|---|---|
| 2026-W7-add-grading-score.sql | exam_submissions 补 6 列（objective_score/subjective_score/total_score/grading_status/grading_error/partial_graded）+ idx_submissions_grading | 1060（重复列）、1061（重复索引名） | A1 列存在性计数 + A2 索引存在性 | 已应用（6/6 列 + 索引在）·2026-10-11 12:16 @ c4425b0 | 已应用（同主库逐项一致）·2026-10-11 12:16 @ c4425b0 |
| 2026-W10-add-absence-makeup.sql | exam_absence、exam_candidates 两表 + exams 补 parent_exam_id/makeup_score_rule 两列 + idx_exams_parent | 头注称「重复执行不报错」；**实测头注失真**：`ADD [COLUMN\|KEY] IF NOT EXISTS` 在 MySQL 8 语法非法，整本执行报 1064（见「差异登记」） | A3 表存在性计数 + A1 + A2 | 部分应用：两表与两列在；**idx_exams_parent 未应用（死对象，见差异登记）**·2026-10-11 12:16 @ c4425b0 | 同主库逐项一致·2026-10-11 12:16 @ c4425b0 |
| 2026-W10-add-class.sql | classes、user_class 两表 | 无（CREATE TABLE IF NOT EXISTS 重复执行不报错） | A3 | 已应用（2/2 表在）·2026-10-11 12:16 @ c4425b0 | 同主库·2026-10-11 12:16 @ c4425b0 |
| 2026-W10-add-score-review.sql | score_review 表 | 无（同上） | A3 | 已应用（表在）·2026-10-11 12:16 @ c4425b0 | 同主库·2026-10-11 12:16 @ c4425b0 |
| 2026-W15-add-dlq-messages.sql | exam_dlq_messages 表 | 头注称「表 already exists 可忽略」；正文为 CREATE TABLE IF NOT EXISTS，实际重复执行不报错 | A3 | 已应用（表在）·2026-10-11 12:16 @ c4425b0 | 同主库·2026-10-11 12:16 @ c4425b0 |
| 2026-W15-add-score-review-created-time.sql | score_review 补 created_time 列 | 1060 | A1 | 已应用（列在）·2026-10-11 12:16 @ c4425b0 | 同主库·2026-10-11 12:16 @ c4425b0 |
| 2026-W16-add-audit-log.sql | audit_log 表 | 头注称「表 already exists 可忽略」；正文为 CREATE TABLE IF NOT EXISTS，实际重复执行不报错 | A3 | 已应用（表在）·2026-10-11 12:16 @ c4425b0 | 同主库·2026-10-11 12:16 @ c4425b0 |
| 2026-W16-add-primary-keys.sql | 全部存量表补 PRIMARY KEY (id)（逐条 ALTER） | 1068（已有主键）、1146（表不存在）——按头注**逐条执行、跳过预期失败** | A4 主键覆盖计数 | 已应用（范围内 25/25 表有主键，缺主键清单为空）·2026-10-11 12:16 @ c4425b0 | 同主库·2026-10-11 12:16 @ c4425b0 |
| 2026-W38-add-audit-log-time-index.sql | audit_log 补 idx_audit_time | 1061 | A2 | 已应用（索引在）·2026-10-11 12:16 @ c4425b0 | 同主库·2026-10-11 12:16 @ c4425b0 |
| 2026-W38-add-republish-index.sql | exam_submissions 补虚拟列 answers_missing + idx_submissions_republish | 1060、1061 | A1 + A2 | 已应用（列与索引均在）·2026-10-11 12:16 @ c4425b0 | 同主库·2026-10-11 12:16 @ c4425b0 |
| 2026-W40-add-submissions-student-index.sql | exam_submissions 补 idx_submissions_student | 1061（**本仓实测吻合**：重复执行报 `ERROR 1061 Duplicate key name 'idx_submissions_student'`，对象无变化） | A2 + A5 | 已应用（**2026-10-11 本案补应用**：应用前 ABSENT → 应用后索引在，EXPLAIN 按 student_id 查询实走本索引 type=ref；复跑 1061 对象无变化）·2026-10-11 12:23 @ c4425b0 | 已应用（由复制同步：应用后主从 GTID 对齐、秒级延迟 0，索引与 EXPLAIN 与主库一致）·2026-10-11 12:23 @ c4425b0 |

### 核验 SQL（A1–A5，对象级口径）

占位符：`<表>` 表名、`<列>` 列名、`<索引>` 索引名、`<范围表清单>` 该脚本对象的表名集合。
全部只读，可在主库或从库执行（从库执行时读到的即复制后的状态）。

```sql
-- A1 列存在性（预期：计数 = 脚本声明的列数）
SELECT COUNT(*) FROM information_schema.columns
 WHERE table_schema = DATABASE() AND table_name = '<表>' AND column_name IN (<列清单>);
-- A2 索引存在性与形状（预期：返回该索引成员序与列名，缺失则空集）
SELECT seq_in_index, column_name FROM information_schema.statistics
 WHERE table_schema = DATABASE() AND table_name = '<表>' AND index_name = '<索引>' ORDER BY seq_in_index;
-- A3 表存在性（预期：计数 = 该脚本声明的表数）
SELECT COUNT(*) FROM information_schema.tables
 WHERE table_schema = DATABASE() AND table_name IN (<表清单>);
-- A4 主键覆盖（预期：缺主键清单为空）
SELECT IFNULL(GROUP_CONCAT(t.name), '(none)') FROM (<范围表清单>) t
 LEFT JOIN information_schema.table_constraints tc
   ON tc.table_schema = DATABASE() AND tc.table_name = t.name AND tc.constraint_type = 'PRIMARY KEY'
 WHERE tc.constraint_name IS NULL;
-- A5 真实链路代理（对象被真正用到）：EXPLAIN 一条该索引服务的查询，预期 key 命中该索引
EXPLAIN SELECT id FROM <表> WHERE <索引首列> = <样例值>;
```

## 差异登记（实测与声称的冲突，如实记录并上报，不静默取任何一方）

**`exams.idx_exams_parent`（2026-W10-add-absence-makeup.sql 第 3 条）登记为「未应用（死对象）」**，理由三
条均为 2026-10-11 @ c4425b0 现场（dev 主/从库）实测：

1. `schema.sql` 的 `exams` 建表块**没有**该索引——即使按脚本头注「新建库由 schema.sql 一次建全」，新库也
   不会有它；对该库补应用反而制造存量库与新库的结构分歧；
2. 脚本用 `ADD KEY IF NOT EXISTS` 语法，MySQL 8 对 `ADD [COLUMN|KEY] IF NOT EXISTS` 一律报
   `ERROR 1064 (42000)`（对 master 用「列已存在、若语法受支持则为无害 no-op」的第 1 条语句做的两分支探针
   实证；同语法的第 3 条不作现场执行——若语法受支持它会真实建索引）——该脚本在 MySQL 8 上**整本不可按原样
   执行**，头注「重复执行不报错（MySQL 8.0）」与同目录 W7 头注「MySQL 8 不支持 ADD COLUMN IF NOT EXISTS」
   相互矛盾，实测支持后者；
3. 全仓库代码零引用该索引（parent_exam_id 的查询路径未依赖它）。

处置：不补应用（避免制造分歧）、不修改既有脚本（目录红线）、不擅自补写新脚本——如需处置（补进
schema.sql / 新写迁移脚本 / 判定脚本该条作废）须另行裁决。该行其余对象（两表两列）状态正常。

另：dev 库中存在历史遗留表 `rep_test`，它不在 `schema.sql` 也不在任何迁移脚本中——这是既有登记过的
遗留对象（见仓库根 AGENTS.md），非漂移，不属于本台账脚本集。

## 新环境从零建库 runbook

一个**新建的空 MySQL 环境**不是存量库：`schema.sql` 是 `CREATE TABLE IF NOT EXISTS` 的全量幂等脚本，
一次建全全部业务表（含主键、索引与本台账所列对象的最终形态），**本目录迁移脚本一律无需执行**。

1. **起库**：按仓库根 `docker-compose.yml` 起本项目的 MySQL 服务（主/从容器名与服务名以该文件为准，
   宿主端口声明以该文件为准，本文件不复制端口值；连接口令以 `src/main/resources/application-dev.yml`
   为准，本文件不复制）。空数据目录首次启动时容器的 init 挂载只写复制账号与主从配置，不含业务表。
2. **建 schema**：应用启动时自动执行 `src/main/resources/schema.sql`（或手工对目标库执行一遍）。
   新库由此获得全部业务对象——不需要、也不应该再重放任何 `2026-W*.sql`（那是给**已存在的库**补差的）。
3. **主从复制**：从库由复制通道同步主库的全部变更（含 DDL）。核验复制健康用 `SHOW REPLICA STATUS`
   看 IO/SQL 线程与延迟，并用 `@@GLOBAL.gtid_executed` 主从对齐确认追平。
4. **与既有环境一致性核验**：需要证明新库与 dev 等价时，按本登记表「核验 SQL」A1–A4 逐对象核对
   （对象存在性与形状），**不重放迁移脚本**。空库初始化与脚本重跑语义的既有实证见
   `docs/mysql8-init-verification.md`（verify-mysql8-init 归档证据：空库 schema.sql 一次建全、
   W16 两类预期错误码语义一致、`--force` 重复执行要点——具体数值结论以该文档为准，此处不复制）。
5. **跑真实链路**：对核验中发现的对象，按 A5 用真实查询确认可用（audit_log 事故的教训：异常可能被
   `catch` 吞掉，只有真实链路能证明对象真的被用到）。

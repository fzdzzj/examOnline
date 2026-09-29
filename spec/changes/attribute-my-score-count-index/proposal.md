# 提案：myScore 名次聚合计数的覆盖索引候选先归因，再裁决是否加索引

> 状态：**进行中（归因-only，未实施）**。本笔只做归因与裁决：`src/main`、`src/main/resources/schema.sql`、`docker/mysql/migrations/` 零改动。归因不成立即如实 NO-GO 并按未采用草案归档；成立则另写一份**独立**实施提案，本笔仍不实施。

## Why

`ScoreService.myScore`（`src/main/java/com/exam/score/service/ScoreService.java:300`）的名次取数在 `optimize-my-score-rank-fetch` 之后是一条聚合计数：`gradingSubmissionMapper.selectCount(Wrappers...eq(examId).eq(status, GRADED).isNotNull(totalScore).gt(totalScore, 本人总分))`。`GradingSubmission` 是 `exam_submissions` 表的判分域双实体（`@TableName("exam_submissions")`），因此该 COUNT 打在答卷表上，谓词为 `exam_id = ? AND status = 3 AND total_score IS NOT NULL AND total_score > ?`。

答卷表现有 6 个索引（PRIMARY、uk_exam_student、idx_submissions_sweep、idx_submissions_exam_submit、idx_submissions_grading、idx_submissions_republish）**没有任何一个包含 total_score**。静态推演上，最可能的基线计划是走 `(exam_id, grading_status)` 或 exam_id 前缀做 ref 定位、再逐行回表取 total_score 过滤；候选覆盖索引 `(exam_id, status, total_score)` 可把 COUNT 变成纯索引区间扫描（Using index，无回表）。但这是**运算次数推算，不是实测**：

- H2（测试库引擎）看不见回表——它的执行计划没有 MySQL 二级索引回表这回事，H2 上的任何计时或 EXPLAIN 都不能当本候选的证据；
- `spec/specs/score-management/spec.md`「myScore 名次取数」条款明文 **SHALL NOT 为凑收益顺手修改索引**；`spec/specs/performance/spec.md`「Java 优化先归因、一次只改一类」要求先在同一负载下量出剖面再动手。

最强反例：基线计划本身已近似覆盖（例如优化器选择只扫索引条件、回表代价极小）、或班级规模在真实负载里很小、或写路径（交卷 CAS / 汇总 CAS / 答卷 INSERT，均要维护这个新索引）的额外代价吞掉读端收益。因此先归因，裁决后再谈实施。

## What Changes

1. **MyBatis 侧捕获原文**：新增 test-only 测量工具（类名以 `IT` 结尾，不进 Surefire 全量门禁），在隔离 H2/test profile 上按既有 `RankAttributionMeasureIT` 同口径造数（每 exam_id 50/200/1000/3000 行，status=3、total_score=objective_score=score(i)=400+(i×7919)%300 scale 1、大量并列且全非空），注册 MyBatis 拦截器捕获 `ScoreService.myScore` 真实调用链发出的全部语句原文与绑定参数（不手写近似 SQL）。捕获结果与独立推算的名次做语义自检。
2. **B1 基线臂（真 MySQL 引擎）**：本地 Docker 起一次性临时 mysql:8.0 容器（与 docker-compose.yml 同主版本；不连共享 dev、不写 dev 库、用完销毁），整库执行仓库 `schema.sql` 原样建表（含全部既有索引），灌入同口径可复现数据；对捕获原文逐规模跑 `EXPLAIN` 与 `EXPLAIN ANALYZE`，每形状 ≥3 轮（实际 5 轮）+ 2 次预热，逐轮记录：选中索引、rows 估计、访问方式（ref/range）、是否 Using index（覆盖）、EXPLAIN ANALYZE 实际行扫描数与实际耗时、逐轮 ISO 时间戳与原始输出。不得用单次最好值。
3. **B2 对照臂**：同一容器同一数据上只建候选覆盖索引 `(exam_id, status, total_score)`（只在临时容器里，不进仓库），重跑 B1 同口径；随后 DROP 该索引再复测 2 轮基线作为**同代码顺序漂移**读数。判定带用该漂移读数设定：波动吞没收益就写「稳定性不达标」，不写「收益不存在」。
4. **B3 副作用同批核**：新增索引对写路径的代价——答卷 INSERT、汇总 CAS（`GradingSubmissionMapper.casSummarize` 原文）、交卷 CAS（`ExamSubmissionMapper.casSubmit` 原文）各 3 轮同口径对照；`SchemaSqlMysqlCompatibilityTest` 在门禁中确认仍绿（本笔不改 schema.sql，属回归确认）；`docker/mysql/migrations/` 脚本放进去不等于生效（AGENTS.md 硬约定 4）——若 GO，实施提案必须显式写明谁按什么顺序应用到存量库、如何验证。
5. **B4 裁决与交付**：「测量是否有效」（M 组判据）与「是否找到稳定主导因素」（D 组判据）分开裁，判据在测量前冻结于 `evidence/PREREGISTRATION.md` 并算子化由脚本机械复算。主导成立 → 写**另一份独立实施提案**（schema.sql 变更草案 + migration 脚本草案 + 实体零改动论证 + 等价性论证，等价证据用既有 `MyScoreRankEquivalenceTest` E1–E8 全绿）；不成立 → 按未采用草案归档本笔，不实施。

## Impact

- **规范**：`specs/score-management/spec-delta.md` 为本候选的归因门禁草案（ADDED）。NO-GO 时按未采用草案随目录归档、不合入基线；GO 时由独立实施提案承接，本目录归档时仍不合入。
- **代码**：仅 `src/test`（测量工具一个文件）。`src/main`、`schema.sql`、`docker/mysql/migrations/`、前端、Mapper SQL、JVM/线程池零改动。
- **用户与部署**：不新增端点、不改任何可见行为。临时 MySQL 容器用 127.0.0.1 高位端口 + tmpfs 数据目录，宿主内隔离，不触碰共享 dev 与数据卷，证据落盘后 `docker rm -f` 销毁。`docs/backend-optimization-candidates.md` 保持未跟踪、不暂存、不提交。

## 停止条件与证据边界

- 捕获到的语句与推演形态不符（例如 COUNT 并非打在 exam_submissions）、基线计划已无回表可消除、容器里 schema.sql 建不出原样结构、轮次不完整或语义自检失败：停止并如实记录，不为 GO 改判据。
- 临时容器（tmpfs、单机、空并发负载）结果只证明隔离口径下的机制与倍数，**不外推生产 P99、生产数据量、生产 MySQL 参数与真实并发**；生产负载频度与真实班级人数分布无获准来源，记为未知。
- 写路径墙钟含 mysql 客户端与解析开销，只取同口径臂间比值，不取绝对值下生产结论。
- GO 只授权「写一份独立实施提案」，不授权在本笔实施；实施仍需走该提案自身的审批与门禁。

# 提案：判分／成绩读形态列投影（5 单元，先归因后裁决）

> 状态：执行中（先归因、再冻结判据、后测量、仅 GO 才实施）。静态核对基于 `d6232e5`（分支 `feature/update-monitor-overview-submission-projection`，2026-10-01）。
> 一次只改一类：本卡只改「读语句的 SELECT 列集」；谓词、排序、索引、schema、缓存、JVM、线程池、连接池、MQ、前端、写语句（含 `casSummarize`）一律零改动。

## Why

判分进度（`GradingQueryService.progress`）、成绩汇总（`ScoreService.summarize`）、发布预览（`ScoreService.publishPreview`）三条读路径的答卷/批改行取数均为实体默认全列（`GradingSubmission` 15 列含 LONGTEXT `answers`；`SubjectiveGrade` 16 列含 TEXT `student_answer`），而机械归因表明这些路径实际只消费标量字段：

- progress 主语句只读 `id`、`grading_status`；主观行只读 `submission_id`、`score`；
- summarize 主语句只读 `id`、`grading_status`、`objective_score`；主观行只读 `submission_id`、`question_id`、`score`；
- publishPreview 主语句只读 `student_id`、`objective_score`、`subjective_score`、`total_score`、`partial_graded`（指导草案曾漏 `objective_score`，以机械证据 `item.setObjectiveScore(submission.getObjectiveScore())` 为准修正）。

前序 `project-scalar-only-submission-reads` 已确立「字节为主判据 + 单侧无回归边界 + 三臂轮转」的归因方法并在同表（`exam_submissions`，`exam_id = ?` 谓词）测得 n=300 时 4,529,200 → 1,800 字节（2516.33×@n=3000）的量级；本卡沿用同口径把该方法应用到判分/成绩域的五个站点。量级锚：`docs/需求决策记录.md` §二十（grep 锚 `exam_bench`）实测库 10 万答卷 / 500 场考试（场均约 200 行）、`loadtest/README.md` §4（grep 锚 `5000 笔`）单场 5000 人交卷压测包络——本卡形状 n ∈ {200,1000,3000}（每场答卷数）落在真实量级包络内，且与前序两卡同口径便于横向比较。

排除项（机械证据见 `evidence/PREREGISTRATION.md` §0）：`ScoreExportService.forEachSubmissionPage`（消费方经 `QuestionScoreResolver.resolveBatch` 全页读 `answers`）、`ObjectiveGradingService.loadSubjectiveGrades`（判分热写路径，卡面默认排除）。

## What Changes

1. **先归因后冻结**：消费字段清单机械导出（直接 getter、传入私有 helper、record/DTO 构造、逃逸 lambda 全覆盖），判据在任何测量运行前冻结于 `evidence/PREREGISTRATION.md` 并记 sha256。
2. **真引擎三臂测量**：一次性 `mysql:8.0` 容器（tmpfs、127.0.0.1 高位端口、独立库名 `grading_score_projection_measure`、整库执行 `schema.sql`、记镜像 digest 与 `SHOW CREATE TABLE`/`SHOW INDEX`、用毕销毁留零匹配证据）；形状 n ∈ {200,1000,3000}；每端点三臂 OLD（全列）/ PROJ（投影）/ OLDrep（等价副本）× 2 预热 + 5 计时轮、逐轮左轮转；应传字节用容器侧机械解析 SELECT 列表后 `SUM(COALESCE(LENGTH(...),0))` 确定性求和（非计时）。判据：S1 语义逐字段等价（三端点响应 + `casSummarize` 逐轮逐参不变）、S2 应传字节比 ≥ 5.0（逐单元逐形状）、S3 PROJ 逐轮落在 max(OLD ∪ OLDrep) 噪声带内（逐端点）、S4 条数与行数两臂相同。
3. **仅 GO 才实施、且只动读形态**：对 GO 单元加 `.select(...)`，列集 == 冻结列集一字不差；新增常驻护栏 `GradingScoreProjectionGuardTest`（长字段读取即失败 + PROJ 长字段必 null 而库内非 null 计数 > 0 + 捕获 SELECT 列表 == 冻结列集），先红后绿；测量工具 `GradingScoreProjectionMeasureIT`（IT 后缀不进门禁）。
4. **收口**：delta 合入 `spec/specs/data-access/spec.md` 既有 Requirement「标量读取的列投影」（逐 GO 单元一条 Scenario + 尾部合入注记，验收边界＝一次性本地容器/单机/空并发/测试上下文）；`spec/README.md` 当前状态行 + 归档行；变更目录整体移入 `spec/changes/archive/`。

## Impact

- **规范**：仅 GO 且验收通过时，`spec/specs/data-access/spec.md`「标量读取的列投影」Requirement 追加 Scenario 与合入注记（本目录 `specs/data-access-delta.md` 为候选）。判分进度／成绩汇总的业务口径如需条款仍归 `grading` / `score-management` 能力域，本卡不新增。
- **代码**：仅 GO 后改 `GradingQueryService.progress`（M1/M2）与 `ScoreService`（M3/M4/M5）的目标语句 `.select(...)` 列集；测试侧新增护栏与测量 IT。NO-GO 单元保持原样。既有覆盖（`ScoreServiceTest`、`GradingScoreIntegrationTest`、`ScorePublishTransactionIntegrationTest`）作为回归基线，断言不放宽；progress 端点无既有覆盖（如实登记），其语义网由本卡 oracle 与护栏承担。
- **用户/API**：不新增端点、不改响应契约或用户可见口径。
- **数据与部署**：不改 `schema.sql`、迁移、索引、JVM、线程池或 Redis 写入协议；不写共享 dev、不 push、不建 PR、不连 sport-verify-* 容器与宿主 6379。

## 验收与停止条件

- 判据与措辞在执行前冻结（见 `evidence/PREREGISTRATION.md`）；裁决由 `analyze-grading-score-projection.cjs` 按冻结算子机械输出，不人脑算；不得混用 S2/S3 失败措辞、不得以中位数替代「每一轮」、不得挑轮、不得下调 5.0 倍数；失败轮原样保留。
- S1 任一破坏、或 S2/S3/S4 命中 NO-GO 判的单元不实施；允许部分 GO；实施若需动谓词/索引/排序/缓存/写语句即超出本卡授权，停手。
- 门禁为仓库根 `mvnw.cmd clean test`；基线锚 329/0/0/1，新增常驻护栏 3 例后精确账目 329→332、Skipped 仍为 1；两笔提交严格划分（实施笔 + 收口笔，收口笔零 src 改动凭 `git diff --name-only` 空输出豁免门禁）。
- 测量环境（一次性本地容器、单机、空并发、进程内测试上下文）结论不外推生产 MySQL/Tomcat，不构成判分或成绩发布 P99 结论。

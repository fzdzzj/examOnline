# 提案：ScoreService 读侧拆出（成绩服务读写分置，工程健康 C1）

> 状态：已立项待执行；阶段 0 查证基于 main `b32ca46`（2026-10-11）现场重核。台账 `docs/production-hardening-candidates.md` 候选 C1 的「汇总/发布/撤回/排名/预览/导出集中在 ScoreService」定性已部分失真——导出（ScoreExportService）、排名计算（RankCalculator）、复核（ScoreReviewService）、补考合并（MakeupScoreService）、榜单（ScoreLeaderboardService）、错题本（StudentWrongQuestionService）、分析报告（ExamAnalysisReportService）七类能力早已独立。本提案按用户裁决执行「读侧拆出」粒度（2026-10-11 AskUserQuestion 裁决记录）。

## Why

ScoreService 现状（`b32ca46` 实测）：512 行、5 个公开方法，全部属「成绩生命周期」单一域，但读侧与写侧混杂共类——

- **读侧**：`publishPreview`（教师预览，强一致读、无事务、M5 列投影）与 `myScore`（学生查分，强一致读、无事务、显式不加 `@DS` 从库路由）；
- **写侧**：`summarize`（汇总，CAS 写 + 状态机推进）、`publish`/`revoke`（发布/撤回，事务 + 审计 + 指标打点 + 读己之写标记）。

两类方法的事务语义、一致性口径、鉴权视角（教师归属校验 vs 学生本人）互不重叠，却共享同一构造器（9 个注入，其中 userMapper / rankCalculator / scoreReviewService 仅被读侧使用）。score 包既有七类独立 service 先例，读侧独立成类后，ScoreService 收缩为纯写侧（依赖 9→6），与「新能力独立 service」的组织惯例完全对齐。

**历史约束的显式裁决**：memory 及 MakeupScoreService javadoc 载有「补考成绩合并逻辑必须放独立类，不得修改 ScoreService 现有方法体」——该约束系 `add-makeup-final-score` 变更卡的**单卡写侧边界**（防该案顺手改 ScoreService），MakeupScoreService 已独立交付并归档，约束使命已完成。本案为用户显式立项的 ScoreService 重构变更卡，该单卡约束不适用于本案的读侧方法迁移；但 MakeupScoreService 自身的独立性不受影响（本案零触碰 `com.exam.exam.service.MakeupScoreService`）。

**已验证**（`b32ca46` 现场实测）：生产调用方唯一（ScoreController 5 端点）；测试侧 8 文件引用 scoreService；两类敏感护栏须随迁——MyScoreRankEquivalenceTest e8b 的 @DS 反射护栏（扫 ScoreService 全方法禁从库路由）与 GradingScoreProjectionGuardTest 的 M5 列投影护栏（锚定 publishPreview）；契约 schema 名 `SummarizeStats` 为 simple name 形态（openapi.yaml L2926，record 不搬家即稳定）。**未知**：读侧用例迁移时 ScoreServiceTest 造数基建的复用代价——阶段 3 现场实施验证。

## What Changes

1. **新类 `com.exam.score.service.ScoreQueryService`**：承载 `publishPreview(Long)` 与 `myScore(Long)`（方法体逐字迁移，含 M5 列投影注释、强一致读 javadoc、404 显式判定与复核隐藏逻辑）；私有 `requireOwnedExam` / `loadUsers` 内联副本（先例：ScoreController 与 ScoreService 已各持一份 requireOwnedExam 内联，不为 4 行方法建共享抽象）。依赖 5 个：ExamMapper、GradingSubmissionMapper、UserMapper、RankCalculator、ScoreReviewService。
2. **ScoreService 收缩为纯写侧**：保留 summarize（含私有 loadSubjectiveGradesBySubmission / computeSummary / Summaries record）+ publish / publishOne / recordPublish / doPublishOne + revoke / revokeOne / LoginRoleCheck + writeAudit + requireOwnedExam；构造器依赖降为 6 个（去 userMapper、rankCalculator、scoreReviewService）。**`SummarizeStats` record 原地不动**（嵌套于 ScoreService，Controller 签名 `ApiResponse<ScoreService.SummarizeStats>` 与契约 schema 名均不变）。
3. **ScoreController**：新增注入 ScoreQueryService，`publishPreview` / `myScore` 两端点改调新类；summarize / publish / revoke 端点及全部注解零改动。
4. **测试迁移与护栏同步**（用例总数只增不减）：
   - ScoreServiceTest 读侧 10 例（publishPreview 3 + myScore 7）整体迁至新文件 `ScoreQueryServiceTest`，所需 loginAs / 造数基建随迁；原文件保留写侧用例；
   - MyScoreRankEquivalenceTest：myScore 直调改指新类；**@DS 反射护栏同步扩展**——e8b 断言增加对 ScoreQueryService 的类级 + 方法级 @DS 禁令（读侧是强一致读护栏的主保护对象）；
   - ScoreServiceReviewHideTest 改注入新类；GradingScoreProjectionGuardTest 的 M5 护栏锚点与注释改指 `ScoreQueryService#publishPreview`；
   - measure IT（RankAttributionMeasureIT / MyScoreCountCaptureMeasureIT / ScalarReadsAttributionMeasureIT / GradingScoreProjectionMeasureIT）字段与直调改指新类；`(ScoreService.SummarizeStats)` 强转不动。
5. **spec 基线**：`spec/specs/score-management/spec.md` ADDED「成绩服务读写分置」Requirement（五 Scenario：读侧归属 / 写侧归属 / 强一致读护栏随类迁移 / 外部契约零变化 / 历史约束边界裁决）。

## Impact

- **规范**：score-management 能力域 ADDED 1 个 Requirement（5 Scenario）；既有 Requirement 原文零改动。
- **代码**：src/main 恰 3 文件（ScoreQueryService 新增、ScoreService 收缩、ScoreController 改注入）；src/test 约 9 文件改 + 1 新增（ScoreQueryServiceTest）；**外部契约零变化**（端点路径、schema 名、错误码、响应结构全不动，openapi.yaml 不得出现在改动清单）。
- **用户/API**：无任何可见变化。
- **数据与部署**：无（零 schema 改动、零配置改动）。

## 验收与停止条件

- 纯重构纪律：`git diff --name-only main...HEAD -- openapi.yaml` 为空；OpenApiContractTest 全绿；后端 `mvnw.cmd clean test`（仓库根）BUILD SUCCESS 且用例数只增不减（基线 398/0/0/1，迁移守恒或净增）；前端三门禁全绿持平（86 文件 / 610 例，零前端改动）。
- 三变异独立可红（执行侧自报仅供参考，以复核子 agent 独立复现击红为准）：a. 破坏 myScore 名次聚合（如去 gt 条件）→ 名次用例击红；b. 移除 publishPreview 的 M5 列投影 → M5 护栏击红；c. 给 ScoreQueryService 类或方法加 `@DS` 从库路由 → 扩展后 e8b 反射护栏击红。三复原后 git diff 逐字节归零。
- **停止条件**：① 若读侧拆出后发现与写侧存在隐藏耦合（如读方法依赖类内事务上下文或写侧状态）导致语义变化，停止并上报裁决，不为拆而拆改语义；② 若 ScoreServiceTest 读侧用例迁移须改动写侧用例或造数基建大改，停止上报；③ 若契约因拆分产生 diff（schema 名漂移等），停止上报，不得为迁就改契约文件或注解配置；④ ScoreService 方法体迁移须逐字搬运（含注释），任何顺手重构、改写、删注释都按违规处理。

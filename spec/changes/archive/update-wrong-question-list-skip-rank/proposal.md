# update-wrong-question-list-skip-rank 提案

## 背景与问题（2026-10-12 性能审计，台账候选 5）

2026-10-12 巡查（`docs/backend-optimization-candidates.md`「2026-10-12 巡查」节）确认：错题本列表路径 `StudentWrongQuestionService.listWrongQuestions`（`StudentWrongQuestionService.java:123-184`）对页内 ≤10 场考试逐场调用 `ScoreExportService.getPersonalReport`，但仅消费 `questions/scores/answers` 三项；`loadPersonalReport`（`ScoreExportService.java:413-440`）每场固定执行的 `userMapper.selectById`（`:423`）与 `rankBySubmissionId`（`:425`——全场 GRADED 答卷投影查询 + RankCalculator O(n log n) 排名）在列表响应中零消费、纯丢弃。学生每打开一页错题本即触发 10 次「全班投影查询 + 全班排名计算」，全部白做。

消费矩阵（已核全部调用方）：需要 rank 与用户字段的路径为错题本单场回顾（`StudentWrongQuestionService.java:213`）与 ScoreExportService 两处导出（`:291` 个人成绩单 PDF、`:337` 逐题明细导出）；仅列表路径（`:126`）丢弃。这是代码可证明的纯浪费（计算后丢弃、响应零变化），消除无语义风险。

## 变更内容

1. `ScoreExportService` 抽取「判分核心装配」私有方法（`readByExamId` + GRADED `selectOne` + `scoreResolver.resolve` + `parseAnswers`，即现 `loadPersonalReport` 的判分部分），新增轻量入口（如 `getPersonalReportWithoutRank`）：装配 `questions/scores/answers`，不调用 `userMapper.selectById` 与 `rankBySubmissionId`，PersonalReport 的 rank/用户/分数字段以占位值填充（或返回独立轻量 record，形态执行时定）。
2. `StudentWrongQuestionService.listWrongQuestions` 切换调用轻量入口；`reviewExam`（`:213`）与 ScoreExportService 两处导出（`:291`/`:337`）逐字不动，继续走完整口径。
3. 护栏测试：轻量入口纯 JUnit 单测——mock RankCalculator 断言零交互，且 questions/scores/answers 与完整口径一致（同源判分护栏）。

## 红线与验收边界

- **判分同源**：轻量入口与完整入口必须共用同一判分核心装配（同一私有方法或同一代码路径），绝不另造第二套判分口径（`add-student-wrong-questions` 核心约束）。
- **零改动域**：`reviewExam` 方法体、ScoreExportService 两处导出方法体、`RankCalculator`、`rankBySubmissionId` 本身、`grading/paper/submission/clazz` 域、`schema.sql`、`pom.xml` 一律零触碰。
- **响应形状零变化**：错题本列表/回顾响应字段与取值与改前逐字一致；`openapi.yaml` 零变更、paths 数零变化；前端零触碰。
- **测试总数只增不减**：后端新增用例数恰等于 gate-baseline.json backendTests 上调幅度（收口笔同笔上调）；前端用例数持平。
- **停止条件**：实施中发现判分或排名口径的隐藏耦合（如列表路径实际依赖 rank 值）立即停止并如实上报，不得绕过。

## 复核分级判定（2026-10-12 定版规则，显式声明）

本变更为**业务代码变更**（score 域读侧）→ 分级②：**全量六项本地独立复跑**保留，派发轮次采用**先复核后收口**形态——执行侧停在实施笔本地提交（不 push、不合入、不归档），复核子 agent PASS 后另行派收口（收口含 spec-delta 合入、--no-ff 合入、R100 归档、台账回填）。独立复现主线（mock 零交互构造性实证 + 既有测试零回归）不可砍。

## 验收判据

- 后端 `./mvnw clean test` 全绿，Tests run 恰增新增用例数，Failures/Errors 0。
- 新增单测证明：列表路径不再触发全班排名计算（RankCalculator 零交互）且判分三要素与完整口径一致。
- 既有错题本列表/回顾/导出全部既有用例原样全绿（响应零回归实证）。
- 实施笔 diff 仅涉及 `ScoreExportService.java`、`StudentWrongQuestionService.java` 与新增测试文件（红线域全空）。

# project-analysis-report-graded-read 提案

## 背景与问题（2026-10-12 性能审计，台账候选 6）

2026-10-12 巡查（`docs/backend-optimization-candidates.md`「2026-10-12 巡查」节候选 6）确认：`ExamAnalysisReportService.listGraded`（`src/main/java/com/exam/score/service/ExamAnalysisReportService.java:384-390`）selectList 无投影；`GradingSubmission` 实体映射 `answers`（LONGTEXT，`GradingSubmission.java:38-39`），无投影即逐行携带学生答案 JSON 取回。消费方核对：班级概览（`:185-191`）只用 totalScore；关注名单（`:352-379`）只用 studentId + totalScore；gradedStudentIds 只用 studentId——全场 answers 全程未消费，n 名已批改学生 × 数 KB 至数十 KB JSON 的全行取回与瞬时内存持有属纯浪费。教师低频端点但与仓库既定「列投影 + 常驻护栏」模式（`project-scalar-only-submission-reads`、`project-grading-score-scalar-projection` 先例）直接相悖，本案是模式复制。

## 变更内容

1. `listGraded` 加 `.select(GradingSubmission::getStudentId, GradingSubmission::getTotalScore)` 两列投影。
2. 常驻护栏测试（按「列投影 + 常驻护栏」先例形态）：防未来字段使用变更导致投影列集与消费列集漂移为 null。
3. 顺手项（台账候选 6 节预登记，不单独测量）：`buildClassOverview` 缺席计数 `submittedIds.contains`（`:174-178`，List 线性 O(应考×实考)）换 Set 查找——同一方法内数据结构替换，语义零变化。

## 红线与验收边界

- **零改动域**：`aggregateQuestionStats` 与逐题指标路径、`grading/paper/submission/clazz` 域、`ScoreExportService`、`StudentWrongQuestionService`、`schema.sql`、`pom.xml` 一律零触碰。
- **响应形状零变化**：分析报告各字段与取值与改前逐字一致；`openapi.yaml` 零变更；前端零触碰。
- **测试总数只增不减**：gate-baseline backendTests 恰增新增用例数（收口笔同笔上调）；frontendTests 611 持平、openapiPaths 74 持平。
- **停止条件**：K3 现状查证若发现 `listGraded` 消费列超出 studentId/totalScore 清单（含间接消费），立即停止并如实上报，不得缩小投影强行实施。

## 复核分级判定（2026-10-12 定版规则，显式声明）

本变更为**业务代码变更**（score 域读侧）→ 分级②：**全量六项本地独立复跑**保留，派发轮次采用**先复核后收口**形态——执行侧停在实施笔本地提交（不 push、不合入、不归档），复核 PASS 后另行派收口。独立复现主线（消费列清单亲手核证 + 投影护栏变异击红）不可砍。

## 验收判据

- 后端 `./mvnw.cmd clean test` 全绿（新基线 403 + 新增数），Failures/Errors 0，Skipped 1。
- 新增护栏测试证明投影列集 = 消费列集；变异（移除投影或扩大消费）时护栏击红。
- 既有分析报告全部既有用例（含 fix-analysis-report-pass-line 的三分制/fail-fast 用例）原样全绿——响应零回归实证。
- 实施笔 diff 仅 `ExamAnalysisReportService.java` 与新增测试文件（红线域全空）。

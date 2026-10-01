# delta：spec/specs/data-access/spec.md ——「标量读取的列投影」Requirement 追加

> 候选 delta：仅当裁决存在 GO 单元且实施验收成立时按 5.1 合入（逐 GO 单元一条 Scenario）；
> NO-GO 单元不据此判为违规。以下以 M1–M5 全 GO 为占位写法，合入时按实际 GO 单元裁剪。

## MODIFIED Requirement: 标量读取的列投影

（在既有 Requirement 的 Scenario 列表后追加以下 Scenario，不改动既有 SHALL 与场景）

#### Scenario: 判分进度与成绩读路径的投影站点（project-grading-score-scalar-projection）

GIVEN 判分进度 `GradingQueryService.progress`、成绩汇总 `ScoreService.summarize`、发布预览 `ScoreService.publishPreview` 的答卷／批改行取数经归因测量（一次性本地 MySQL 8 容器、三臂 OLD/PROJ/OLDrep 轮转、n ∈ {200,1000,3000}）裁决为 GO

WHEN 这些路径执行取数

THEN 对应 GO 站点的 SELECT 列表限定为该路径实际消费的列：

- M1 progress 主语句：`id, grading_status`
- M2 progress 主观行：`submission_id, score`
- M3 summarize 主语句：`id, grading_status, objective_score`
- M4 summarize 主观行：`submission_id, question_id, score`
- M5 publishPreview 主语句：`student_id, objective_score, subjective_score, total_score, partial_graded`

AND 不含 `answers` / `paper_json` / `student_answer` 等长字段，谓词、排序与返回行数不变

AND 三个端点的响应（GradingProgressResponse 七计数、ScorePreviewResponse 全字段与 items 逐项、summarize 的 summarized/skipped/examGraded）与全列读取逐字段等价，`casSummarize` 的调用次数与逐次参数（submissionId、subjectiveScore、totalScore、partialGraded）不变

AND 未实施投影的站点（NO-GO 单元、`forEachSubmissionPage`、判分预取 `loadSubjectiveGrades`、导出/判分/工作台等长字段消费路径）不据此判为违规、取数语句不变

> 合入注记（收口时补全）：裁决、逐单元结果、sha256、验收边界（一次性本地 MySQL 8 容器（tmpfs、高位端口、独立库、用毕销毁）+ 单机 + 空并发 + 进程内测试上下文——不外推生产 MySQL/Tomcat，不构成判分或成绩发布 P99 结论）。

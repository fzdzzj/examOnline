# score-management spec-delta：考试数据分析报告（add-exam-analysis-report）

## ADDED Requirements

### Requirement: 考试数据分析报告

系统 SHALL 提供教师对**已汇总（GRADED 及以上）考试**的只读分析报告接口
`GET /api/exams/{examId}/scores/analysis-report`（权限 `exam:manage` + 归属越权校验），返回
班级概览 / 逐题指标 / 知识点薄弱 / 学生关注名单四块结构化数据。逐题指标（平均分/得分率/答对率/
区分度/作答人数）SHALL 与题目统计导出复用**同一聚合核心**（`ScoreExportService.aggregateQuestionStats`），
口径严禁与导出分叉。班级概览 SHALL 报告应考（班级名单）/ 实考（已汇总答卷）/ 缺席（应考−有答卷）人数、
平均分、及格率（≥60 分）与分数段分布（0-59/60-69/70-79/80-89/90-100）。知识点薄弱 SHALL 按
`question_tags × tags` 聚合得分率（升序，薄弱优先）；试卷题目均无知识点标签时 SHALL 返回空数组 +
`hasTagDimension=false`，不得报错或臆测。学生关注名单 SHALL 返回低于及格线学生的学号/姓名/总分。
考试不存在 SHALL 返回 404，非归属教师 SHALL 返回 403，未汇总考试 SHALL 返回 400。

#### Scenario: 分析报告三块结构返回

GIVEN 教师对已汇总（GRADED）且有成绩的考试发起分析报告查询

WHEN 调用 `GET /api/exams/{examId}/scores/analysis-report`

THEN 返回 200 且包含班级概览 / 逐题指标 / 知识点薄弱 / 学生关注名单四块

AND 班级概览含应考/实考/缺席人数、平均分、及格率与五段分数段分布

#### Scenario: 逐题指标与导出口径同源

GIVEN 一场已汇总考试有若干题目与已判分答卷

WHEN 分析报告返回逐题指标且题目统计导出亦被请求

THEN 两者的平均分/得分率/答对率/区分度/作答人数取自同一聚合核心，口径一致

#### Scenario: 越权与存在性

GIVEN 教师 A 拥有某考试，教师 B 未拥有，且存在不存在的考试 ID

WHEN 教师 B 查询 A 的考试，或任一教师查询不存在的考试

THEN 非归属教师查询返回 403

AND 不存在的考试返回 404

#### Scenario: 未汇总考试拒绝

GIVEN 一场考试尚未执行成绩汇总（状态低于 GRADED）

WHEN 教师查询其分析报告

THEN 返回 400 且提示先执行成绩汇总

#### Scenario: 知识点维度优雅降级

GIVEN 一场考试试卷各题均未挂知识点标签

WHEN 教师查询其分析报告

THEN 知识点块返回空数组且 `hasTagDimension=false`

AND 接口不报错、前端据该标志提示「题库未打知识点标签」

#### Scenario: 知识点薄弱聚合与排序

GIVEN 一场考试至少一题挂了知识点标签且有已汇总成绩

WHEN 教师查询其分析报告

THEN `hasTagDimension=true` 且按标签聚合得分率（携带该标签题目的得分率均值）

AND 结果按得分率升序（薄弱优先）返回

#### Scenario: 学生关注名单

GIVEN 一场已汇总考试存在总分低于及格线（60）的学生

WHEN 教师查询其分析报告

THEN 关注名单返回这些学生的学号/姓名/总分，不含高于等于及格线者
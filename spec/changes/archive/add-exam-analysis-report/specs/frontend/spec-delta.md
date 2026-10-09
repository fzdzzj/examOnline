# frontend spec-delta：教师端考试数据分析报告界面（add-exam-analysis-report）

## ADDED Requirements

### Requirement: 教师端考试数据分析报告界面

教师考试详情页（`teacher/exams/[id].page.vue`）SHALL 提供「分析报告」页签，查询
`GET /api/exams/{examId}/scores/analysis-report` 并渲染班级概览（antd `Statistic` 统计卡）、
分数段分布（`Progress` 条）、逐题指标（`Table`，得分率/区分度列内联呈现）、知识点薄弱
（进度条形态）与学生关注名单（`Table`）。查询失败 SHALL 以错误 Alert 显性呈现后端 message 且
数据区隐藏；成功且返回 `hasTagDimension=false` 时知识点块 SHALL 呈现「题库未打知识点标签」提示
（非错误态）；加载中与成功空数据 SHALL 呈现对应加载/空态。本页 SHALL NOT 自动重试、SHALL NOT 自动跳转。

#### Scenario: 分析报告概览与数据区渲染

GIVEN 教师在考试详情页并切到「分析报告」页签

WHEN 分析报告查询成功且返回数据

THEN 班级概览统计卡（应考/实考/缺席/平均分/及格率）、分数段 Progress 条、逐题指标 Table、
知识点薄弱条与关注名单 Table 按返回值渲染

AND 不出现错误 Alert、不出现「题库未打知识点标签」提示

#### Scenario: 知识点维度缺失提示

GIVEN 分析报告查询成功

WHEN 返回 `hasTagDimension=false`（试卷题目均无知识点标签）

THEN 知识点块呈现「题库未打知识点标签」提示文案

AND 不当作错误态（不出现错误 Alert）

#### Scenario: 查询失败显性呈现且数据区隐藏

GIVEN 教师切到「分析报告」页签

WHEN 分析报告查询失败

THEN 错误 Alert 呈现后端 message，且概览/表格/进度条数据区隐藏

AND 不渲染为业务空态

#### Scenario: 加载态与空数据态

GIVEN 教师切到「分析报告」页签

WHEN 分析报告查询进行中

THEN 呈现加载态

AND 成功且返回空成绩（无实考答卷）时呈现业务空态而非错误
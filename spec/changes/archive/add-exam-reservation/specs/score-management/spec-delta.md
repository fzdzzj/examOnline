# add-exam-reservation spec-delta（score-management）

能力域：`spec/specs/score-management/spec.md`（收口时将下述 Scenario 追加合入既有 Requirement「考试数据分析报告」末尾，Scenario 文本逐字一致；若分析报告 Requirement 在基线中的挂载位与预判不符，以基线实际结构为准并如实登记）

变更类型：MODIFIED Requirement（追加 Scenario）

## MODIFIED Requirement: 考试数据分析报告（追加 Scenario）

#### Scenario: 应考口径随预约制分流

GIVEN 考试开启「需预约」且成绩已汇总

WHEN 教师生成考试数据分析报告

THEN 班级概览中的应考与缺席维度按该考试的有效预约名单计（与缺考域 `exam_absence` 口径同源，不得出现两套缺席数）

AND 报告其余维度（及格线/优秀线派生、分数段、逐题指标、知识点薄弱、关注名单）口径零变化；未开启预约的考试报告与既有输出逐字零回归

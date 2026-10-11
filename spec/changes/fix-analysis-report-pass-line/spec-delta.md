# spec-delta：fix-analysis-report-pass-line

> 合入目标文件：`spec/specs/score-management/spec.md` 末尾追加。ADDED 1 Requirement（六 Scenario）；既有 Requirement 零改动。

## ADDED Requirement: 分析报告及格线按试卷总分比例派生

WHEN 分析报告计算及格率、分数段分布或学生关注名单,

系统 SHALL 以试卷总分乘固定比例派生及格线与优秀线（60%/90%，HALF_UP 取整），SHALL 经响应字段下发派生值，AND SHALL 保持 100 分制试卷的既有输出零变化。

#### Scenario: 比例派生

GIVEN 任意总分的试卷

WHEN 报告生成

THEN 及格线为总分 60%、优秀线为总分 90%（HALF_UP 取整到整数分）

AND 及格率、分数段切分、学生关注名单三处消费统一使用该派生值

#### Scenario: 100 分制零回归

GIVEN 总分为 100 的试卷

WHEN 报告生成

THEN 及格线为 60、优秀线为 90

AND 及格率、分数段分布、关注名单与既有行为逐值一致

#### Scenario: 非常规总分

GIVEN 总分为 150（或 50）的试卷

WHEN 报告生成

THEN 及格线为 90（或 30）、优秀线为 135（或 45）

AND 三段分布按派生值切分而非绝对 60/90

#### Scenario: 总分不可得即拒绝

GIVEN 报告所属考试无法取到试卷总分

WHEN 报告请求

THEN 接口明确失败（fail-fast）

AND 不回退任何默认总分值

#### Scenario: 前端单源渲染

GIVEN 前端分析报告组件

WHEN 渲染关注名单文案与分数段标签

THEN 一切边界数值来自响应下发的 passLine/excellentLine 字段

AND 前端不含及格线/优秀线硬编码

#### Scenario: 契约与用例同步

GIVEN 响应新增两字段

WHEN 实施合入

THEN openapi 契约经重导出与真实实例字节级一致、前端类型同步

AND 双端用例总数只增不减且 gate-baseline 同笔上调

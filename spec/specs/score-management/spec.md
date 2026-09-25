# score-management 规范

> 能力域：成绩管理（阶段 6，W7）。
> 来源：`spec/changes/add-grading-score` 合入（成绩汇总、发布/撤回、排名、导出）。

## Requirements

### Requirement: 成绩汇总

WHEN 教师对已结束考试执行成绩汇总,

系统 SHALL 在本次纳入汇总的答卷均已完成判分且产生客观分（包括合法零分）时，汇总客观分与已批主观分得到总分；存在未判或判分失败答卷时 SHALL 在任何成绩与考试状态写入前拒绝；未批主观题仍按既有“部分批改”规则处理。主观分 SHALL 按本场答卷一次取出，而不是每份答卷各查一次。

#### Scenario: 加权汇总

GIVEN 已交卷答卷的判分状态成功且客观分已产生

WHEN 系统汇总

THEN 总分 = 客观分 + 已批主观分

#### Scenario: 判分未运行或失败则整场拒绝

GIVEN 本次待汇总答卷中至少一份未判分、判分失败或客观分未产生

WHEN 教师请求汇总

THEN 系统返回可观察的判分前置条件错误

AND 不写任何答卷总分或部分批改标记

AND 不推进考试至已批改状态

#### Scenario: 合法客观零分允许汇总

GIVEN 一份纯主观试卷已完成判分或教师已手动裁定客观零分

AND 答卷判分状态成功、客观分明确为零

WHEN 教师汇总

THEN 允许汇总并按实际已批主观分计算总分

AND 不将合法零分误判为“未判分”

#### Scenario: 主观未批完仍可部分汇总

GIVEN 客观判分已完成且试卷中仍有未批主观题

WHEN 教师汇总

THEN 未批主观题按零分计入

AND 整体标记部分批改，保持 `docs/需求决策记录.md` §7.5 的发布与复核语义

#### Scenario: 主观分一次取出

GIVEN 同一场有多份待汇总答卷且卷面含主观题

WHEN 系统汇总

THEN 这些答卷的主观分记录在进入逐份写入之前一次取出

AND 某份答卷没有主观分行时，仍按未批口径处理，不视为已批完

---

### Requirement: 成绩发布

WHEN 成绩已批改,

系统 SHALL 支持发布前预览与批量发布，状态由已批改迁至已发布。

#### Scenario: 发布前预览

GIVEN 教师完成批改

WHEN 教师查看发布预览

THEN 系统展示各学生成绩明细

#### Scenario: 批量发布

GIVEN 教师确认发布

WHEN 教师执行发布

THEN 状态迁至已发布

AND 学生可见成绩

---

### Requirement: 成绩撤回

WHEN 教师撤回已发布成绩,

系统 SHALL 需管理员权限，撤回后学生 SHALL 不再看到成绩，并 SHALL 记录审计日志。

#### Scenario: 撤回隐藏成绩

GIVEN 成绩已发布且学生已看到

WHEN 管理员执行撤回

THEN 学生端不再显示成绩

AND 记录撤回审计日志（谁/何时/原因）

---

### Requirement: 成绩排名

WHEN 系统计算排名,

系统 SHALL 按总分排序，同分 SHALL 并列同名次。

#### Scenario: 并列同名次

GIVEN 两名学生总分相同

WHEN 计算排名

THEN 两人并列同一名次

AND 后续名次跳空（1,2,2,4）

---

### Requirement: 成绩导出

WHEN 教师导出成绩,

系统 SHALL 提供全班成绩单、逐题明细、题目统计、个人成绩单，且 SHALL 以流式方式防止大数据量内存溢出。

#### Scenario: 全班成绩单导出

GIVEN 教师请求导出

WHEN 导出完成

THEN 生成含每人分数与排名的 Excel

#### Scenario: 大数据量不溢出

GIVEN 数千条成绩记录

WHEN 系统导出

THEN 采用流式写入

AND 不因内存不足失败

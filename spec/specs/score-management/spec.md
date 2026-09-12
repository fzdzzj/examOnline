# score-management 规范

> 能力域：成绩管理（阶段 6，W7）。
> 来源：`spec/changes/add-grading-score` 合入（成绩汇总、发布/撤回、排名、导出）。

## Requirements

### Requirement: 成绩汇总

WHEN 客观题与主观题均已判分,

系统 SHALL 汇总客观分与主观分得到总分。

#### Scenario: 加权汇总

GIVEN 客观分与主观分已产生

WHEN 系统汇总

THEN 总分 = 客观分 + 主观分

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

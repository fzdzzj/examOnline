# 规范差异：frontend（监考轮询按页签门控）

本文件包含对 `spec/specs/frontend/spec.md` 的规范变更：往既有 Requirement「监考与行为日志界面」新增 Scenario「非消费页签不轮询」（MODIFIED 形态，完整替换该 Requirement 块，其余 Requirement 不动）。

## MODIFIED Requirements

### Requirement: 监考与行为日志界面

WHEN 考试进行中或已结束,

系统 SHALL 提供提交进度与可疑行为事件的查询界面，且 SHALL 如实描述数据新鲜度。

#### Scenario: 进度可见

GIVEN 一场进行中的考试

WHEN 教师查看监考视图

THEN 呈现提交进度

#### Scenario: 行为事件可追溯

GIVEN 某学生产生了切屏等行为事件

WHEN 教师按学生查看行为日志

THEN 呈现事件类型、严重程度与时间线

#### Scenario: 不夸大数据新鲜度

GIVEN 监考视图通过轮询获取数据

WHEN 界面描述该视图

THEN 使用「准实时轮询」等如实措辞

AND 不声称实时推送

#### Scenario: 观测面板不重做

GIVEN 已有 Grafana 观测面板

WHEN 教师需要查看系统指标

THEN 前端提供只读入口链接

AND 不在前端重做同口径图表

#### Scenario: 非消费页签不轮询

GIVEN 监考总览数据仅被「监考」与「考生名单与进度」页签消费

WHEN 教师停留在其余页签（概览 / 试卷快照 / 行为日志）

THEN 监考总览轮询暂停，不发出请求

AND 切回消费页签时恢复轮询并刷新数据

AND 轮询间隔与「准实时轮询」措辞不变

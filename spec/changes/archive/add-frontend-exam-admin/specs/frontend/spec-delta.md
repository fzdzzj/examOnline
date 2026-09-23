# 规范差异：frontend（班级管理、考试考务与监考界面）

本文件包含对 `spec/specs/frontend/spec.md` 的规范变更（新增）。

## ADDED Requirements

### Requirement: 班级管理界面

WHEN 教师或管理员需要维护班级与学生归属,

系统 SHALL 提供班级增删改查与学生入班、转班的操作界面。

#### Scenario: 班级可维护

GIVEN 存在若干班级

WHEN 用户执行班级新增、修改或删除

THEN 界面反映后端最新结果

#### Scenario: 学生归属可调整

GIVEN 一名学生已属于某班级

WHEN 执行转班操作

THEN 原班级与新班级的学生列表同步更新

### Requirement: 考试创建与发布界面

WHEN 教师需要组织一场考试,

系统 SHALL 提供绑定试卷、班级、时间窗与考试参数的创建界面，且 SHALL 提供发布与提前结束操作。

#### Scenario: 创建考试参数齐备

GIVEN 一份已组好的试卷与一个班级

WHEN 教师填写时间窗、个人时长、迟到允许与防作弊配置

THEN 仅呈现后端已支持的配置字段

AND 提交后考试创建成功

#### Scenario: 状态以后端为准

GIVEN 考试处于某一状态

WHEN 界面渲染考试列表

THEN 状态与可用操作均来自后端返回

AND 前端不自行推算状态迁移

#### Scenario: 发布生成快照

GIVEN 考试尚未发布

WHEN 教师确认发布

THEN 后端生成试卷快照

AND 界面对学生侧可见性做出说明

#### Scenario: 提前结束需知情确认

GIVEN 考试进行中

WHEN 教师执行强制结束

THEN 界面在确认前明示该动作会触发缺考标记

AND 教师确认后才发起请求

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

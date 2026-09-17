# 规范差异：frontend（考后闭环界面）

本文件包含对 `spec/specs/frontend/spec.md` 的规范变更（新增）。

## ADDED Requirements

### Requirement: 主观题批改工作台

WHEN 教师批改主观题,

系统 SHALL 提供逐题打分与评语的工作台，且 SHALL 使并发批改冲突对教师可见。

#### Scenario: 待批改队列可用

GIVEN 存在已交卷但未批改的答卷

WHEN 教师进入批改工作台

THEN 呈现待批改队列并支持分页与筛选

#### Scenario: 逐题打分与评语

GIVEN 教师打开一份答卷

WHEN 对主观题逐题打分并填写评语

THEN 提交后成绩与评语被保存

#### Scenario: 并发冲突不静默覆盖

GIVEN 两名教师同时批改同一份答卷

WHEN 后提交者触发版本冲突

THEN 界面提示该答卷已被他人批改并刷新内容

AND 不静默覆盖先提交者的结果

AND 该行为由自动化测试守住

### Requirement: 成绩发布、撤回与导出界面

WHEN 教师需要对外公布成绩或导出成绩单,

系统 SHALL 提供发布前预览、批量发布、撤回与导出入口，且 SHALL 按后端权限与状态渲染可用动作。

#### Scenario: 发布前可预览

GIVEN 一场考试已完成批改

WHEN 教师准备发布

THEN 先呈现待发布成绩预览

AND 确认后才执行发布

#### Scenario: 动作可用性由后端决定

GIVEN 考试处于某一状态且教师具有某一角色

WHEN 界面渲染发布、撤回等操作入口

THEN 仅呈现后端允许的动作

AND 前端不自行放宽权限

#### Scenario: 导出由后端流式生成

GIVEN 教师请求导出成绩单

WHEN 导出执行

THEN 由后端流式生成文件

AND 前端仅负责触发与下载

AND 前端不取全量数据在本地拼装表格

### Requirement: 缺考与补考界面

WHEN 考试结束需要处理缺考学生,

系统 SHALL 提供缺考名单查看与补考创建入口，且 SHALL NOT 呈现后端尚未提供的补考最终成绩合并结果。

#### Scenario: 缺考名单可见

GIVEN 一场考试已结束

WHEN 教师查看缺考名单

THEN 呈现被标记缺考的学生

AND 自然到点与强制结束两条路径产生的标记均可见

#### Scenario: 补考可创建

GIVEN 某学生缺考或需要补考

WHEN 教师创建补考

THEN 补考作为独立考试记录建立

AND 准入范围按后端返回渲染

#### Scenario: 不声称最终成绩合并可用

GIVEN 后端尚未接线补考最终成绩计算

WHEN 界面呈现补考相关结果

THEN 不展示「主考与补考合并后的最终成绩」

AND 不声称该能力已完成

### Requirement: 成绩查询与复核界面

WHEN 学生查询成绩或申请复核,

系统 SHALL 使成绩可见性与复核资格完全依据后端判定，且 SHALL 呈现复核全流程状态。

#### Scenario: 成绩可见性以后端为准

GIVEN 一场考试的成绩处于已发布或复核中

WHEN 学生查询成绩

THEN 可见性按后端返回渲染

AND 前端不本地推断是否处于复核中

#### Scenario: 复核资格不在前端计数

GIVEN 复核申请存在次数与时间窗限制

WHEN 学生进入复核申请

THEN 剩余次数与窗口状态取自后端

AND 前端不本地计数判定资格

#### Scenario: 复核流程状态可查

GIVEN 学生已提交复核申请

WHEN 学生查看申请

THEN 呈现待处理或已处理状态

#### Scenario: 复核结果可见

GIVEN 教师已处理复核申请

WHEN 学生查看结果

THEN 呈现处理结果与调整说明

# add-exam-reservation spec-delta（frontend）

能力域：`spec/specs/frontend/spec.md`（收口时 ADDED Requirement 合入该基线末尾，Scenario 文本逐字一致）

变更类型：ADDED Requirement

## ADDED Requirement: 学生预约与消息中心界面

#### Scenario: 学生考试列表预约入口

GIVEN 学生考试列表中的「需预约」考试项

WHEN 考试已发布未开始且本人未预约

THEN 列表项呈现预约入口；预约成功后呈现已预约状态与取消入口；考试开始后入口与取消均不可用

AND 非「需预约」考试项不呈现预约入口，列表既有分组与排序行为零变化

#### Scenario: 消息中心与未读角标

GIVEN 学生已登录

WHEN 导航壳加载或学生打开消息中心

THEN 消息铃铛呈现未读数，消息中心按倒序列出本人消息，学生可将消息标记已读且未读数联动减少

AND 消息状态不在前端本地持久化（未读数以服务端为准）

#### Scenario: 教师建考表单预约开关

GIVEN 教师创建普通考试

WHEN 填写建考表单

THEN 表单呈现「需预约」开关（默认关闭），开关值随建考请求提交并正确回填于编辑表单

AND 前端用例断言为直断形态（命中真实 data-test 属性，禁 if/exists 守卫与 || 双通兜底）

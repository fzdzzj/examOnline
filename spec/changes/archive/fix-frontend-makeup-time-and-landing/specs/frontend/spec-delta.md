# frontend spec-delta：补考时间控件与落地页收官（fix-frontend-makeup-time-and-landing）

## ADDED Requirements

### Requirement: 补考时间控件与规范化表单校验

教师端创建补考时，开始时间与结束时间 SHALL 渲染为带有时间选择功能的日期时间选择器（`DatePicker`，`show-time`），SHALL NOT 使用自由文本输入框（`Input`）。表单校验 SHALL 统一使用 Form rules 进行管理：开始时间与结束时间为必填项，且结束时间必须晚于开始时间；未填写或起止时间倒置时 SHALL 在表单项下方呈现校验错误，SHALL NOT 在提交时使用 `message.warning` 作为双轨拦截。提交补考创建请求时，时间值 SHALL 格式化为与 `toIsoLocalDateTime` 输出逐字一致的 ISO-8601 本地日期时间字符串（`YYYY-MM-DDTHH:mm:ss`），契约与后端解析口径保持严格等价。

#### Scenario: 时间控件渲染与自由文本移除

GIVEN 教师进入补考管理页
WHEN 观察创建补考表单
THEN 开始时间与结束时间渲染为 DatePicker 组件
AND 旧 placeholder 自由文本 Input 不存在

#### Scenario: 时间格式转换等价性

GIVEN 教师在日期时间选择器中选定开始与结束时间
WHEN 触发表单提交
THEN 提交至后端的请求体中的时间字符串与原 `toIsoLocalDateTime` 处理结果逐字一致（带字面量 `T`）

#### Scenario: 表单 rules 校验拦截

GIVEN 补考表单时间为空或结束时间早于等于开始时间
WHEN 尝试提交表单
THEN Form rules 拦截提交并呈现错误提示，且不向后端发起创建请求

---

### Requirement: 仪表盘落地页与常用入口

用户登录后的根落地页（`/`）SHALL 呈现欢迎卡与常用快捷入口，SHALL NOT 包含开发期验证卡片（「这一页验证了什么」）。欢迎卡 SHALL 基于既有 auth store 渲染当前用户的显示名与角色标签，SHALL NOT 新增后端数据请求。常用入口 SHALL 与侧边栏已有路由和角色权限过滤逻辑保持一致（学生呈现考试与成绩入口、教师呈现考试/批改/组卷等入口、管理员呈现邀请码管理入口），SHALL NOT 引入未在系统注册的新路由。

#### Scenario: 开发验证文案清除

GIVEN 用户访问系统根路径落地页
THEN 页面中不再出现「这一页验证了什么」及阶段 19 开发验证清单

#### Scenario: 角色自适应的欢迎与常用入口渲染

GIVEN 用户以特定角色（如学生、教师或管理员）登录
WHEN 渲染落地页
THEN 欢迎卡展示该用户的显示名与角色
AND 常用入口区域呈现该角色有权访问的快捷入口，点击可正确导航至对应页面

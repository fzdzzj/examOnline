# frontend 规范

> 能力域：前端（阶段 19 起的前端系列）。
> 来源：`spec/changes/archive/add-frontend-must-change-guard` 合入（强制改密前端守卫，提案④，2026-09-21；接回阶段 19 因契约缺字段而移出的「强制改密前置」需求）+ `spec/changes/archive/add-frontend-student-taking` 合入（学生端在线考试：作答界面/服务端时间倒计时/草稿保存与断线恢复/交卷防重配合/切屏检测与上报/交卷结果如实呈现，阶段 22，2026-09-23）+ `spec/changes/archive/add-frontend-exam-admin` 合入（教师端考务：班级管理界面/考试创建与发布界面/监考与行为日志界面，阶段 21，2026-09-23）。
> 实施注记：
> - 本文件由**首个收尾的前端变更**创建（spec/README.md 约定：谁先收尾谁建目录）；阶段 19–23 五份 spec-delta 待各自验收收尾后按 Requirement 标题逐个追加，不预建空壳。
> - 判定收敛在 `frontend/src/router/access.ts` 的 `decideNavigation` 单一函数，`guard.ts` 只做接线；前端守卫不是安全边界，后端 `@RequireRole` 才是权限的唯一裁决者。
> - `mustChangePassword` 来自 `/api/auth/me`（`Boolean` 装载、恒有值、`non_null` 不会吞掉 `false`）；守卫按 `=== true` 分支，缺省按 false 处理，不误拦正常用户。
> - 不本地持久化「已改密」标记；改密成功后走既有会话刷新路径（`clearTokens → login → fetchProfile`）自动放开，会话刷新即重判。
> - 单测在 `frontend/src/router/__tests__/access.spec.ts`（三分支 + 不误拦用例）。
> - 阶段 22 注记：切屏「离开时长」为单调时钟差（`performance.now()`），非本机时刻；离开只暂存、回归才报一条（后端 count 按条数累计，一次离开一条不多报）；交卷结果只呈现后端返回，「客观题即时出分」为条件式场景（后端返回得分时才呈现，否则如实「待批改」）。
> - 阶段 21 注记：考试状态机权威在后端（`ExamStateMachineService`），前端只按接口返回渲染状态与可用动作；监考数据新鲜度只声称「准实时轮询」（10s 轮询），不声称实时。**组件坑**：ant-design-vue 4 的 `Alert` 只渲染 `message`/`description` 插槽，默认插槽被静默丢弃——发布/force-end 确认弹窗正文曾因此不可见（阶段 21 验收时由用例暴露并修复，另两处阶段 23 残留随其复核处置）；弹窗正文必须走具名插槽。

## Requirements

### Requirement: 强制改密前端守卫

WHEN 已登录用户的会话信息中 `mustChangePassword` 为 true,

系统 SHALL 将其可导航范围限制在改密与登出，且 SHALL 在改密完成后自动恢复导航能力。

#### Scenario: 未改密用户被拦

GIVEN admin 以初始密码登录且未改密

WHEN 尝试导航到业务页面

THEN 被重定向到改密页

AND 不渲染业务功能

#### Scenario: 改密完成后恢复

GIVEN 用户在改密页完成改密

WHEN 会话信息刷新

THEN 守卫按新会话数据放开

AND 可正常导航

#### Scenario: 判定只有单一入口

GIVEN 任意路由切换

WHEN 守卫求值

THEN 判定只发生在路由守卫的单一决策函数内

AND 不存在旁路入口

#### Scenario: 恒有值字段直接判断

GIVEN /api/auth/me 响应

WHEN 读取 mustChangePassword 字段

THEN 该字段恒有值

AND 前端直接按布尔值分支，false 不被误判为必须改密

---

### Requirement: 在线考试作答界面

WHEN 学生进入一场已发布的考试,

系统 SHALL 呈现其个人试卷快照并提供作答与导航能力，且 SHALL 保持界面极简不夹带范围外工具。

#### Scenario: 进入即锁定个人快照

GIVEN 学生首次进入考试

WHEN 界面加载完成

THEN 呈现该学生的个人试卷快照

AND 刷新后题目集合不发生变化

#### Scenario: 作答与导航可用

GIVEN 学生正在作答

WHEN 使用题目导航与上一题/下一题

THEN 已答、未答与当前题目状态可区分

AND 四种题型的作答控件各自可用

#### Scenario: 会话唯一性由后端判定

GIVEN 同一学生在同一考试已有进行中会话

WHEN 再次尝试进入

THEN 按后端返回的错误码提示

AND 前端不自行实现会话锁

#### Scenario: 界面不夹带范围外工具

GIVEN 作答界面

WHEN 检查其功能

THEN 不含计算器与标记备注

AND 不含离线模式、人脸识别、设备指纹、浏览器锁定

---

### Requirement: 倒计时以服务端时间为准

WHEN 学生在限时考试中作答,

系统 SHALL 基于服务端时间呈现剩余时长，且 SHALL NOT 以本地时间作为交卷判定依据。

#### Scenario: 剩余时长来自服务端

GIVEN 学生进入考试

WHEN 界面呈现倒计时

THEN 剩余时长由服务端时间推算

#### Scenario: 本地时间被篡改不影响判定

GIVEN 学生修改本机时间

WHEN 到达服务端认定的截止时刻

THEN 服务端仍按超时处理

AND 前端不因本地时间提前或延后判定

#### Scenario: 归零锁定并待同步

GIVEN 本地倒计时归零

WHEN 界面响应

THEN 锁定作答能力并标记为待同步

AND 网络恢复后按锁定状态提交

AND 服务端对提交时刻仍做兜底校验

#### Scenario: 临近截止有警告

GIVEN 剩余时长进入警告窗口

WHEN 倒计时到达该阈值

THEN 界面给出显式警告

---

### Requirement: 草稿保存与断线恢复

WHEN 学生在作答过程中遭遇网络中断,

系统 SHALL 保证已作答内容不丢失，且 SHALL 在恢复后按保守规则合并草稿。

#### Scenario: 定时保存草稿

GIVEN 学生正在作答

WHEN 到达自动保存周期

THEN 草稿被提交至后端

AND 保存频率不低于后端设计假设（不因按键频繁触发）

#### Scenario: 中断期间本地留存

GIVEN 网络中断

WHEN 学生继续作答

THEN 作答内容写入本地缓存

AND 界面提示当前处于未同步状态

#### Scenario: 恢复后保守合并

GIVEN 后端草稿与本地缓存同时存在

WHEN 执行合并

THEN 以时间戳或版本号较新的一方为准

AND 不静默覆盖学生答案

AND 合并规则由自动化测试守住

#### Scenario: 不声称离线考试

GIVEN 断线期间本地留存答案

WHEN 描述该能力

THEN 表述为断线不丢答案与恢复后同步

AND 不声称支持完整离线考试模式

---

### Requirement: 交卷防重配合

WHEN 学生提交答卷,

系统 SHALL 避免同一意图产生多次提交请求，且 SHALL 以后端返回作为交卷结果的唯一权威。

#### Scenario: 提交意图去重

GIVEN 学生点击交卷

WHEN 请求尚未返回

THEN 提交入口立即进入不可再次触发状态

#### Scenario: 双击与弱网重试不产生重复

GIVEN 学生连续点击或弱网重试

WHEN 请求到达后端

THEN 不产生重复交卷结果

AND 该行为有可复现的演示证据

#### Scenario: 结果以后端为准

GIVEN 交卷请求已发出

WHEN 界面呈现结果

THEN 依据后端返回判定成功与否

AND 不以本地标记代替后端结果

#### Scenario: 失败不丢答案

GIVEN 交卷请求失败

WHEN 界面响应

THEN 保留已作答内容

AND 允许学生再次提交

#### Scenario: 超时自动交卷同源

GIVEN 考试超时

WHEN 触发自动交卷

THEN 与手动交卷走同一提交路径

AND 同样受防重与后端幂等约束

---

### Requirement: 切屏检测与上报

WHEN 学生在考试期间离开作答页面,

系统 SHALL 记录并上报可疑行为事件，且 SHALL 只弹警告而不自动强制交卷。

#### Scenario: 事件被记录

GIVEN 学生切换标签页或窗口失焦

WHEN 事件发生

THEN 记录次数与时长并上报后端

#### Scenario: 只警告不作废

GIVEN 切屏次数达到某一阈值

WHEN 界面响应

THEN 弹出警告

AND 不自动强制交卷

AND 是否作废由教师事后依据行为日志判定

#### Scenario: 不虚报事件

GIVEN 浏览器事件语义存在歧义

WHEN 上报事件

THEN 只上报能确证的事件

AND 不虚报事件类型或严重程度

---

### Requirement: 交卷结果如实呈现

WHEN 学生交卷后查看结果,

系统 SHALL 区分已出分与待批改部分，且 SHALL NOT 呈现未经后端确认的分数。

#### Scenario: 客观题即时出分

GIVEN 后端已完成客观题判分

WHEN 学生查看结果

THEN 呈现客观题得分

#### Scenario: 主观题待批改如实展示

GIVEN 主观题尚未批改

WHEN 学生查看结果

THEN 呈现待批改状态

AND 不猜测或预估分数

---

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

---

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

---

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

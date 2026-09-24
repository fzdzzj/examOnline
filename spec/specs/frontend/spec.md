# frontend 规范

> 能力域：前端（阶段 19 起的前端系列，19–23 五阶段全量）。
> 来源：`spec/changes/archive/add-frontend-skeleton-auth` 合入（前端工程底座/API 契约驱动集成/会话与令牌生命周期/角色路由守卫，阶段 19，2026-09-23）+ `spec/changes/archive/add-frontend-teacher-authoring` 合入（题库管理界面/组卷界面/前端不复现判分口径，阶段 20，2026-09-23）+ `spec/changes/archive/add-frontend-must-change-guard` 合入（强制改密前端守卫，提案④，2026-09-21；接回阶段 19 因契约缺字段而移出的「强制改密前置」需求）+ `spec/changes/archive/add-frontend-student-taking` 合入（学生端在线考试：作答界面/服务端时间倒计时/草稿保存与断线恢复/交卷防重配合/切屏检测与上报/交卷结果如实呈现，阶段 22，2026-09-23）+ `spec/changes/archive/add-frontend-exam-admin` 合入（教师端考务：班级管理界面/考试创建与发布界面/监考与行为日志界面，阶段 21，2026-09-23）+ `spec/changes/archive/add-frontend-post-exam` 合入（考后闭环：主观题批改工作台/成绩发布撤回导出/缺考与补考/成绩查询与复核，阶段 23，2026-09-23）。
> 实施注记：
> - 本文件由**首个收尾的前端变更**创建（spec/README.md 约定：谁先收尾谁建目录）；阶段 19–23 五份 spec-delta 待各自验收收尾后按 Requirement 标题逐个追加，不预建空壳。
> - 判定收敛在 `frontend/src/router/access.ts` 的 `decideNavigation` 单一函数，`guard.ts` 只做接线；前端守卫不是安全边界，后端 `@RequireRole` 才是权限的唯一裁决者。
> - `mustChangePassword` 来自 `/api/auth/me`（`Boolean` 装载、恒有值、`non_null` 不会吞掉 `false`）；守卫按 `=== true` 分支，缺省按 false 处理，不误拦正常用户。
> - 不本地持久化「已改密」标记；改密成功后走既有会话刷新路径（`clearTokens → login → fetchProfile`）自动放开，会话刷新即重判。
> - 单测在 `frontend/src/router/__tests__/access.spec.ts`（三分支 + 不误拦用例）。
> - 阶段 22 注记：切屏「离开时长」为单调时钟差（`performance.now()`），非本机时刻；离开只暂存、回归才报一条（后端 count 按条数累计，一次离开一条不多报）；交卷结果只呈现后端返回，「客观题即时出分」为条件式场景（后端返回得分时才呈现，否则如实「待批改」）。
> - 阶段 21 注记：考试状态机权威在后端（`ExamStateMachineService`），前端只按接口返回渲染状态与可用动作；监考数据新鲜度只声称「准实时轮询」（10s 轮询），不声称实时。**组件坑**：ant-design-vue 4 的 `Alert` 只渲染 `message`/`description` 插槽，默认插槽被静默丢弃——发布/force-end 确认弹窗正文曾因此不可见（阶段 21 验收时由用例暴露并修复，另两处阶段 23 残留已随其复核修复）；弹窗正文必须走具名插槽。
> - 阶段 23 注记：批改冲突（1012/409）只提示并拉最新行、绝不自动重试提交（`useGradingFlow`）；成绩导出（含个人成绩单 xlsx/pdf）是后端流式导出，前端只触发与 blob 下载；补考最终成绩合并（`MakeupScoreService.finalScore`）后端零调用，前端不展示不声称（遗留 #5，须后端立项）；学生端无「查本人复核申请列表」端点，申请结果以成绩卡片状态呈现——以上接口缺口均如实告知、不前端变通；阶段 23 范围的两处 Alert 默认插槽残留（发布确认/复核申请弹窗）在复核时修复并有用例取证。

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

---

### Requirement: 主观题批改工作台

WHEN 教师对已结束的考试运行判分并批改主观题,

系统 SHALL 提供显式判分入口、逐题打分与评语的工作台，且 SHALL 使判分失败与并发批改冲突对教师可见。

#### Scenario: 显式运行判分后进入队列

GIVEN 考试已结束且未汇总，存在已交卷答卷

WHEN 教师在批改工作台触发运行判分

THEN 前端仅调用现有后端判分端点一次并防重复点击

AND 真实呈现成功、失败与总数，刷新题级进度及学生行

AND 判分失败清单可见，不把部分失败报成全成功

#### Scenario: 空答卷与无主观题不误导

GIVEN 选择了已结束的考试

WHEN 判分端点返回总数为零或试卷无主观题

THEN 总数为零如实提示无已交卷答卷，不声称判分成功

AND 无主观题仅免除人工主观批改，不免除客观判分入口

#### Scenario: 已汇总与已发布无假重判入口

GIVEN 考试状态为已批改或已发布

WHEN 教师查看批改工作台

THEN 前端不把“运行判分”显示为可对所有已批改答卷整场重判的动作

AND 已发布考试遵守后端撤回前不得判分的既有边界

#### Scenario: 汇总被拒绝可回到判分

GIVEN 教师从成绩页尝试汇总，后端因未判/失败答卷拒绝

WHEN 前端接收错误

THEN 显示可观察的拒绝原因及前往批改工作台的操作指引

AND 不在前端自行判分或假定汇总成功

#### Scenario: 待批改队列可用

GIVEN 一场含主观题的考试存在已交卷但未批改的答卷

AND 教师已运行判分且后端已生成对应主观题批改行

WHEN 教师进入或刷新批改工作台

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

---

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

---

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

---

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

---

### Requirement: 前端工程底座

WHEN 需要为已交付的后端能力提供可演示的 Web 界面,

系统 SHALL 提供与后端同仓库的前端工程，且 SHALL 使构建、类型检查、代码规范检查与测试各自可独立跑通。

#### Scenario: 工程可复现启动

GIVEN 一份干净检出

WHEN 按记录命令安装依赖并启动开发服务

THEN 前端可访问且能通过代理调用后端接口

#### Scenario: 质量门禁可跑

GIVEN 前端代码存在

WHEN 运行 lint、type-check 与单元测试

THEN 三者均通过

#### Scenario: 前端基线不影响后端基线

GIVEN 前端测试与后端 Maven 测试并存

WHEN 各自运行

THEN 前端测试不计入后端 surefire 计数

AND 后端基线不因前端变更而下降

### Requirement: API 契约驱动集成

WHEN 前端调用后端接口,

系统 SHALL 以导出的 OpenAPI 契约生成类型化客户端，且 SHALL NOT 手写与契约重复的接口定义。

#### Scenario: 客户端由契约生成

GIVEN openapi.yaml 存在

WHEN 运行生成命令

THEN 产出类型化客户端供页面调用

#### Scenario: 代理保留后端路径前缀

GIVEN 后端端点自身带 /api 前缀

WHEN 前端开发代理转发请求

THEN 不剥离该前缀

AND 真实请求返回业务响应而非 404

#### Scenario: 统一响应解包

GIVEN 后端返回统一响应结构

WHEN 前端收到响应

THEN 由拦截层统一解包

AND 业务错误码呈现为可读提示而非原始报文

### Requirement: 会话与令牌生命周期

WHEN 用户在前端持有访问令牌,

系统 SHALL 使令牌过期后可自动续期，且 SHALL 在续期失败时清理本地会话。

#### Scenario: 并发过期只刷新一次

GIVEN 多个请求同时收到未授权响应

WHEN 触发令牌刷新

THEN 刷新只发生一次

AND 其余请求排队等待刷新结果后重放

#### Scenario: 刷新失败即登出

GIVEN 刷新令牌已失效

WHEN 刷新请求失败

THEN 清理本地令牌与用户状态

AND 跳转登录页

#### Scenario: 登出主动失效

GIVEN 用户点击登出

WHEN 前端执行登出

THEN 调用后端使令牌进入黑名单

AND 本地会话被清理

### Requirement: 角色路由守卫

WHEN 不同角色用户访问前端路由,

系统 SHALL 按角色渲染可用入口并拦截越权访问，且 SHALL NOT 以前端守卫作为安全边界。

#### Scenario: 按角色渲染入口

GIVEN 已登录用户具有某一角色

WHEN 进入应用

THEN 仅呈现该角色可用的菜单与路由

#### Scenario: 越权访问被拦截

GIVEN 用户访问不属于其角色的路由

WHEN 路由守卫执行

THEN 跳转无权限页

AND 不呈现该页面内容

#### Scenario: 安全边界在后端

GIVEN 前端守卫被绕过

WHEN 直接调用后端接口

THEN 后端角色校验仍然拒绝

AND 前端代码中明确记录该取舍

### Requirement: 题库管理界面

WHEN 教师需要维护题目,

系统 SHALL 提供题目检索、录入、修改与软删除的界面，且 SHALL 按题型提供对应的录入校验。

#### Scenario: 按条件检索题目

GIVEN 题库中存在多道题目

WHEN 教师按题型、标签或关键词筛选

THEN 列表按分页呈现匹配结果

#### Scenario: 题型差异化录入

GIVEN 教师选择某一题型

WHEN 填写题目表单

THEN 仅呈现该题型适用的字段

AND 该题型的必要校验在提交前生效

#### Scenario: 软删除语义以后端为准

GIVEN 一道题目已被软删除

WHEN 教师查看题库

THEN 其可见性与可操作性由后端返回决定

AND 前端不将已删题目呈现为可用

### Requirement: 组卷界面

WHEN 教师需要产出试卷,

系统 SHALL 提供手动选题与标签随机抽题两条路径，且 SHALL 支持逐题分值覆盖与总分汇总；已有题目的试卷详情 SHALL 实际呈现题目表。

#### Scenario: 手动组卷可排序与改分

GIVEN 教师已选入若干题目

WHEN 调整题号顺序或单题分值

THEN 试卷内容与总分实时更新

#### Scenario: 随机抽题结果可确认

GIVEN 教师配置了标签、题型与数量

WHEN 触发抽题

THEN 前端呈现抽中结果

AND 教师可重抽或确认入卷

#### Scenario: 随机算法不在前端

GIVEN 需要按标签抽题

WHEN 前端发起抽题

THEN 由后端决定抽中题目

AND 前端不复现随机算法

#### Scenario: 试卷可只读预览

GIVEN 一份已组好的试卷

WHEN 教师查看试卷详情

THEN 呈现题目内容与分值分布

#### Scenario: 详情页题目表真实可见

GIVEN 试卷中已有题目

WHEN 教师打开试卷详情

THEN 页面实际呈现题目表及题目内容，而非仅显示题目计数或分值分布

AND 试卷未锁定时呈现改分、排序与移出入口

AND 试卷已锁定时呈现只读题目内容

### Requirement: 前端不复现判分口径

WHEN 前端处理题目答案录入,

系统 SHALL 仅做格式校验，且 SHALL NOT 在前端实现答案归一化或判分规则。

#### Scenario: 归一化归后端

GIVEN 教师录入判断题答案

WHEN 前端提交

THEN 按后端约定的原始格式提交

AND 归一化由后端完成

#### Scenario: 两端不漂移

GIVEN 判分口径需要调整

WHEN 修改发生

THEN 仅后端变更

AND 前端无需同步修改判分逻辑

---

> 合入注记（2026-09-23，`accept-frontend-19-23` 收口批次，阶段 19/20 的 delta 补合入）：
> 五阶段 Requirement 至此全部入基线。**已知缺陷如实登记**（真机走查 `frontend/docs/frontend-stages-walkthrough.md`，
> `59bab7b`）：①「组卷界面」的 *手动组卷可排序与改分* 与 *试卷可只读预览* 两个 Scenario 当前不成立——
> `teacher/papers/[id].page.vue` 漏 `import { Table }`（引入提交 `060b012`，交付即坏，控制台
> `[Vue warn]: Failed to resolve component: Table`、题目表零渲染），登记遗留 #17 待立项修复；
> ②「主观题批改工作台」缺判分入口——`grading/run` 已生成到 SDK 但无页面调用，判分前
> `totalStudents=0`，教师纯靠 UI 进不了批改队列（连带「跳过判分汇总按 0」分支未实走），登记遗留 #18。
> 门禁：lint/type-check exit=0、vitest 38 文件 331 例（`59bab7b` 指导 agent 复跑）；
> 后端树与已门禁 `b1fa8a2` 一致（293/0/0/1）。走查覆盖与残留数据清单见走查记录原文。

> D1 收口注记（2026-09-23，变更 `fix-frontend-paper-table-render`，修复提交 `f0e1288`）：
> 组件级红绿回归与提交后 frontend 门禁已通过；真实 Chromium + dev 后端复验补证了 20-3b 的加题、改分、调序、移出及刷新后状态（专用草稿 17，验证后已清理），并补证了 20-5 的锁定只读预览（专用永久试卷 18 / 快照 1，题目 9/8，申报总分 15）。20-5 的快照生成是测试准备接口调用，不作为前端按钮能力。主 Agent 另行完成只读数据库复核；历史试卷 14 与已软删除试卷 17 未被用于写操作。归档截图位于该变更的 `evidence/` 目录。
> 原走查 `59bab7b` 中 `[Vue warn]: Failed to resolve component: Table`、题目表零渲染的历史记录保留不改；本次证据表明 D1 两个场景现已成立，遗留 #17 关闭。D2（遗留 #18）保持不变。

> D2 收口注记（2026-09-25，变更 `fix-grading-entry-and-summary-prerequisite`，推荐 A）：
> 批改工作台显式运行判分入口与成绩汇总写前护栏（未判/失败答卷在任何写入前整场拒绝；合法客观零分与 §7.5 主观部分批改保留），连同成绩页前置错误的「前往批改工作台」指引，已在隔离测试中验收（后端集成/服务层用例 + 前端页面测试 + 已提交 HEAD 全量门禁）；**未跑共享 dev 真汇总**，真实 Chromium 亦未执行，不得视为真机已验。原走查 `59bab7b` 的历史 D2 记载保留不改；原遗留 #18 关闭。

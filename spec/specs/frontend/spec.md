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
> - 阶段 23 注记：批改冲突（1012/409）只提示并拉最新行、绝不自动重试提交（`useGradingFlow`）；成绩导出（含个人成绩单 xlsx/pdf）是后端流式导出，前端只触发与 blob 下载；补考最终成绩展示已由 `add-makeup-final-score-frontend`（2026-09-25 归档）收口：教师 makeups 按学生查询、学生 scores 显式切换口径三态（考试列表无 parentExamId，前端不猜补考），合并规则仍在后端、前端只渲染返回值不本地推算；学生端无「查本人复核申请列表」端点，申请结果以成绩卡片状态呈现——该接口缺口如实告知、不前端变通；阶段 23 范围的两处 Alert 默认插槽残留（发布确认/复核申请弹窗）在复核时修复并有用例取证。

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

#### Scenario: 倒计时响应窄化不深层监听快照

GIVEN 倒计时以整卷响应（含题目列表等非时间字段）作为快照来源

WHEN 非时间字段发生任何更新

THEN 倒计时引擎不重新锚定，剩余秒数按单调时长平滑递减、不跳跃不重置

AND 时间字段（remainingSeconds / deadlineTime / serverTime）任一变化时才重新锚定

AND 倒计时对快照的监听依赖 SHALL 窄化为时间三字段投影，SHALL NOT 对整卷快照做深层遍历

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

#### Scenario: 非消费页签不轮询

GIVEN 监考总览数据仅被「监考」与「考生名单与进度」页签消费

WHEN 教师停留在其余页签（概览 / 试卷快照 / 行为日志）

THEN 监考总览轮询暂停，不发出请求

AND 切回消费页签时恢复轮询并刷新数据

AND 轮询间隔与「准实时轮询」措辞不变

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

#### Scenario: 考试下拉累积多页不丢前页

GIVEN 教师的考试总数超过单页容量

WHEN 教师进入批改工作台选择考试

THEN 前端按页累加拉取直到取完（返回空或未满一页即到底），下拉列出完整候选

AND 后续页的写入不覆盖、不丢弃已累积的前页

AND 达到安全页数上限时停止，不陷入死循环

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

#### Scenario: 考试下拉不截断为第一页

GIVEN 教师的考试总数超过单页容量

WHEN 教师进入成绩页选择考试

THEN 下拉按统一策略累积多页、列出完整候选，不限于第一页

AND 该取数策略与其余考后页面（批改 / 复核 / 缺考 / 补考）同源，不各自叠加实现

---

### Requirement: 缺考与补考界面

WHEN 考试结束需要处理缺考学生,

系统 SHALL 提供缺考名单查看、补考创建与补考最终成绩展示，且 SHALL NOT 在前端本地推算补考合并规则。

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

#### Scenario: 补考最终成绩展示

GIVEN 后端已提供补考最终成绩端点（沿主考家族按考试配置规则合并，历史成绩保留不覆盖）

WHEN 教师或学生查看补考关联成绩

THEN 呈现后端返回的最终成绩

AND 合并规则完全由后端计算，前端不本地推算

#### Scenario: 学生侧复核语义同构

GIVEN 学生查询本人补考最终成绩

WHEN 后端返回 reviewing 为 true

THEN 隐藏分数（防「看了分数再申请」），与 myScore 口径同构

AND 未发布统一按后端「成绩待发布」渲染

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

### Requirement: 查询失败与业务态分离

页面数据查询失败 SHALL 显性呈现错误态（Alert 承载后端 message），SHALL NOT 渲染为业务空态或「成绩待发布」业务态；「成绩待发布」SHALL 仅由后端 400 固定文案判别（`utils/scoreVisibility.ts` 的 `isNotPublishedError`），前端 SHALL NOT 放宽该判别或引入第二套本地推断；既有业务空态文案 SHALL 保持原样（成功且无数据时照常呈现）。

#### Scenario: 学生成绩页失败显性呈现

GIVEN myScore 或补考最终成绩查询返回非「成绩待发布」错误

WHEN 页面渲染

THEN 错误 Alert 呈现后端 message

AND 未发布卡片不出现（失败不得伪装成「成绩未发布」业务态）

#### Scenario: 待发布仅认后端 400 文案

GIVEN 后端返回 400「成绩待发布」

WHEN 页面渲染

THEN 仍渲染未发布业务态、不弹错误 Alert（回归保护，判别不放宽）

#### Scenario: 教师侧列表失败不伪装空态

GIVEN 复核申请 / 缺考名单 / 补考候选人查询失败

WHEN 页面渲染

THEN 错误 Alert 呈现后端 message 且既有空态文案不出现

AND 成功且无数据时空态文案原样保留

#### Scenario: 补考最终成绩查询失败同口径

GIVEN 教师侧补考最终成绩查询失败

WHEN 页面渲染

THEN 错误 Alert 呈现后端 message，成功空值提示文案保持原样

### Requirement: 考试编辑与删除入口

教师考试列表 SHALL 为「未开始且未发布」的考试提供「编辑」入口（与「发布考试」同源乐观门控）、为「未发布」的考试提供「删除」入口（乐观放宽，最终裁决在后端）；编辑 SHALL 复用创建页（route query 携带 examId 时呈现「编辑考试」）并经详情端点回填、提交调用 `PUT /api/exams/{id}`，删除 SHALL 经知情确认弹窗（形态对齐「强制结束」）调用 `DELETE /api/exams/{id}`；操作失败 SHALL 原文呈现后端 message，SHALL NOT 本地编造失败文案或拦截请求；操作成功 SHALL 失效列表查询刷新（对齐发布/强制结束的既有做法）。

#### Scenario: 操作列乐观门控

GIVEN 列表行按后端返回的 status/published 渲染

WHEN 行为「未开始 + 未发布」

THEN 「编辑」与「删除」均出现

AND 行为「进行中 + 未发布」或「已结束 + 未发布」时仅「删除」出现（编辑不出现，删除乐观放宽）

AND 行为「已发布」时两者均不出现

#### Scenario: 编辑复用创建页并回填

GIVEN 教师点击「编辑」进入 create?examId=&lt;id&gt;

WHEN 页面加载

THEN 标题呈现「编辑考试」且详情端点返回值回填全部表单字段（含防作弊两开关）

AND 提交调用 `PUT /api/exams/{id}`（path 携带 examId）成功后跳回列表并失效 exams 查询

#### Scenario: 删除知情确认

GIVEN 教师点击「删除」

WHEN 确认弹窗呈现

THEN 弹窗明示删除不可逆（形态对齐「强制结束」弹窗）

AND 确认后调用 `DELETE /api/exams/{id}` 并失效 exams 查询

#### Scenario: 失败原文呈现不本地编造

GIVEN 后端拒绝编辑或删除（如非未开始考试的删除请求）

WHEN 操作失败

THEN message 呈现后端返回的原始 message，且不跳转、不失效查询

### Requirement: 邀请码管理界面

管理端 SHALL 提供邀请码管理页（`/admin/invite-codes`，落既有 `/admin` 角色分区）：列表按后端返回字段渲染（状态按 `status` 呈现，前端不推算）、经 `listInviteCodes` 取数；生成 SHALL 经表单提交 `createInviteCode`（字段按契约），成功后新码在任何列表刷新前于当前弹层内一次性可见并可复制；作废 SHALL 经知情确认弹窗（形态对齐考试删除）调用 `invalidateInviteCode`；查询失败 SHALL 以 Alert 显性呈现后端 message 且 SHALL NOT 伪装空态；操作失败 SHALL 原文呈现后端 message，SHALL NOT 本地编造失败文案或拦截请求；生成 / 作废成功 SHALL 失效邀请码列表查询刷新。

#### Scenario: 列表按后端字段渲染

GIVEN 后端返回邀请码数组（含有效与已作废行）

WHEN 页面渲染

THEN 邀请码 / 备注 / 状态 / 已使用次数 / 创建时间均按返回值呈现

AND 作废入口仅在有效（status=0）行出现

#### Scenario: 生成后新码一次性可见可复制

GIVEN 管理员填写备注并提交生成

WHEN `createInviteCode` 返回新邀请码

THEN 弹层内展示新码且提供复制入口

AND 邀请码列表查询被失效刷新

#### Scenario: 作废知情确认

GIVEN 管理员点击有效行的「作废」

WHEN 确认弹窗呈现

THEN 弹窗明示作废不可逆（形态对齐考试删除弹窗）

AND 确认后调用 `invalidateInviteCode`（path 携带该行 id）并失效列表查询

#### Scenario: 查询失败不伪装空态

GIVEN 列表查询失败

WHEN 页面渲染

THEN 错误 Alert 呈现后端 message，空态不出现

AND 成功且无数据时空态照常呈现

#### Scenario: 失败原文呈现不本地编造

GIVEN 生成或作废被后端拒绝

WHEN 操作失败

THEN message 呈现后端返回的原始 message，且不失效查询、不展示伪成功结果

---

### Requirement: 下拉与列表取数不截断

教师端需要完整候选集的下拉与需要分页呈现的列表 SHALL NOT 把「一次请求的一页」当作全量：分页信封无 `total` 的候选下拉 SHALL 经统一累加器取数（满页续拉、空页或未满页即停、最多 5 页、单页失败保留已累积部分且不抛错）；列表页 SHALL 使用服务端分页（当前页进入 `queryKey`、翻页发起真实请求），SHALL NOT 一次取回后在客户端切片；列表查询失败 SHALL 以 Alert 显性呈现后端 message 且 SHALL NOT 伪装成空态；取数 SHALL 使用 `types.gen.ts` 导出的 SDK 函数与契约声明的分页参数，SHALL NOT 手写 URL。既有考试下拉的累积语义 SHALL 不因本 Requirement 的实现抽取而改变。

#### Scenario: 累加器逐页累积到到底

GIVEN 候选端点返回裸列表且无 total 信封

WHEN 第 1 页返回满页（size=100）、第 2 页返回 35 条

THEN 累加结果为 135 条且只发起 2 次请求

AND 首页即返回未满一页时只发起 1 次请求、不再续拉

#### Scenario: 累加器保护上限与单页失败保留

GIVEN 连续 5 页都返回满页

WHEN 累加到第 5 页

THEN 停止续拉（最多 5 页保护上限），返回已累积的 500 条

GIVEN 第 2 页请求失败

THEN 保留第 1 页已累积数据返回、不抛错、不阻断页面渲染

#### Scenario: 考务创建页试卷与班级下拉取全量候选

GIVEN 试卷与班级各 135 条（分两页返回）

WHEN 考试创建页加载两个下拉

THEN 试卷下拉与班级下拉都经累加器发起 2 次请求

AND 135 条全部为该下拉的可选项

#### Scenario: 转班目标下拉取全量班级

GIVEN 班级 135 条（分两页返回）且转班弹层已打开

WHEN 转班目标班级下拉加载

THEN 发起 2 次请求、目标选项覆盖全部班级（仅排除当前班级本身）

#### Scenario: 试卷列表翻页发起真实请求

GIVEN 试卷列表按服务端分页显示第 1 页 10 条（后端共 250 条）

WHEN 用户点第 2 页

THEN `queryKey` 随当前页变化并重新发起请求（`page: 2`）

AND 表格渲染的是第 2 页返回的行，源码不再存在一次取回后的本地切片

AND 分页器在满页时至少预留下一页（无 total 信封下做下界推断，不谎称精确总数）

#### Scenario: 列表查询失败不伪装空态

GIVEN 试卷列表查询失败

WHEN 页面渲染

THEN 错误 Alert 呈现后端 message，表格与其空态不出现

AND 成功且无数据时空态照常呈现

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

> 合入注记（2026-10-07，变更 `fix-frontend-query-failure-states`，UX 台账 U-1 收口）：
> 「查询失败与业务态分离」Requirement 合入（4 个 Scenario，文本与该卡 `specs/frontend/spec-delta.md` 逐字一致）。
> 实施边界：四处接线——student/scores（myScore 与补考最终成绩两查询）、teacher/reviews、teacher/absences、
> teacher/makeups（候选人与最终成绩两查询），错误态以 Alert 承载后端 message（形态抄 `StudentExamList.vue`
> 「空态与错误态分开」既有范式）；既有业务空态文案零改动；`utils/scoreVisibility.ts` 纯函数与类型、
> `ScoreVisibilityCard` 零改动；零契约变更零 `gen:api`。spec-delta 未明说的一处实现口径如实登记：
> 页面 queryFn 对「成绩待发布」的归一（catch → null → not-published）保持原样，页面对 error 槽内的值
> 再判别一次 `isNotPublishedError` 属防回归双保险，不是第二套判别口径。
> 验收边界=实施笔 `df7ed0a` 已提交状态双端门禁：frontend lint:check/type-check:check/test 三项退出码 0、
> vitest 47 文件 403 例 → 51 文件 414 例（+4 文件 +11 例，先红 6 failed | 9 passed 后绿，红灯恰为新增失败态
> 断言与词法护栏）；backend `mvnw.cmd clean test` 368/0/0/1 + BUILD SUCCESS（零后端改动，与基线 3b7783e 持平）。
> 纯静态 + 单测可证：未启 dev server、未跑前端构建、未跑真实 Chromium。

> 合入注记（2026-10-07，变更 `add-frontend-exam-edit-delete`，前端台账 F-1+F-2 收口）：
> 「考试编辑与删除入口」Requirement 合入（4 个 Scenario，文本与该卡 `specs/frontend/spec-delta.md` 逐字一致）。
> 实施边界：teacher/exams/index.page.vue 操作列新增「编辑」（与「发布考试」同源门控，跳 create?examId=&lt;id&gt;）
> 与「删除」（乐观放宽为仅未发布，含进行中/已结束的未发布行），删除经声明式确认弹窗（形态对齐「强制结束」：
> v-model:open Modal + warning Alert 明示不可逆，正文走 #message 具名插槽）调 delete2；create.page.vue 编辑模式
> （route query examId → 标题「编辑考试」、detail2 回填全部表单字段、提交改调 update2、成功失效 exams 查询并跳回
> 列表）。spec-delta 未明说的实现口径如实登记：①「乐观口径」的准确含义——按钮显隐不构成越权写入口，后端
> `ExamService.assertEditable`（未发布且未开始）才是裁决者，进行中/已结束未发布行的删除请求会被 400 拒绝，
> 失败 message 原文呈现（不本地拦截、不编造文案），用例以 mock rejection 固化该行为；②编辑模式请求体始终携带
> description（空串 = 清空描述），创建模式保留既有「空描述不携带」口径零改动；③antiCheatConfig 契约类型是
> JsonNode（unknown），回填按创建写入的键防御性读取、缺失回落新建默认值；④操作列宽度 230 → 320（容纳新增按钮）。
> 验收边界=实施笔 `5279168` 已提交状态双端门禁：frontend lint:check/type-check:check/test 三项退出码 0、
> vitest 51 文件 414 例 → 52 文件 421 例（+1 文件 +7 例，先红 7 failed | 5 passed 后绿，红灯恰为新增编辑/删除
> 断言）；backend `mvnw.cmd clean test` 368/0/0/1 + BUILD SUCCESS（零后端改动，与基线 04d7b69 持平；
> `git diff --name-only 04d7b69 5279168 -- src pom.xml schema.sql openapi.yaml` 为空）。
> 纯静态 + 单测可证：未启 dev server、未跑前端构建、未跑真实 Chromium。

> 合入注记（2026-10-07，变更 `add-frontend-invite-code-admin`，前端台账 F-4 收口）：
> 「邀请码管理界面」Requirement 合入（5 个 Scenario，文本与该卡 `specs/frontend/spec-delta.md` 逐字一致）。
> 实施边界：新页 `admin/invite-codes/index.page.vue`——Card + Table（按后端字段渲染）+ 查询失败 Alert 与数据区
> 分离（U-1 三态分离口径）+ 生成 Modal（成功后同弹层展示新码可复制）+ 作废知情确认弹窗（形态对齐考试删除：
> 声明式 Modal + warning Alert 明示不可逆，#message 具名插槽）；`(dashboard).page.vue` 导航 admin 分区由
> disabled 占位「管理端（未开放）」真实化为 `canAccess(role, '/admin/')` 门控的「邀请码管理」入口
> （NAVIGABLE_PATHS 同步增补）；`access.ts` 分区结构、注册页与 errorMap 零改动，审计日志/踢人端点不接
> （后台账保留）。spec-delta 未明说的实现口径如实登记：①空备注不携带 body（`body: note ? { note } : {}`，
> 对齐创建考试「空描述不携带」口径）；②新码直接以 `createInviteCode` 响应在弹层内一次性展示，不另发补看
> 请求（列表列的邀请码原文同按后端返回呈现）；③状态标签按后端 `status` 直读（0 绿「有效」/其余 default
> 「已作废」），前端不推算；④作废入口 fail-closed——仅 `status===0` 行渲染，非有效行不提供入口。
> 验收边界=实施笔 `b7cdabe` 已提交状态双端门禁：frontend lint:check/type-check:check/test 三项退出码 0、
> vitest 52 文件 421 例 → 53 文件 429 例（+1 文件 +8 例，先红 exit 1（模块解析失败：页面尚不存在、0 例执行，
> 红态性质如实登记）后绿 8/8；复制入口断言经变异校验可红）；backend `mvnw.cmd clean test` 368/0/0/1 +
> BUILD SUCCESS（零后端改动，与基线 c762508 持平；`git diff --name-only c762508 b7cdabe -- src pom.xml
> schema.sql openapi.yaml` 为空）。
> 纯静态 + 单测可证：未启 dev server、未跑前端构建、未跑真实 Chromium。

> 合入注记（2026-10-07，变更 `fix-frontend-list-truncation-family`，UX 台账 U-2 截断家族收口）：
> 「下拉与列表取数不截断」Requirement 合入（6 个 Scenario，文本与该卡 `specs/frontend/spec-delta.md` 逐字一致）。
> 实施边界：新增 `hooks/fetchAllPages.ts`（分页信封缺 total 时的逐页累加语义通用形态），
> `useTeacherExams.ts` 的 `fetchAllTeacherExams` 改为一行委托（签名、`TEACHER_EXAMS_*` 常量与
> `createTeacherExamsQueryOptions` 零改动）；考务创建页试卷/班级两个下拉、班级页转班目标下拉改走该累加器；
> 试卷列表页改服务端分页（`pageNum/pageSize` 进 computed `queryKey` + `@change` 翻页真实请求，
> 移除 `FETCH_SIZE` 本地切片）并按 U-1 加查询失败 Alert。零后端改动、零契约变更零 `gen:api`。
> spec-delta 未明说的实现与解释口径如实登记：①**失败态按载体分别对齐既有口径**——下拉沿用考试下拉家族
> 已在基线的「尽力而为」语义（单页失败保留已累积、不抛错、不阻断页面，改造成整块红 Alert 会动到五个
> 在用消费点的形态，属越界），只有列表页用 U-1 错误 Alert（失败不得渲染成空表/空态）；②试卷列表分页器
> `total` 采「满页即至少还有下一页」的**下界推断**，未照抄考试页的字面 `total: pageNum * pageSize`——按 antd
> `vc-pagination/Pagination.js` 的 `calculatePage = floor((total-1)/pageSize)+1` 与 `hasNext = current < calculatePage`，
> 字面写法在满页时算出 1 页、下一页按钮根本点不到，「翻页真实请求」这条断言将无从触发；变异校验已做
> （去掉 `+1` 预留即转红）；③该推断意味着页码数字可能小于真实末页（不谎称精确总数）；④数据量级**未测量**
> ——dev 库数据量不构成分布证据，收口判据与考试下拉同为「取数不截断」，与量级无关；⑤委托抽取后
> `useTeacherExams.ts` 模块头注释同步改写为「累加语义本体在 `fetchAllPages.ts`」，否则其自述会变为假；
> ⑥既有 `examCreatePublishFlow.spec.ts` 的 `useQuery` mock 不走 `queryFn`，其班级候选夹具由单页信封
> 改为累加后的数组（4 行 diff，断言集合与口径零改动）。
> 随本卡入库的测试稳定性处置（与 U-2 需求无关，可整块回滚）：`gradingServerPaging.spec.ts` 两条重交互用例
> 在**未改动的 main@31dfc17** 上连续三次全量跑越界（`Error: Test timed out in 5000ms`；诊断轮实测 5034ms
> 与 3465ms，定向单跑整文件 9 例仅 3929ms——CPU 争抢放大致边际超时，非逻辑缺陷），按派发卡「第三抖触发专项」
> 为其补显式 15000ms 上限；判据收窄过程如实登记——首轮只抬「实测越界那一条」，实施笔门禁中同文件第二条
> 用例以同机理越界（第 4 次抖动），遂同因同处置，其余三条（实测 1865/1820/965ms，≥2.7 倍余量）不动。
> 验收边界=实施笔 `d78269e` + 专项续笔 `719f22d` 已提交状态双端门禁：frontend lint:check/type-check:check/test
> 三项退出码 0、vitest 53 文件 429 例 → 57 文件 449 例（+4 文件 +20 例；先红 exit 1 '9 failed | 1 passed (10)'，
> 其中新增累加器 spec 因模块尚不存在而在解析层失败、0 例执行，红态性质如实登记；唯一绿例为既有
> `enabled` 门控行为保留用例）；`useTeacherExams.spec.ts` 19 例在委托改造后原样全绿（委托等价性护栏）；
> 抖动收口按重复测量判定——续笔处置后连续两次全量全绿（对照处置前 4 轮全量越界）。backend
> `mvnw.cmd clean test` 368/0/0/1 + BUILD SUCCESS（零后端改动，与基线 `31dfc17` 当次实测持平；
> `git diff --name-only 31dfc17 719f22d -- src pom.xml schema.sql openapi.yaml` 为空）。
> 纯静态 + 单测可证：未启 dev server、未跑前端构建、未跑真实 Chromium。

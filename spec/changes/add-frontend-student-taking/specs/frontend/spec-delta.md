# 规范差异：frontend（学生端在线考试）

本文件包含对 `spec/specs/frontend/spec.md` 的规范变更（新增）。

## ADDED Requirements

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

# 规范差异：frontend（倒计时监听依赖窄化）

> 目标基线：`spec/specs/frontend/spec.md` 的「倒计时以服务端时间为准」Requirement。本 delta 只追加一个 Scenario，既有四个场景与 Requirement 头部原文不动。

## MODIFIED Requirements

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

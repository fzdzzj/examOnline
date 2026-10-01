# exam-taking 规范差异：进入考试的班级准入强校验

> 已合入（2026-10-01）：本 delta 内容与当时合并入 `spec/specs/exam-taking/spec.md` 的「进入考试」Requirement 及其 Scenario 一致（MODIFIED「进入考试」Requirement + 5 个新增 Scenario）。
> 措辞为判定式：不写裸计数与端口；准入判据以代码与状态机为唯一出处。

## MODIFIED Requirements

### Requirement: 进入考试

WHEN 学生进入考试, 系统 SHALL 校验考试处于进行中；对**首次进入**（该考试尚无本人答卷）的学生 SHALL 执行准入校验——普通考试（非补考）要求考试已指派班级且学生当前属于该班级，补考要求学生在名单内——校验通过后 SHALL 生成个人快照锁定题目与顺序，并 SHALL 以点击开始时间启动个人倒计时。学生已有本场答卷时 SHALL 直接放行进入答题上下文（断线重连/刷新恢复），SHALL NOT 因事后班级归属变动拒绝。

#### Scenario: 进入考试成功

GIVEN 考试处于进行中

AND 学生尚未开始该考试

WHEN 学生点击进入并开始

THEN 系统生成个人快照（锁定题目与选项顺序）

AND 记录 start_time 启动个人倒计时

#### Scenario: 刷新不换题

GIVEN 学生已进入考试并锁定快照

WHEN 学生刷新或重新进入

THEN 系统返回与首次一致的题目与顺序

AND 不重新抽题

#### Scenario: 非进行中禁止进入

GIVEN 考试未开始或已结束

WHEN 学生尝试进入考试

THEN 系统拒绝进入并返回对应错误

## ADDED Scenarios

#### Scenario: 班级内学生进入普通考试

GIVEN 一场进行中的普通考试已指派班级 A

AND 学生当前属于班级 A

AND 学生尚未开始该考试

WHEN 学生点击进入并开始

THEN 系统生成个人快照并启动个人倒计时

#### Scenario: 非本班学生跨班级进入被拒

GIVEN 一场进行中的普通考试已指派班级 A

AND 学生当前不属于班级 A

WHEN 学生尝试进入考试

THEN 系统拒绝进入并返回 403

AND 错误提示指明其不属于该考试指定的班级

AND 系统不为其创建答卷

#### Scenario: 未指派班级的普通考试禁止进入

GIVEN 一场进行中的普通考试未指派班级

WHEN 学生尝试进入考试

THEN 系统拒绝进入并返回 403

AND 错误提示指明该考试未指派班级

#### Scenario: 补考按名单准入

GIVEN 一场进行中的补考有指定名单

WHEN 名单内学生与名单外学生分别尝试进入

THEN 名单内学生进入成功

AND 名单外学生被拒并返回 403

#### Scenario: 已进入学生断线重进放行

GIVEN 学生已首次进入考试并生成答卷

WHEN 学生被移出该考试班级后刷新或重新进入

THEN 系统放行进入并返回与首次一致的快照与草稿

AND 不重新抽题
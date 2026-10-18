# 规范差异：exam-management 域增加考中应急顺延与个性化补时

> **能力域**：`exam-management`  
> **对应变更**：`add-exam-time-extension`

---

## ADDED Scenarios

### Requirement: 考中应急顺延与个性化补时

系统必须提供考中应急顺延与个性化补时端点 `POST /api/exams/{id}/extend-time`，支持归属教师或管理员在突发状况（机房断电、网络抖动、考生电脑死机等）下，对正在进行中的考试实施全场顺延或针对指定在途考生精准补时。

#### Scenario: 全场考试应急顺延（Global Extension）
- **GIVEN** 考试处于进行中状态（`status = 1`）
- **WHEN** 归属教师提交未指定考生名单（`studentIds` 为空或 null）的顺延请求，设定顺延时长 N 分钟（1 ≤ N ≤ 240）
- **THEN** 系统更新该考试主表：`end_time` 延后 N 分钟，`duration_minutes` 增加 N 分钟
- **AND** 批量更新该考试下所有仍处于进行中（`status = 1`）答卷的 `deadline_time`，顺延 N 分钟
- **AND** 系统记录 `EXAM_EXTEND_TIME` 安全审计日志（包含操作人、IP、模式、实际顺延时长与在途答卷受影响数）
- **AND** 旁路向该考试关联学生投递延时站内通知
- **AND** 清除该考试的详情缓存

#### Scenario: 指定在途考生个性化补时（Individual Compensation）
- **GIVEN** 考试处于进行中状态（`status = 1`）
- **WHEN** 归属教师指定特定考生 ID 列表提交补时请求，补时时长为 N 分钟
- **THEN** 考试主表 `end_time` 与 `duration_minutes` 保持不变
- **AND** 仅针对指定考生仍处于进行中（`status = 1`）的答卷执行 `deadline_time` 顺延 N 分钟
- **AND** 已交卷学生及其他未指定考生的答卷截止时间保持不变
- **AND** 系统记录 `EXAM_EXTEND_TIME` 安全审计日志并在摘要中明确标记为个人补时模式及受影响学生

#### Scenario: 非进行中考试拒绝顺延与补时
- **GIVEN** 考试处于未开始（`status = 0`）或已结束（`status = 2`）或已批改等非进行中状态
- **WHEN** 教师发起顺延或补时请求
- **THEN** 系统前置拦截并拒绝执行，返回 HTTP 400 与业务错误提示
- **AND** 考试主表及任何答卷记录不发生修改

#### Scenario: 水平越权教师顺延他人考试被拦截
- **GIVEN** 教师试图对非自己创建且无管理权限的考试发起顺延或补时请求
- **WHEN** 提交请求
- **THEN** 系统归属权守卫前置拦截，返回 HTTP 403 FORBIDDEN
- **AND** 考试及答卷数据不产生任何变更

#### Scenario: 顺延时长参数校验拦截
- **GIVEN** 顺延时长 `extensionMinutes` 为 null、小于 1 或大于 240 分钟
- **WHEN** 提交请求
- **THEN** 系统通过 Bean Validation 前置拦截，返回 HTTP 400 参数校验错误

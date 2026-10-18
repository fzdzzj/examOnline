# authentication 规范差异（spec-delta）

## MODIFIED Requirements

### Requirement: 安全事件审计落库

> 修改说明：在既有登录、账户锁定安全事件审计基础上，扩展覆盖考务核心高危写操作安全审计（考试发布/撤回/删除、成绩发布/撤回、用户强制踢人下线），实现考务全生命周期关键操作留痕追溯。

#### Scenario: 考务生命周期关键操作安全审计
- **GIVEN**: 教师或管理员执行考务关键写操作（发布考试、删除考试、发布成绩、撤回成绩、管理员踢人下线）
- **WHEN**: 业务操作成功完成
- **THEN**: 系统 SHALL 旁路向 `audit_log` 写入对应的操作审计记录（动作类型分别为 `EXAM_PUBLISH`、`EXAM_DELETE`、`SCORE_PUBLISH`、`SCORE_REVOKE`、`USER_KICK`）
- **AND**: 审计记录 SHALL 包含当次操作人的用户 ID、用户名、客户端真实 IP、链路 traceId 与操作详情（关联的业务 ID、考试标题或撤回原因）
- **AND**: 审计写入失败 SHALL NOT 阻断或回滚主业务事务

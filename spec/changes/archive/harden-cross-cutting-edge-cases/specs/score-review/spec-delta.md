# score-review 规范变更

## MODIFIED Requirements

### Requirement: 复核处理

#### Scenario: 复核处理 CAS 原子更新与并发冲突防守 (harden-cross-cutting-edge-cases)

GIVEN 存在成绩复核申请
WHEN 教师处理成绩复核申请
THEN 系统 SHALL 通过状态条件 `status IN (STATUS_PENDING, STATUS_PROCESSING)` 进行原子 CAS 更新
AND 若因并发重复操作导致受影响行数为 0，系统 SHALL 抛出业务异常返回 HTTP 400 Bad Request 拒绝重复处理，防止并发下状态覆盖或重复通知

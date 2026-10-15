# spec-delta: class-management (harden-core-security-and-state-boundaries)

### Requirement: 班级学生管理

#### Scenario: 学生转班目标班级所有权水平越权拦截 (ADDED)
- **WHEN**: 教师调用学生转班接口（`PUT /api/classes/{id}/students/{userId}/transfer`），传入的源班级属于该教师，但目标班级（`targetClassId`）属于其他教师且操作人非管理员
- **THEN**: 系统 SHALL 拒绝该操作，返回 HTTP 403 Forbidden，禁止跨教师将学生强行转移至他人班级
- **AND**: 若源班级与目标班级相同（`classId.equals(targetClassId)`），系统 SHALL 拒绝该操作，返回 HTTP 400 Bad Request 提示源班级与目标班级不能相同

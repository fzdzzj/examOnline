# class-management 规范变更

## ADDED Requirements

### Requirement: 班级删除引用保护

WHEN 教师或管理员请求删除班级,

系统 SHALL 检查是否存在考试（`exams.class_id = classId`）引用该班级；若存在引用，系统 SHALL 拒绝删除并返回 HTTP 400 Bad Request，防止关联考试的班级 ID 悬挂及缺考应考名单推导异常。

#### Scenario: 班级被考试引用时拒绝删除 (harden-cross-cutting-edge-cases)

GIVEN 某班级已有考试关联（`exams.class_id = id`）
WHEN 教师调用删除班级接口（`DELETE /api/classes/{id}`）
THEN 系统拒绝删除，返回 HTTP 400 Bad Request，提示信息明确说明该班级已被考试引用
AND `user_class` 关联与 `classes` 记录均不被删除

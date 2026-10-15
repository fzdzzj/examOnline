# spec-delta: exam-management (add-exam-lifecycle-notifications)

### Requirement: 考试发布

#### Scenario: 考试发布向关联班级学生旁路推送通知 (ADDED)
- **WHEN**: 教师发布一场已绑定班级的考试（`POST /api/exams/{id}/publish`，状态迁为未开始且 published=1）
- **THEN**: 系统 SHALL 旁路获取该班级的全部学生 ID（`user_class` 关联列表）
- **AND**: 系统 SHALL 为每位班级学生生成一条站内消息（title="考试发布通知"，content 包含试卷标题与开考时间，examId 为该考试 ID，read_flag=0）
- **AND**: 消息生成过程中的任何异常 SHALL 被旁路捕获并降级记录，绝不影响或回滚考试发布事务

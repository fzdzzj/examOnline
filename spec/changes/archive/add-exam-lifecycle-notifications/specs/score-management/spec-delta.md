# spec-delta: score-management (add-exam-lifecycle-notifications)

### Requirement: 成绩发布

#### Scenario: 成绩发布向实考考生旁路推送通知 (ADDED)
- **WHEN**: 教师发布一场考试成绩（`POST /api/scores/publish`，状态由已批改迁为已发布）
- **THEN**: 系统 SHALL 旁路获取该考试的全部实考考生 ID（`exam_submissions` 中 status=GRADED 的学生）
- **AND**: 系统 SHALL 为每位实考考生生成一条站内消息（title="成绩发布通知"，content 包含试卷标题与成绩查询指引，examId 为该考试 ID，read_flag=0）
- **AND**: 消息生成过程中的任何异常 SHALL 被旁路捕获并降级记录，绝不影响或回滚成绩发布事务

---

### Requirement: 成绩复核

#### Scenario: 成绩复核处理完成向申请学生旁路推送通知 (ADDED)
- **WHEN**: 教师处理完成学生的成绩复核申请（`POST /api/scores/reviews/{id}/handle`，动作可为 AGREE 或 REJECT）
- **THEN**: 系统 SHALL 为申请该复核的学生生成一条站内消息（title="成绩复核结果通知"，content 包含处理结果已同意/已驳回，若同意且调分包含调整后总分信息，examId 为该考试 ID，read_flag=0）
- **AND**: 消息生成过程中的任何异常 SHALL 被旁路捕获并降级记录，绝不影响或回滚复核处理事务

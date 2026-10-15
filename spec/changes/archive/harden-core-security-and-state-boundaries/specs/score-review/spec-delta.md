# spec-delta: score-review (harden-core-security-and-state-boundaries)

### Requirement: 成绩复核申请

#### Scenario: 未参加考试或未完成批改的学生拒绝申请复核 (ADDED)
- **WHEN**: 学生调用复核申请接口（`POST /api/exams/{examId}/score-reviews`），但该学生在本次考试中无有效答卷、未参加考试或答卷尚未处于已批改（STATUS_GRADED）且总分非空状态
- **THEN**: 系统 SHALL 拒绝该操作，返回 HTTP 400 Bad Request，错误信息明确说明未参加本场考试或成绩尚未批改完成，无法申请复核

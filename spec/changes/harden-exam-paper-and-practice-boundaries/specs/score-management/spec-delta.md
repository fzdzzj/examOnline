# spec-delta: score-management (harden-exam-paper-and-practice-boundaries)

## MODIFIED Requirements

### Requirement: 错题重练与消灭计划 (add-wrong-question-practice)

#### Scenario: 原题被软删除后从考试快照平滑回退判分 (ADDED)
- **WHEN**: 学生提交重练题目作答（`POST /api/scores/my/wrong-questions/practice/submit`），且该题目在题库中已被教师逻辑软删除（`is_deleted=1`）
- **THEN**: 系统 SHALL 自动从该学生所属且包含该题的已发布考试快照（`ExamSnapshot`）中装配该题目完整的类型、选项与标准答案，并基于既有客观题判分策略执行同源判分
- **AND**: 判分结果正常记录 `practice_attempt` 明细、连对次数与消灭状态正常更新，响应返回该题的正确答案与解析，保证历史错题练习链路不中断

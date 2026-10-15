# spec-delta: question-bank (harden-exam-paper-and-practice-boundaries)

## MODIFIED Requirements

### Requirement: 试卷克隆与模板复用 (add-paper-template-clone)

#### Scenario: 试卷克隆产物标题超长安全截断 (ADDED)
- **WHEN**: 教师克隆或另存为模板一张试卷，其原标题长度使得追加 `（副本）` 后总长度超过 128 字符（`papers.title VARCHAR(128)`）
- **THEN**: 系统 SHALL 自动截断原标题，使得 `截断后的标题 + "（副本）"` 的总长度严格等于 128 字符，绝不因字段长度溢出而抛出数据库异常或截断失败

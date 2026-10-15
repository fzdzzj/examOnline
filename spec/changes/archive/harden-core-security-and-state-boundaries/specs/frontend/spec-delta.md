# spec-delta: frontend (harden-core-security-and-state-boundaries)

### Requirement: 主观题批改工作台 (add-frontend-post-exam)

#### Scenario: 主观题列表查询失败显性呈现与三态分离 (ADDED)
- **WHEN**: 教师进入批改工作台，主观题题目列表查询接口（`GET /api/exams/{examId}/grading/subjective/questions`）返回网络错误或 HTTP 500 异常
- **THEN**: 页面 SHALL 显性展示错误 Alert 并提示具体错误原因（`data-test="questions-error"`）
- **AND**: 页面 SHALL NOT 将该错误伪装为空数组展示为「该试卷没有主观题，无需人工批改」的信息提示

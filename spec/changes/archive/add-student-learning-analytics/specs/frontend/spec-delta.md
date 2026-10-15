# 规范增量：frontend

## 新增需求：学生端学情画像与靶向练习界面 (ADDED)

前端成绩中心界面 SHALL 支持学生查看跨考试学情画像，并展示薄弱知识点靶向推荐练习。

### Scenario: 学生端学情画像 Tab 交互与渲染 (ADDED)
- **WHEN**: 学生进入成绩中心（`frontend/src/pages/(dashboard)/student/scores/index.page.vue`）
- **THEN**: 界面 SHALL 提供「我的成绩」与「学情画像与靶向练习」Tab 切换
- **AND**: 切换至「学情画像」时，界面 SHALL 呈现概览统计卡片（参测场次、历史均分、最高分、及格率）、成绩走势折线/进度条、知识点掌握度进度列表（根据掌握度红/黄/绿分级呈现）
- **AND**: 界面 SHALL 呈现靶向推荐练习卡片，展示推荐题目与推荐原因（薄弱知识点提示）
- **AND**: 界面 SHALL 严格遵循三态分离：查询中呈现 loading，无考试记录呈现引导空态，API 失败显性呈现错误 Alert

# spec-delta：add-question-fill-blank

涉及域：question-bank、grading、frontend（exam-taking 作答渲染归 frontend 域表述）。

## 域：question-bank

### MODIFIED Requirement: 题目管理
新增 Scenario：
- Scenario: 创建单空填空题 —— type=5，题干含 `____` 空位占位，可接受答案列表非空
  （≥1 项）、各项不含 `|`、总长 ≤512；创建成功响应回显归一化后答案串。
- Scenario: 填空题答案校验拒绝 —— 空答案列表 / 单项含 `|` / 超长，均 400 带明确 message，
  不落库。
- Scenario: 填空题编辑与软删除 —— 与既有题型同语义，编辑可增删可接受答案项。
- Scenario: 题库列表按 type=5 筛选 —— 既有筛选链路对 5 透明生效。

### MODIFIED Requirement: 答案归一化
新增 Scenario：
- Scenario: 填空答案归一化 —— trim 首尾空白后按 `|` 拆分、各项再 trim、空项剔除、
  以 `|` 重组为归一化串；学生侧答案仅 trim 不做其他变换。
- Scenario: 既有四题型归一化零回归 —— 单选/多选/判断/简答的归一化行为逐字不变。

## 域：grading

### ADDED Requirement: 填空题自动判分
- Scenario: 精确命中 —— 学生答案（trim 后）与某一可接受答案全等（含中文）→ 该题满分。
- Scenario: 忽略大小写命中 —— 纯英文字母答案忽略大小写比对命中 → 满分；比对不改变
  学生答案原样存档（student_answer 快照保留原文）。
- Scenario: 未命中 —— 与全部可接受答案比对不中 → 零分，进入成绩与错题本口径
  （错题判定与既有题型同构）。
- Scenario: 策略经注册表接入 —— FillBlankGradingStrategy 注册于 GradingStrategyRegistry，
  判分核心既有方法体零改动；既有四题型判分结果逐字零回归。
- Scenario: 不进主观批改 —— 填空题不出现在主观批改工作台候选中；简答仍走主观链不变。

## 域：frontend

### MODIFIED Requirement: 题目编辑
新增 Scenario：
- Scenario: 填空题录入 —— 类型选择含「填空」；选择后渲染可接受答案动态列表
  （增删项、每项必填）；提交与后端校验口径一致的本地预校验与错误提示。

### MODIFIED Requirement: 学生作答
新增 Scenario：
- Scenario: 填空作答渲染 —— type=5 渲染文本输入框（不展示任何可接受答案提示），
  草稿自动保存/恢复对填空答案生效。
- Scenario: 逐题回顾填空展示 —— 回顾页展示学生原文、判分结果与正确答案
  （可接受答案列表展示形态与简答参考答案区分）。

### 既有约束引用（不重复正文）
- 断言有效性纪律（decorativeAssertionGuard 词法护栏）适用于本变更全部新增测试。
- SDK 按既有代码生成方式接入，type 枚举扩展自动携带。

## 契约

- openapi.yaml：题目创建/响应 DTO 的 type 枚举补 5；描述补「填空」；paths 数只增不减。
- gate-baseline.json：后端 surefire 与前端 vitest 用例数按实测同批上调。

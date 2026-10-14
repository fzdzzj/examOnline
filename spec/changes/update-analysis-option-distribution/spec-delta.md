# spec-delta：update-analysis-option-distribution

涉及域：score-management、frontend。

## 域：score-management

### MODIFIED Requirement: 考试数据分析报告
新增 Scenario：
- Scenario: 逐题选项分布 —— analysis-report 逐题指标 QuestionStatItem 新增
  optionDistribution 数组 [{option, count, rate}]（选项字母或 T/F、选择人数、
  占比）；仅 SINGLE/MULTIPLE/JUDGE 输出分布，FILL_BLANK/SHORT_ANSWER 为空数组
  不报错；占比分母为该题作答人数，MULTIPLE 每人可贡献多个选项、占比和可超
  100%，未作答学生不计入任何选项桶；选项计数与题目统计导出复用同一聚合核心，
  既有指标（平均分/得分率/答对率/区分度/作答人数）数值不得因本扩展漂移。

### MODIFIED Requirement: 成绩导出
新增 Scenario：
- Scenario: 题目统计导出选项分布列 —— question-stats xlsx 在既有列（题号/
  题型/满分/平均分/得分率/答对率/区分度/作答人数）之后新增「选项分布」单列
  文本（形如 A:12(30%) B:8(20%)…，判断题 T/F），与 analysis-report JSON 的
  optionDistribution 同源一致；既有列零删改；无选项题型该列为空。

## 域：frontend

### MODIFIED Requirement: 教师端考试数据分析报告界面
新增 Scenario：
- Scenario: 逐题选项分布呈现 —— 分析报告页签逐题指标区展示选项分布（选项
  标识 + 人数 + 占比，T/F 展示层映射为「正确/错误」），无选项题型不渲染分布
  区；零新依赖（无图表库，文本/CSS 条形态对齐既有 UI 风格）；加载/失败既有
  Scenario 行为不变。

### 既有约束引用（不重复正文）
- 断言有效性纪律（decorativeAssertionGuard 词法护栏）适用于本变更全部新增测试。
- SDK 按既有代码生成方式再生成，schema 变更自动携带（零新端点，paths 不变）。

## 契约

- openapi.yaml：paths 不变（82）；QuestionStatItem schema 向后兼容新增
  optionDistribution 字段；SDK 再生成仅 types 变更。
- gate-baseline.json：后端 surefire 与前端 vitest 用例数按实测同批上调，
  openapiPaths 维持 82。

# project-analysis-report-graded-read spec-delta

能力域：挂载位以基线实际结构为准（分析报告行为 Requirement 在 score-management 基线、取数效率 Requirement 在 performance 基线时，优先 score-management「考试数据分析报告」Requirement 挂载位，收口时按实际结构定，Scenario 文本逐字一致）

变更类型：ADDED Requirement

## ADDED Requirement：分析报告全场取数仅投影消费列

### Scenario: listGraded 两列投影

- WHEN 服务端生成考试数据分析报告并取全场已批改（GRADED 且总分非空）答卷
- THEN 取数仅投影 student_id 与 total_score 两列，不得逐行取回 answers LONGTEXT 等未消费列
- AND 报告各字段（班级概览/逐题指标/知识点薄弱/关注名单及 passLine/excellentLine）与既有契约逐字一致，openapi.yaml 零变更

### Scenario: 投影护栏常驻

- WHEN 任何后续改动使 listGraded 投影列集与消费列集漂移（移除投影、或新增未投影列的消费）
- THEN 常驻护栏测试（投影列集 = 消费列集断言）必须击红

### Scenario: 缺席计数查找复杂度

- WHEN 班级概览计算缺席人数（应考名单 − 有答卷者）
- THEN 判重查找为集合语义（O(1) 查找形态），缺席计数结果与既有口径逐字一致

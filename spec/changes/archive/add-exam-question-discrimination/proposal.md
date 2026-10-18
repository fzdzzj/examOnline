# 提案：考试分析报告试题区分度与难度评价诊断（add-exam-question-discrimination）

> **变更 ID**：`add-exam-question-discrimination`  
> **能力域**：`score-management`  
> **级别**：S 级数据智能与业务创新特性（后端计算 + Excel 导出 + 前端渲染）  
> **前置依赖**：`add-exam-time-extension`（已合入 main）  
> **影响面**：`ExamAnalysisReportResponse`、`ExamAnalysisReportService`、`ExamAnalysisReport.vue`、`openapi.yaml`

---

## 1. 为什么做（Why）

- **业务痛点**：目前在线考试系统的成绩分析报告中，虽然底层基于前 27% 高分组与后 27% 低分组计算了试题区分度数值（$D$），但：
  1. 仅暴露裸浮点数（如 `0.38`），缺乏教育测量学意义的定性等级判定（优良/良好/尚可/劣质/反常）；
  2. 缺乏试题难度系数（$P = \bar{X} / X_{max}$）及其等级划分（容易/中等/较难）；
  3. 缺乏面向出题教师的**命题质量诊断与修改建议**（例如当 $D < 0$ 时未给出严重反常预警——提示可能题干有严重歧义或参考答案配置错误；当区分度过低时未提示题干干扰项需修订等）；
  4. 多 Sheet Excel 导出的「逐题指标」工作表中缺少区分度等级与诊断建议列。
- **治理目标**：全面引入经典教育测量学（Classical Test Theory, CTT）标准模型，将试题区分度与难度转化为具备高业务指导价值的**评级标签与智能诊断建议**，并贯通 JSON API、SXSSF Excel 导出与前端可视化界面。

---

## 2. 改什么（What）

1. **教育测量学评级与诊断模型**：
   - **区分度评级（Discrimination Level）**：
     - $D \ge 0.40$：`优良`（试题区分力强，优秀命题）
     - $0.30 \le D < 0.40$：`良好`（区分力较好，可直接使用）
     - $0.20 \le D < 0.30$：`尚可`（区分力一般，建议微调题干或选项）
     - $0 \le D < 0.20$：`劣质`（区分力差，无法有效区分高低水平）
     - $D < 0$：`反常预警`（负区分度！高分组得分低于低分组，提示题干严重歧义或答案有误）
     - 样本不足（$N < 4$ 或分组大小 $< 1$）：`样本不足`
   - **难度系数与评级（Difficulty & Level）**：
     - 难度系数 $P = \text{averageScore} / \text{fullScore}$（保留 2 位小数，答题人数为 0 时为 null）；
     - $P \ge 0.70$：`容易`；$0.40 \le P < 0.70$：`中等`；$P < 0.40$：`较难`。
   - **命题质量诊断建议（Quality Suggestion）**：
     - 结合难度与区分度自动生成专业建议文本（如“优良试题，建议入选精品题库”、“中等难度，区分能力优良”、“区分度偏低，建议优化错误选项诱答力”、“负区分度反常，请紧急核查题干与参考答案正确性”等）。

2. **后端 DTO 与计算层升级**：
   - [`ExamAnalysisReportResponse.QuestionStatItem`](file:///d:/code/examOnline/src/main/java/com/exam/score/dto/ExamAnalysisReportResponse.java) 扩充：`difficulty`（Double）、`difficultyLevel`（String）、`discriminationLevel`（String）、`qualityEvaluation`（String）；
   - [`ExamAnalysisReportService.java`](file:///d:/code/examOnline/src/main/java/com/exam/score/service/ExamAnalysisReportService.java) `buildQuestionStats` 方法组装上述测量学指标；
   - `writeQuestionSheet` 在 SXSSF Excel 导出中追加「难度系数」「难度等级」「区分度等级」「命题诊断建议」等列，数据文本严格经 `sanitizeFormula` 清洗。

3. **前端呈现升级**：
   - [`ExamAnalysisReport.vue`](file:///d:/code/examOnline/frontend/src/components/exam/ExamAnalysisReport.vue)：
     - 区分度列渲染彩标 Tag：`优良`（绿色）、`良好`（蓝色）、`尚可`（橙色）、`劣质/反常预警`（红色）、`样本不足`（灰色）；
     - 新增「命题建议」列，悬浮展示详尽诊断建议，提升教师用户体验。

4. **API 契约升级**：
   - [`openapi.yaml`](file:///d:/code/examOnline/openapi.yaml) 的 `QuestionStatItem` 补齐新增属性定义（paths 保持 102 不变）。

---

## 3. 约束与红线（Guardrails）

- **零 DDL**：纯内存数据挖掘与指标派生，严禁修改 `schema.sql`，严禁新增数据库迁移；
- **公式注入防御**：Excel 导出文本一律必须经过 `ExcelSheetWriter.sanitizeFormula` 处理；
- **优雅降级**：样本不足（实考学生少于 4 人）时区分度与建议优雅回退，绝不报 NPE 或抛 500；
- **双端门禁与 TDD 纪律**：新增单测与集成测试先红后绿，变异验证破坏评级阈值或诊断逻辑必须断言级击红并回绿留证。

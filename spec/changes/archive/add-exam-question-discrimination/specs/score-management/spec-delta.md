# 规范差异：score-management 域增加试题区分度与难度评价诊断

> **能力域**：`score-management`  
> **对应变更**：`add-exam-question-discrimination`

---

## ADDED Scenarios

### Requirement: 试题区分度与难度评价诊断

系统必须在考试数据分析报告及多 Sheet Excel 导出中，全面引入经典教育测量学（Classical Test Theory）标准模型，将试题难度与基于前 27% 高分组 vs 后 27% 低分组的区分度数值，自动派生为定性等级评价与命题质量诊断建议。

#### Scenario: 试题区分度多档定性评级
- **GIVEN** 考试已汇总成绩且实考学生数充足（实考人数 ≥ 4 且高低分组各 ≥ 1 人）
- **WHEN** 教师查询考试分析报告（`GET /api/exams/{examId}/scores/analysis-report`）
- **THEN** 逐题指标输出数值区分度 `discrimination`（保留 2 位小数）并自动派生 `discriminationLevel`：
  - $D \ge 0.40$：`优良`
  - $0.30 \le D < 0.40$：`良好`
  - $0.20 \le D < 0.30$：`尚可`
  - $0 \le D < 0.20$：`劣质`
- **AND** 派生对应的 `qualityEvaluation` 诊断文本（如优良试题推荐入库、区分度偏低建议优化干扰项等）

#### Scenario: 负区分度严重反常预警
- **GIVEN** 试题出现高分组平均分低于低分组平均分（$D < 0$）
- **WHEN** 系统生成逐题分析指标
- **THEN** 区分度评级 `discriminationLevel` 标记为 `反常预警`
- **AND** `qualityEvaluation` 明确包含警示文案，提示出题人检查题干严重歧义或参考答案错误

#### Scenario: 试题难度系数与分级计算
- **GIVEN** 试题存在作答人次（`answeredCount > 0`）且满分大于 0
- **WHEN** 计算逐题指标
- **THEN** 系统计算难度系数 `difficulty` = 平均分 / 满分（保留 2 位小数）
- **AND** 派生 `difficultyLevel`：$P \ge 0.70$ 为 `容易`；$0.40 \le P < 0.70$ 为 `中等`；$P < 0.40$ 为 `较难`

#### Scenario: 样本不足时优雅回退
- **GIVEN** 实考人数少于 4 人或高低分组大小小于 1
- **WHEN** 生成分析报告或导出 Excel
- **THEN** `discrimination` 返回 null，`discriminationLevel` 明确标识为 `样本不足`
- **AND** `qualityEvaluation` 提示样本不足（需至少 4 人实考），系统不产生 NPE 或 500 异常

#### Scenario: 多 Sheet Excel 导出完整包含测量学诊断指标
- **GIVEN** 归属教师请求导出多 Sheet 分析报告 Excel
- **WHEN** 触发 `GET /api/exams/{examId}/scores/analysis-report/export`
- **THEN** 「逐题指标」工作表表头新增难度系数、难度等级、区分度等级与命题建议列
- **AND** 诊断建议文本严格通过 `ExcelSheetWriter.sanitizeFormula` 处理，防止公式注入

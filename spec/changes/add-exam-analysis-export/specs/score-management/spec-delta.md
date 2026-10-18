# Score Management Specification Delta

> Capability: `score-management`  
> Change: `add-exam-analysis-export`  
> Status: PROPOSED  

---

## ADDED Scenarios

### Requirement: 考试数据分析报告

#### Scenario: 考试数据分析报告多 Sheet Excel 导出

- **GIVEN** 教师对已汇总（GRADED 或已发布）且有成绩数据的考试请求分析报告导出
- **WHEN** 调用 `GET /api/exams/{examId}/scores/analysis-report/export`
- **THEN** 系统返回 200 状态码与 `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` 字节流
- **AND** 响应头包含 `Content-Disposition: attachment; filename*=UTF-8''...`
- **AND** 工作簿包含四个工作表：
  1. 「班级概览与分数段」：包含应考/实考/缺席人数、及格线、优秀线、平均分、及格率与五段分数段分布；
  2. 「逐题指标」：包含题号、题型、满分、平均分、得分率、答对率、区分度、作答人数与选项分布；
  3. 「知识点薄弱」：包含知识点名称与得分率（无标签题目时输出提示文本「题库未打知识点标签」）；
  4. 「学生关注名单」：包含低于及格线学生的学号、姓名、总分（无低于及格线学生时输出提示「暂无低于及格线学生」）；
- **AND** 导出的各项指标与 `GET /api/exams/{examId}/scores/analysis-report` 聚合口径逐字同源
- **AND** 写入的所有文本经 `ExcelSheetWriter.sanitizeFormula` 进行公式防注入清洗

#### Scenario: 分析报告导出越权与未汇总拦截

- **GIVEN** 教师 B 尝试导出教师 A 拥有的考试分析报告，或请求不存在/未汇总的考试 ID
- **WHEN** 调用 `GET /api/exams/{examId}/scores/analysis-report/export`
- **THEN** 非归属教师请求返回 403 Forbidden
- **AND** 不存在的考试返回 404 Not Found
- **AND** 未汇总考试（状态低于 GRADED）返回 400 Bad Request

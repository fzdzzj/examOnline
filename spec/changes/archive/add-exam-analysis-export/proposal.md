# Proposal: 考试数据分析报告多 Sheet Excel 导出 (add-exam-analysis-export)

> 阶段：创新点 4 ⭐⭐⭐ 深度落地（后端 + 前端 + 契约，零 DDL）  
> 目标能力域：`score-management`, `frontend`  
> 分支：`feature/add-exam-analysis-export`  
> 评级：S / M 级特性增强  

---

## 1. 为什么做（背景与动机）

- **业务背景**：在前期已完成的「考试数据分析报告」（`add-exam-analysis-report`）中，教师端通过 `GET /api/exams/{id}/scores/analysis-report` 获得了班级概览、分数段分布、逐题指标（P值/D值/选项分布）、知识点薄弱、关注名单五维综合诊断指标。
- **痛点现状**：目前教师只能在系统页面上只读浏览分析结果，无法离线导出结构化数据向教研组汇报、备份归档或做进一步二次分析。现有的 `ScoreExportService` 支持班级成绩单、逐题明细、题目统计、个人成绩单导出，但缺少针对「综合数据分析报告」的离线导出。
- **目标方案**：在后端提供 `GET /api/exams/{examId}/scores/analysis-report/export` 接口，复用现有聚合核心生成 4 个工作表（Sheet 1: 班级概览与分数段分布；Sheet 2: 逐题指标；Sheet 3: 知识点薄弱；Sheet 4: 学生关注名单）的 Excel 报告；在前端分析报告页签中增加「📥 导出分析报告」操作按钮，接入已有的 `downloadExport` 流式下载管道。

---

## 2. 改什么（范围与改动面）

### 2.1 后端改动

1. **控制器接线** (`ScoreController.java`):
   - 暴露 `GET /api/exams/{examId}/scores/analysis-report/export`；
   - 权限标注 `@RequirePermission("exam:manage")`；
   - 教师归属校验 `requireOwnedExam(examId)`（考试不存在 404，软删/非归属 403）；
   - 调用 `examAnalysisReportService.exportReport(examId)`，并利用 `attachment(examId, "考试分析报告", ".xlsx", XLSX_MEDIA_TYPE, ...)` 封装返回。
2. **分析报告服务实现** (`ExamAnalysisReportService.java`):
   - 新增 `public byte[] exportReport(Long examId)`；
   - 状态前置：考试状态须满足 `exam.getStatus() >= Exam.STATUS_GRADED`，未汇总抛 400（与 `buildReport` 语义一致）；
   - 基于现有 `buildReport(examId)` 聚合得到数据结构，调用 `ExcelSheetWriter.withStreamingWorkbook` 构建 SXSSF 流式工作簿（窗口 100 行防 OOM）：
     - **Sheet 1「班级概览与分数段」**：基本概览行（应考人数、实考人数、缺席人数、及格线、优秀线、平均分、及格率）+ 分数段分布表（分数段、人数、占比）；
     - **Sheet 2「逐题指标」**：题号、题型、满分、平均分、得分率、答对率、区分度、作答人数、选项分布；
     - **Sheet 3「知识点薄弱」**：知识点名称、得分率（若 `hasTagDimension=false` 则输出单行提示「题库未打知识点标签」）；
     - **Sheet 4「学生关注名单」**：学号、姓名、总分（低于及格线学生名单，若名单为空则输出单行提示「暂无低于及格线学生」）；
   - **安全防范**：所有写入单元格的文本值经 `ExcelSheetWriter.sanitizeFormula` 清洗，防范 Excel 公式/DDE 注入（CWE-1236）。

### 2.2 前端改动

1. **组件增强** (`frontend/src/components/exam/ExamAnalysisReport.vue`):
   - 在数据区顶部工具栏（或标题行右侧）增加「导出分析报告」按钮；
   - 绑定 `exporting` 状态与 `:disabled="!hasData || loading"` 禁用门控；
   - 点击调用 `downloadExport`（来自 `@/utils/exportDownload`），请求 `/api/exams/{examId}/scores/analysis-report/export`；
   - 导出中展示 loading，成功触发下载并展示 `message.success`，失败展示错误 Alert / message。
2. **页面传参支持** (`frontend/src/pages/(dashboard)/teacher/exams/[id].page.vue`):
   - 向 `ExamAnalysisReport` 传入 `examTitle`（可选），用于下载文件的语义化命名（如 `${examTitle}-考试分析报告.xlsx`）。

### 2.3 契约与基线

1. **API 契约** (`openapi.yaml`):
   - 补充 `GET /api/exams/{examId}/scores/analysis-report/export` 路径与参数、响应定义（100 -> 101 paths）。
2. **CI 门禁基线** (`.github/ci/gate-baseline.json`):
   - `openapiPaths` 单调上调至 101；
   - `backendTests` 与 `frontendTests` 随新增用例数单调上调。

---

## 3. 质量红线与禁忌

1. **零 DDL**：严禁改动 `src/main/resources/schema.sql`、`docker/mysql/migrations/`。
2. **禁止自建表**：集成测试严禁使用 `@Sql` 自建表。
3. **防 OOM 规范**：Excel 写入统一经 `ExcelSheetWriter.withStreamingWorkbook`（SXSSF 流式写）。
4. **口径同源**：多 Sheet 导出的指标数值必须直接基于 `buildReport`，严禁在导出层重复编写或魔改统计公式。
5. **门禁与变异**：
   - 后端 `./mvnw clean test` 全量通过；
   - 前端 `pnpm run lint:check`、`pnpm run type-check:check`、`pnpm test` 全量通过；
   - 实施笔提交后执行 m1/m2/m3 变异击红并回绿，证据写入 `evidence/`。

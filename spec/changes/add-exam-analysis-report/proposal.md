# 提案：考试数据分析报告（add-exam-analysis-report，创新点4 ⭐⭐⭐ / Phase 2 P1）

## Why

规格书 `docs/examOnline需求规格说明书.md` §3.1 核心创新点 4「考试数据分析报告」（自动生成 PDF，
难度系数/区分度/知识点薄弱点，教师可直接用于教学改进，唯一 ⭐⭐⭐）与 Phase 2「考试数据报告：
班级/题目/学生三维度分析」（P1）在现有基线中仍是空白。既有导出能力已具备题目统计 Excel
（题号/题型/题干/满分/平均分/得分率/答对率/区分度/作答人数，口径为前后 27% 高低分组的区分度），
但尚无面向教师的分析**可视化页面**，知识点薄弱点维度也完全未落。

指导 agent 已裁决立项：**产出形态=页面可视化**（不引 ECharts，用 antd 原生组件），导出能力后补。
本卡落地三维度分析（班级/题目/知识点/轻量学生）的统一只读接口与教师端「分析报告」页，
且**逐题指标与新接口严格同源同口径**（将既有 `exportQuestionStats` 的聚合/区分度核心抽为
共享方法供 Excel 导出与新 JSON 接口共用，既有导出行为与列口径零变化，禁止另造第二套公式）。

## What Changes

1. **后端只读接口** `GET /api/exams/{examId}/scores/analysis-report`（权限 `exam:manage` +
   `requireOwnedExam` 越权校验，口径同既有导出端点，就近 ScoreController 导出区）：
   - **a. 班级概览**：应考／实考／缺席人数、平均分、及格率（≥60 分）、分数段分布
     （0-59/60-69/70-79/80-89/90-100 六段）；
   - **b. 逐题指标**：与 `exportQuestionStats` 同源同口径（含区分度），另附题目顺序/题型；
   - **c. 知识点薄弱**：按 `question_tags × tags` 聚合得分率；**优雅降级**——试卷题目均无知识点
     标签时返回空数组 + `hasTagDimension=false`，前端提示「题库未打知识点标签」，不报错不猜测；
   - **d. 学生关注名单（轻量学生维度）**：低于及格线学生列表（学号/姓名/总分），不做逐生×逐题
     交叉矩阵（留二期）。
   - 数据源复用既有判分结果路径（`forEachSubmissionPage` + `resolveQuietly`「每生一行×逐题得分」
     同源查询），不新写全表扫描。
2. **契约**：`openapi.yaml` 新增 path + schema；`gen:api` 再生成；轮廓流程沿 `add-exam-list-filtering`
   既有模式，含 `OpenApiContractTest` 既有护栏（UTF-8 字节级、servers.url、无 U+FFFD）断言同步更新，
   重导出对比留证。
3. **前端教师端「分析报告」页**：
   - **入口**：挂到考试详情页就近（对照既有导航形态定，见 spec-delta 注记）；
   - **内容**：概览统计卡（antd `Statistic`）+ 分数段 `Progress` 条 + 逐题指标 `Table`
     （得分率/区分度列内联呈现）+ 知识点薄弱条形（antd 进度条形态）+ 关注名单 `Table`；
   - **知识点维度空态**：`hasTagDimension=false` 文案「题库未打知识点标签」，不是错误态；
   - **查询失败**：沿 `fix-frontend-exam-page-error-states` 三态口径（error 消费 + Alert + 数据区隐藏）；
   - **加载态/空态**（无成绩/未判分完成）齐备。
4. **零破坏**：既有四个 export 端点行为与列口径零变化；`ScoreExportService` 既有导出单元/集成测试
   零改动通过（抽取重构后仍绿）。

## Impact

### 受影响的规范
- `spec/specs/score-management/spec.md` — ADDED：考试数据分析报告（班级/题目/知识点/学生关注名单
  四维只读分析接口，逐题指标与导出口径同源）Requirement；
- `spec/specs/frontend/spec.md` — ADDED：教师端考试数据分析报告界面 Requirement。

### 受影响的文件
- 后端：`src/main/java/com/exam/score/service/ScoreExportService.java`（抽取共享聚合核心）、
  `src/main/java/com/exam/score/controller/ScoreController.java`（新增端点）、
  新增 `src/main/java/com/exam/score/dto/ExamAnalysisReportResponse.java`（或等价 DTO）、
  新增 `src/main/java/com/exam/score/service/ExamAnalysisReportService.java`；
- 契约：`openapi.yaml`；
- 前端：`frontend/src/api/axios/**`（gen:api 生成）、新增分析报告页面或考试详情页子模块、
  前端测试 spec；
- 规范：`spec/` 目录。

## 验收判据

1. **先红后绿**：
   - 后端新增集成用例（404 不存在/403 非归属/200 三块结构/知识点降级分支）先红，后端实施后变绿；
   - 前端页面 spec（渲染/空态/失败态/降级文案）先红，前端实施后变绿；
   - 关键断言变异校验（撤权限注解→红、撤降级分支→红、复原→绿）。
2. **同源同口径**：分析接口逐题指标与 `exportQuestionStats` 共用同一聚合/区分度核心
   （抽取重构），既有导出 Excel 列口径零变化；`ScoreExportService` 既有导出测试零改动通过。
3. **双端门禁只增不减**：
   - 后端 `mvnw.cmd clean test` 通过（用例数只增不减，BUILD SUCCESS，含 `OpenApiContractTest`）；
   - 前端 `lint:check`、`type-check:check`、`test` 全部退出码 0，用例数只增不减。
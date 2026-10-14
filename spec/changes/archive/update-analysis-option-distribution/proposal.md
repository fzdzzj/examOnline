# update-analysis-option-distribution：题目选项分布统计

## 背景与必要性

愿景 §9.5「题目质量分析」的选项分布是创新点 4（考试数据分析报告）交付时的**唯一
残差**（功能待办清单勘误后定位为低优先小案，2026-10-14 用户裁决立案）：现有
analysis-report 与 question-stats xlsx 已交付平均分/得分率（即 P 值）/答对率/
区分度（前后 27% 高低分组）/作答人数，但**教师讲评时看不到每个选项各有多少学生
选择**——这是错误选项干扰项有效性的核心讲评依据，纯计数补全，数据已在库。

## 现场核证基础（HEAD=cb2fe59，2026-10-14 指导主agent 现场核证 + Explore 两轮）

- 学生原始答案可得：`exam_submissions.answers` 为 questionId→答案 JSON；
  `GradingSubmission.answers` 映射同构，`paperReader.parseAnswers` 已在聚合链路
  将其转为 Map（单选/判断为归一化字符串、多选为升序字母串——具体形态执行侧
  核验，不符则按实际归一化口径实现并回报）。
- 聚合同源纪律在案：spec 基线「考试数据分析报告」明文「逐题指标与题目统计导出
  复用同一聚合核心（ScoreExportService.aggregateQuestionStats），口径严禁分叉」。
- 扩展点明确：`aggregateQuestionStats`（ScoreExportService L213-264 循环）→
  `QuestionStatMetric`（answeredCount/averageScore/scoreRate/correctRate/
  discrimination）→ 导出列（exportQuestionStats L173-182）与
  `ExamAnalysisReportResponse.QuestionStatItem`（DTO L174-183）同源。
- 前端落点：`ExamAnalysisReport.vue` 挂载于教师考试详情页「分析报告」页签
  （frontend spec「教师端考试数据分析报告界面」Requirement）；前端无图表库
  （package.json 无 echarts/chart 类依赖）。
- 数字链：后端 447（Skipped 1 存量）/ 前端 656（91 文件）/ openapiPaths 82；
  本案**零新端点**（paths 不变），仅 schema 向后兼容扩展。
- 零 DDL 前提：纯内存聚合计数，无新表/列/索引，无迁移脚本。

## 设计裁决

1. **范围**：逐题指标补选项分布（每选项选择人数 + 占比），三处同源同步——
   analysis-report JSON（QuestionStatItem 增 `optionDistribution` 数组
   `[{option, count, rate}]`）、question-stats xlsx（增「选项分布」列）、
   前端分析报告页签逐题区展示。
2. **题型口径**：仅 SINGLE/MULTIPLE/JUDGE 输出分布（选项字母 A–F / 判断 T/F）；
   FILL_BLANK/SHORT_ANSWER 无选项，`optionDistribution` 为空数组（不报错、
   不臆测——对齐基线「知识点薄弱空维度」的处理先例）。
3. **占比口径**：分母 = 该题作答人数（answeredCount，讲评视角「多少比例的学生
   选了 C」）；MULTIPLE 每人可贡献多个选项，占比之和可超 100%，如实呈现
   不归一；未作答学生不计入任何选项桶。
4. **聚合同源**：选项计数在 `aggregateQuestionStats` 既有循环内完成（新增
   计算，不改既有指标计算行）——P 值/答对率/区分度/作答人数数值**不得漂移**，
   以护栏用例锁定（既有指标用例全绿即证）。
5. **xlsx 列形态**：单列文本「A:12(30%) B:8(20%) …」（选项数不定 A–F，单列
   文本最稳，ExcelSheetWriter 既有消费方式不变）。
6. **前端形态**：无图表库、零新依赖——逐题指标区以文本/CSS 条形态展示分布
   （对齐既有 UI 风格，T/F 由展示层映射为「正确/错误」，DTO 保持原始 T/F）。
7. **契约**：openapi schema 向后兼容扩展（QuestionStatItem 增字段），paths
   82 不变；SDK 再生成（types 变更）；gate-baseline 双端用例数同批上调、
   openapiPaths 不动。
8. **边界（明确不做）**：不引入图表库；不做跨考试聚合；不改 P/D/答对率/
   及格线任何既有口径（及格线按试卷总分比例派生的既有基线不动）；不做选项级
   正确性/干扰项有效性分析（分布即原始计数，解读留给教师）。

## 红线（零触碰）

- 判分核心（ExamGradingService/ObjectiveGradingService/SubjectiveGradingService）
  与 `QuestionScoreResolver` 既有判分行（parseAnswers 只消费不修改）；
  ScoreService/MakeupScoreService、PaperSnapshotService。
- `aggregateQuestionStats` **既有指标计算行零改动**（只允许新增选项计数逻辑）；
  既有导出列（题号/题型/满分/平均分/得分率/答对率/区分度/作答人数）零删改。
- pom.xml（零新依赖）、application*.yml、schema.sql（零 DDL）、auth/security、
  com.exam.clazz、.github/（gate-baseline 用例数上调除外）、dev
  docker-compose.yml、spec/specs 既有基线、其余 score 域端点。

## 验收边界

- 讲评场景：构造 SINGLE（各选项多人选择）、MULTIPLE（多选、占比和>100%）、
  JUDGE（T/F 分布）、FILL_BLANK/SHORT_ANSWER（空数组）→ analysis-report JSON
  的 optionDistribution 逐项与构造一致；question-stats xlsx 新列内容与 JSON
  同源一致；前端页签展示分布；**既有指标数值与本案前完全一致**（护栏）；
  学生访问 403 既有用例仍绿。
- 双端门禁全绿且用例数净增；paths 不变（82）；变异自证 ≥3 项（候选：撤题型
  过滤→FILL 题臆造分布→红；撤同源→JSON 与 xlsx 分布不一致→红；撤多选逐字母
  计数→多选只计首选项→红；撤未作答排除→分母虚增→红）。
- 本案零 DDL、零新依赖、零新端点；W41/W42 存量应用由用户另行裁决，不随本案。

## 停止条件

- `parseAnswers` 后的原始答案形态与设计口径 2/3 的计数假设不符（如多选非字母
  串），且无法按既有归一化口径无歧义计数——上报裁决。
- 任一既有指标（P/答对率/区分度/作答人数）因接入选项计数而数值漂移且无法在
  不改既有计算行的前提下消除——停止上报。
- 任一红线需破坏才能实现——立即停止上报。

# add-question-import-export：题库 Excel 批量导入导出

## 背景与必要性

成熟产品差距分析三案串行（2026-10-13 用户裁决）的第三案（终案）：填空题（①）与
题干图片（②）已闭环（main=5a239f3），本案殿后——**导入模板的列口径依赖 ② 终态的
imageUrl 字段与五题型终态**，顺序不可倒置的原因至此兑现。题库批量建设（模板导入/
整库导出）是成熟在线考试产品的高频教师能力（愿景 §2.3「批量导入」、决策记录 12.7
「容错 + 错误报告」已预先裁决容错语义）；当前仅单条建题，百题级题库建设效率硬缺口。

## 现场核证基础（HEAD=5a239f3，2026-10-13 指导主agent 现场核证）

- Apache POI 5.4.0 已在 pom（poi-ooxml，SXSSF 流式写 + XSSF 读，**零新依赖**）；
  `com.exam.score.support.ExcelSheetWriter` 已把「防 OOM 的 Excel 写出管道」从成绩
  导出剥离为可复用工具（SXSSF 滑动窗口 + 显式 dispose）。
- 成绩导出已有四个 xlsx/pdf 下载端点先例（ScoreController 的 class-sheet / detail /
  question-stats / personal：GET + `attachment(...)` 响应助手 + XLSX_MEDIA_TYPE +
  中文文件名）——题库导出/模板端点对齐该形态，前端下载方式亦对齐。
- 题型五类终态（SINGLE/MULTIPLE/JUDGE/SHORT_ANSWER/FILL_BLANK，QuestionType 枚举
  与 questions.type 列一致，答案口径：单选单字母 / 多选升序字母列表 / 判断 T/F /
  简答参考答案原文 / 填空 `|` 分隔可接受答案）；`questions.difficulty` TINYINT 三档
  （1易 2中 3难）；② 交付 `questions.image_url`（VARCHAR(512) NULL，契约
  `^(|/images/.*)$`）。
- 标签模型：tags（TagType 枚举、**教师共享的全局资源**）+ question_tags 关联；题目
  API 按 tagIds 传递、QuestionService 管理「tagIds=null 保留原关联」语义。
- 决策记录 12.7 已裁决容错语义：**逐行校验、错误行跳过并生成报告（行号+原因）、
  正确行入库**；multipart 既有配置 20MB/50MB（图片链路 5MB/张，xlsx 余量充足）。
- 零 DDL 前提核证：导入写 questions/question_tags 既有表与列，无新表/新列/新索引
  需求——**本案无迁移脚本**（与 W41/W42 不同，AGENTS.md 约定 4 不触发）。

## 设计裁决

1. **零 DDL、零新依赖**：不新增表/列/索引，schema.sql 零改动，无 W43 迁移脚本；
   pom.xml 零触碰（POI 既有）。
2. **三端点**（新增 QuestionImportExportController，/api/questions 命名空间、类级
   `@RequirePermission("question:manage")` 复用题库权限点，不新增权限点）：
   `POST /api/questions/import`（multipart 单文件 xlsx，逐行容错导入）；
   `GET /api/questions/export`（全量导出为 xlsx，列口径与导入模板逐字一致）；
   `GET /api/questions/import-template`（下载模板：表头 + 五题型示例行 + 填写说明）。
   契约 paths 79→82（只增不减）。
3. **模板列口径**（单一 sheet、固定列序；**导入校验与模板产出共用同一份列定义，
   防漂移**）：题型（单选/多选/判断/简答/填空 五个词）、题干（必填）、选项A–选项F
   （单选/多选必填≥2 个非空；判断/简答/填空须留空）、答案（按题型：单选单字母；
   多选去重升序字母串如 `ABC`；判断 `T`/`F`；简答参考答案文本；填空 `|` 分隔可接受
   答案，对齐 ① 归一化口径）、标签（可选，逗号分隔，**仅接受已存在标签名**——标签
   为教师共享全局资源，未知标签名记行错误，不在导入中随手新建，防脏标签）、难度
   （可选，易/中/难；空则对齐单条建题的缺省口径，执行侧现场核证并对齐）、图片URL
   （可选，须 `/images/` 前缀或空，契约同 ②，任意外链记行错误）。题干/答案长度
   上限按 questions 列与既有单条建题校验口径（执行侧现场核证后同口径）。
4. **导入语义（决策 12.7 落地）**：文件级前置校验（扩展名 xlsx、可解析、数据行数
   ≤ `@Value("${exam.question-import.max-rows:1000}")`——超限 400 整批拒绝，不做
   异步任务）；**逐行独立事务**（一行的库内失败记行错误，不拖垮整批）；不去重
   （题目无业务唯一键，与单条建题口径一致）；响应
   `{imported, failed, errors:[{row, reason}]}` 即报告，不持久化、不落新表；导入
   题目立即可用（列表/组卷/抽题），软删与单条建题一致。
5. **导出语义**：全量导出教师视角全部未软删题目（对齐列表查询口径——执行侧核证
   列表的软删过滤条件并同口径）；SXSSF 流式（复用 ExcelSheetWriter，题库量级远
   小于其设计场景）；响应形态对齐成绩导出 `attachment(...)` 助手；**round-trip
   可用**：导出文件修改后可直接再导入（列同名同序，标签名/难度/图片URL 均按导入
   口径回读）。
6. **校验复用**：导入行校验优先复用 QuestionService/既有校验口径（题型解析、答案
   归一化、标签关联）；若现场核证发现耦合不可直用，则在导入服务内同口径独立实现
   并以护栏用例锁口径一致（差异上报，不擅自放宽）。
7. **前端**：题库管理页工具区加「导入 / 导出 / 下载模板」——导入为选择 xlsx →
   提交 → 结果弹层（成功数/失败数/行号+原因表，与后端响应逐字段一致）；导出/
   模板为文件下载（对齐前端既有成绩导出下载形态，执行侧核证后复用同一工具）；
   SDK 按既有方式再生成。
8. **既有文件受控修改白名单**（白名单外既有文件零触碰）：`openapi.yaml`（契约
   重导出产物）、`.github/ci/gate-baseline.json`（同批上调）、前端 questions 页
   与 SDK 生成物；`ExcelSheetWriter` 只消费不修改。
9. **边界（明确不做）**：不导入图片二进制（仅 imageUrl 引用，图片走 ② 上传端点）；
   不做 Word/PDF 导入（功能待办清单 C 类不建项）；不做异步导入/进度轮询（千行内
   同步返回，超限整批 400）；不持久化导入报告；不做导入撤销（回滚靠既有软删）；
   不做题库去重/合并。

## 红线（零触碰）

- auth/security 包、com.exam.clazz 包、判分核心既有方法体（ExamGradingService/
  ObjectiveGradingService/SubjectiveGradingService）、ScoreService/
  MakeupScoreService、PaperSnapshotService（建题不触发快照，快照只在组卷时生成——
  本案天然零涉及）。
- `pom.xml`、`application*.yml`（行数上限走 @Value 默认值 + 环境变量覆盖）、
  `schema.sql`（零 DDL）、`ExcelSheetWriter` 既有方法体（只消费）、
  QuestionController/QuestionService 既有方法体（扩展若必要走新增方法，既有行零
  改动——实际耦合形态以现场核证为准，差异上报）。
- `.github/`（gate-baseline.json 同批上调除外）、dev `docker-compose.yml`、
  swCore/swRegister、`spec/specs/` 既有基线。

## 验收边界（三案串行的第三案/终案）

- 全新链路可用：教师下载模板 → 填入五题型混合数据（含故意错误行：坏题型/未知
  标签名/外链图片URL/多选答案与选项不匹配/选项不足）→ 导入 →
  `{imported, failed, errors}` 与构造逐项一致 → 正确行题目（含标签关联、难度、
  图片URL）列表可见、可组卷抽题；导出 → 列口径与模板逐字一致 → 修改后可再导入
  （round-trip）；学生三端点均 403。
- 双端门禁全绿且用例数净增；契约 paths 79→82；变异自证 ≥3 项（撤行级容错→一行错
  整批失败→红；撤 question:manage→学生可导入→红；撤图片URL 前缀行校验→外链入库
  →红；撤行数上限→超限被受理→红）。
- 本案零迁移脚本、零 DDL；W41/W42 存量库应用仍由用户另行裁决，不随本案。

## 停止条件

- 现场核证发现成绩导出下载形态（attachment 助手 / 前端下载工具）与题库导出无法
  对齐（如前端无既有可复用的文件下载形态）——上报裁决，不硬凑。
- QuestionService 校验逻辑与导入列口径存在不可复用的隐性耦合，独立实现将导致两套
  口径分叉——停止上报。
- 任一红线需破坏才能实现——立即停止上报。

# 提案：错题本一期与考后逐题解析（add-student-wrong-questions，创新点3 + §3.3 / Phase 2）

## Why

根据 `docs/examOnline需求规格说明书.md`：
- §3.1 核心创新点 3「错题本 + 消灭计划」（自动归集错题，连续 3 次做对自动移出，形成学习闭环）；
- §3.3 体验创新「考后即时解析」（交卷并发布后显示逐题解析与标准答案）；
- Phase 2「错题本：自动归集 + 分类 + 重练」（P1）。

指导 agent 已对本卡范围作出明确裁决：
- **一期范围**：归集 + 查看 + 解析；
- **明确二期**（本卡不做）：重练功能、依赖重练的「消灭计划」（连续 3 次做对自动移出错题本）、错题导出功能。

### 为什么不建 `wrong_questions` 持久化表（一期架构裁决）

当前系统中，学生答卷仅在 `exam_submissions` / `GradingSubmission` 中持久化最终客观题/主观题/总分与答案 JSON，**并未建立逐题得分的持久化表**。
系统的唯一真实逐题得分判定链路是：
`ScoreExportService.loadPersonalReport(exam, studentId)` → `PersonalReport` record（逐题得分/评语/判分依据，经 `QuestionScoreResolver` 现场重算）。

若在本期另造持久化表或第二套判分口径，将直接违反单一体事实源原则，导致导出成绩、发布成绩与错题本得分判定产生漂移。
因此，一期错题本与单场逐题回顾必须与 `ScoreExportService` 同源，基于现场重算结果判定。

### 为什么分页必须以考试为粒度（性能与设计论证）

在逐题得分现场重算的约束下：
若以"错题"为粒度分页，系统必须预先重算该学生参与的**所有历史考试的所有题目**才能得到全量错题集，再在内存中做 `subList` 切片（前次半成品的硬伤设计）。当学生参加数十场甚至上百场考试时，每次翻页都会触发全量重算风暴，导致 CPU 与内存耗尽。

因此，本提案严格约束：**分页以考试为粒度**（每页 N 场考试组，N ≤ 10）。
1. 仅对学生已参加且**已发布**的考试做分页切片；
2. 仅对当前页的 N 场考试调用同源 `PersonalReport` 执行重算；
3. 单次请求的重算上限严格锁定在 N（≤ 10）场考试以内，计算复杂度完全可控。

## What Changes

1. **后端同源抽取与独立服务**（`com.exam.score` 域）：
   - `ScoreExportService` 做最小抽取：将 `PersonalReport` 暴露为 `public record`，补充 `answers` 映射字段，增加公共委托入口 `getPersonalReport(Exam exam, Long studentId)`；既有 4 个导出端点行为、列口径与单测 100% 零变化；
   - **禁止修改既有 `ScoreService`**，新建独立服务 `StudentWrongQuestionService`（对齐 `MakeupScoreService` 独立服务模式）；
   - 端点一：`GET /api/scores/my/wrong-questions?page=&size=`：
     - 以已发布考试为粒度分页（size ≤ 10）；
     - 错题判定：得分严格小于满分（`score < max`，含 0 分及多选/主观题的部分对）；
     - 语义校准：`analysis` 题目解析字段必须通过 `GradingQuestion.questionId` 批量关联 `questions.analysis`，严禁与 `ResolvedQuestionScore.detail()`（判分依据）或教师评语混淆；
     - 结果按考试分组呈现，附带考试标题、时间、题号、题型、题干、选项、我的答案、正确答案、得分/满分、题目解析与判分依据。
   - 端点二：`GET /api/scores/my/exams/{examId}/review`：
     - 单场考试逐题回顾（全量题目，含答对与答错）；
     - 发布门控：未发布返回 400「成绩待发布」；无答卷或无成绩返回 404「暂无本人成绩记录」；越权拒绝（只允许查本人）。
2. **契约管理**：
   - `openapi.yaml` 导出增补 2 个新端点与 DTO Schema；
   - 前端执行 `npm.cmd run gen:api` 同步生成 SDK 与 TypeScript 类型；
   - 同步更新 `OpenApiContractTest` 数量与结构护栏。
3. **前端呈现**（`student` 分区）：
   - 新增页面 `frontend/src/pages/(dashboard)/student/wrong-questions/index.page.vue`（我的错题本）；
   - 在 `(dashboard).page.vue` 侧边栏及 `NAVIGABLE_PATHS` 注册 `/student/wrong-questions`；
   - 查分页 `student/scores/index.page.vue` 在成绩已发布卡片区增加「逐题回顾」入口，点击弹层展示单场逐题解析明细；未发布考试不出入口；
   - 前端三态：显性呈现查询失败 Alert、数据区隐藏；空态展示正向文案「暂无错题」；题目解析缺失时优雅展示「暂无解析」占位，不产生空白错乱。

## Impact

### 受影响的规范
- `spec/specs/score-management/spec.md`：增加错题本列表查询与单场考试逐题回顾规范；
- `spec/specs/frontend/spec.md`：增加学生端错题本界面与查分页逐题回顾入口及三态交互规范。

### 受影响的文件
- 后端：
  - `src/main/java/com/exam/score/service/ScoreExportService.java`（最小抽取）
  - `src/main/java/com/exam/score/service/StudentWrongQuestionService.java`（新增独立服务）
  - `src/main/java/com/exam/score/controller/ScoreController.java`（新增 2 个端点）
  - `src/main/java/com/exam/score/dto/**`（新增响应 DTO）
  - `src/test/java/com/exam/score/service/StudentWrongQuestionIntegrationTest.java`（新增集成测试）
  - `src/test/java/com/exam/support/OpenApiContractTest.java`（契约断言更新）
  - `openapi.yaml`（重新导出）
- 前端：
  - `frontend/src/pages/(dashboard)/student/wrong-questions/index.page.vue`（新增错题本页面）
  - `frontend/src/pages/(dashboard)/student/scores/index.page.vue`（查分页增加入口）
  - `frontend/src/pages/(dashboard).page.vue`（侧边栏与导航白名单）
  - `frontend/src/api/axios/**`（重新生成 SDK）
  - `frontend/src/pages/(dashboard)/student/wrong-questions/__tests__/wrongQuestionsPage.spec.ts`（新增测试）
  - `frontend/src/pages/(dashboard)/student/scores/__tests__/examReviewEntry.spec.ts`（新增测试）

## 验收判据

1. **测试先行（红后绿）**：
   - 后端新增集成用例（发布门控拦截、非本人403/404、错题判定含部分对、题目解析语义正确、考试粒度分页）先红后绿；
   - 前端 spec（列表与分组渲染、空态「暂无错题」、错误态 Alert、未发布无入口、无解析占位）先红后绿；
   - 变异校验留证（撤发布门控变红、撤部分对错题判定变红、复原变绿）。
2. **核心字段完整性自查**：
   - 我的答案（`myAnswer`）、正确答案（`correctAnswer`）、题目解析（`analysis`）均真实赋值，0 处 TODO / 空串占位。
3. **同源零回归**：
   - 既有 `/api/scores/my` 及 4 个导出端点行为 100% 保持原有口径；
   - `GradingScoreIntegrationTest` 零改动全绿。
4. **全量门禁只增不减**：
   - 后端 `mvnw.cmd clean test` 退出码 0，计数 ≥ 378 + 新增用例；
   - 前端 `lint:check`、`type-check:check`、`test` 退出码 0，计数 ≥ 68 文件 / 519 用例。

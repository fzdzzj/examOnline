# 提案：主观题批改按题分页（add-subjective-grading-pagination，P2）

## Why

后端能力缺口登记（`docs/backend-optimization-candidates.md` 缺口 C，2026-10-02 基于 `bfc7bfa` 现场核验）：批改工作台取数端点 `GET /api/exams/{examId}/grading/subjective?questionId=` 返回**单题 × 全部交卷学生全量行**（`SubjectiveGradeMapper.selectWorkbenchRows`，无分页参数），前端面板客户端分页（pageSize 10）+ 客户端筛选（`nameFilter`/`onlyUngraded`，`SubjectiveGradingPanel.vue:178-193`），冲突回填 `refreshRow` 全量拉取后按 submissionId find（`:233-243`）。大班/大场次时一次性取回并渲染全部作答全文。前端候选台账候选 8 因此冻结。

## What Changes（关键裁决：就地扩参 + 响应恒为分页信封 + 筛选下沉 + submissionId 单行取数）

1. **端点扩参**（路径不变）：`GET /api/exams/{examId}/grading/subjective?questionId=&page=&size=&onlyUngraded=&name=&submissionId=`，新增参数全部可选：
   - `page`（≥1）/`size`（1..100）：**缺省时返回全量**（信封内 rows=该题全部行）；
   - `onlyUngraded`（boolean）：未批改过滤下沉服务端（对齐面板既有 `!r.graded` 语义）；
   - `name`（string）：学生姓名过滤下沉服务端（LIKE，对齐面板既有 `studentName.includes`）；
   - `submissionId`：单行取数（至多 1 行），专供 409/1012 冲突回填链路，替代「全量拉取后 find」。
2. **响应恒为分页信封**（裁决依据：全仓唯一消费方是批改页本身，本卡前后端协同改造，不做「缺省裸 List / 带参信封」的 oneOf 双形态——那会让契约与生成类型长期背两套形状）：`{ "rows": SubjectiveGradeRow[], "total": number, "graded": number }`；`graded` 与题级进度（`SubjectiveQuestionItem.gradedStudents`）口径一致；`ORDER BY g.student_id` 排序稳定性不动（翻页不丢行不错行的前提）。
3. **乐观锁链路零改动**：`casSaveScore`、`expectedVersion`、409/1012 冲突语义、`useGradingFlow` 协议原样；仅 `refreshRow` 的取数方式改为 `submissionId` 参数单行拉取。
4. **前端改造**：批改页 `useQuery` 持有服务端分页与筛选状态（page/size/onlyUngraded/name 入 queryKey）；面板移除客户端全量筛选与客户端分页（筛选输入与分页控件改由服务端驱动）；`gradedCount` 改用信封 `graded`。
5. **契约与再生成**：仓库根 `openapi.yaml` 路 A 离线重导出（响应形状变更）；`gen:api` 再生成，前端类型随信封更新。
6. **词法护栏（前端）**：面板源码断言不再含客户端全量筛选链（`filteredRows` 对 props.rows 的 filter）与静态客户端分页、`refreshRow` 调用含 `submissionId` 查询参数——变异还原可红。
7. **既有语义零改动**：批改权限与教师归属校验（既有 403 口径）、`casSaveScore` 协议、打回重批语义、读己之写打点（`readYourWriteMark.mark()` 在分页查询下主库回读口径须复核并保持）——全部原样。

## Impact

### 受影响的规范
- `spec/specs/grading/spec.md` — ADDED：主观题批改行分页与筛选 Requirement（信封形状、参数语义、冲突回填单行取数、排序稳定 Scenario）。

### 受影响的文件
- `src/main/java/com/exam/grading/`：`GradingController.subjectiveRows`（扩参）、`SubjectiveGradingService.listRows`（分页/筛选/单行）、`SubjectiveGradeMapper.selectWorkbenchRows`（或新增分页查询）、新增分页信封 DTO；
- `src/test/java/com/exam/grading/`：新增集成测试；
- 仓库根 `openapi.yaml`；`frontend/src/api/axios/**`（gen:api）、`grading/index.page.vue`、`SubjectiveGradingPanel.vue`、`useGradingFlow.ts`（仅 refreshRow 取数实现）、新增/扩展前端 spec。

### 需要迁移
- 无（零表变更）。

## 边界与不做
- 不动 saveSubjectiveScore 端点与判分链路；不动题级进度端点；不做分页性能优化（COUNT 缓存等，一次只改一类）；`onlyUngraded`/`name`/`submissionId` 之外的筛选不新增。

## 验收判据
- 先红后绿；实施笔仅含 `src/main`、`src/test`、`openapi.yaml`、`frontend/src`；收口笔仅含 `spec/`（`git diff --name-only <实施笔> HEAD -- src frontend pom.xml` 为空）；
- 仓库根 `mvnw.cmd clean test` @ 实施笔：总数恰好较基线（以 add-question-batch-delete 合入后的当次实测为准）+ 新增用例数，Failures/Errors 0，Skipped 恒 1；
- 前端三项退出码 0，vitest 恰增新用例数（基线随 add-question-batch-delete 合入后实测）；
- `graded` 信封字段与题级进度口径一致性有用例断言；`git status` 终态除白名单未跟踪文件外干净。

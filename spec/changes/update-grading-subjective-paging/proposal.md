# 提案：主观题批改分页护栏补强与台账核验（update-grading-subjective-paging）

## Why

在历史优化台账（`docs/backend-optimization-candidates.md` 缺口 C 与前端优化台账候选 8）中，登记了主观题批改工作台首屏「单题 × 全量交卷学生一次拉全量 + 客户端分页」的性能债。

在本次开工阶段 0 调研核实中发现：
该能力主体已由前期变更 `add-subjective-grading-pagination`（commit `c1c3ba0`，2026-10-02 合入并归档于 `spec/changes/archive/add-subjective-grading-pagination/`）完整实施并合入 `spec/specs/grading/spec.md` 基线。
因此，本变更非全新功能实施案，如实纠偏定性为**护栏补强案**：
1. 梳理并核验前案设计在当前代码库（`main @ 19e0c05`）上的实际表现与约束；
2. 为既有分页实现补充「越界页返回空 rows 且统计真实」、「相邻页严格有序无缝隙无重叠」、「前端越界空态呈现」、「筛选作用域与题级进度口径一致性」4 项边界护栏测试；
3. 对既有实现执行 3 项关键变异校验，确认护栏有效性并实现台账收口闭环。

---

## 阶段 0 · 调研核实与查证定性

### 1. 既有实现查证与重复立项定性纠偏

经全仓代码与历史提交检索查证：
- **提交事实**：commit `c1c3ba0`（`feat(grading): 主观题批改行分页信封与筛选下沉、冲突回填单行取数（add-subjective-grading-pagination）`）已于 2026-10-02 合入 main；
- **端点现状**：`GET /api/exams/{examId}/grading/subjective` 已就地扩参为 `questionId, page, size, onlyUngraded, name, submissionId`（全部可选），响应恒为分页信封 `{ rows: SubjectiveGradeRow[], total: number, graded: number }`，且在缺省 `page/size` 时保持全量兜底；
- **前端现状**：`grading/index.page.vue` 与 `SubjectiveGradingPanel.vue` 已切换为服务端分页与筛选，`refreshRow` 已接入 `submissionId` 单行拉取；
- **定性结论**：主观题批改服务端分页信封功能已经完整就绪。本案不可再虚构「实施信封/取数改造」的三件套叙事，转为**护栏补强案**，专注补充未覆盖的边界测试与变异证据。

### 2. 消费方普查

全仓 `git grep` 普查 `subjectiveRows` 与 `/api/exams/{examId}/grading/subjective`：
- **前端业务调用方**：唯一业务消费页面为 `frontend/src/pages/(dashboard)/teacher/grading/index.page.vue`（通过 `useQuery` 驱动工作台行展示）及面板 `SubjectiveGradingPanel.vue`（在 `refreshRow` 中单行回填）；
- **集成测试**：`GradingScoreIntegrationTest`、`SubjectiveRowsPaginationIntegrationTest` 及前端批改流用例；
- **导出路径**：成绩与题目统计导出（`ScoreExportService`）直接使用内部 `SubjectiveGradeMapper.selectList`，不经过该 HTTP 端点；
- **结论**：前端唯一消费方确实为批改工作台一族，无其他隐蔽调用方。

### 3. `SubjectiveGradingService.java` 的 `readYourWriteMark.mark()` 语义复核

- 源码位置：`SubjectiveGradingService.java:199-206`。
- 语义核验：教师提交批改 `saveScore`（写操作）完成后，显式调用 `readYourWriteMark.mark()` 并通过原 `selectWorkbenchRows` 从主库回读最新行。写后窗口内同一客户端后续发起的读请求（包括工作台列表分页刷新 `listRows`）强制路由至主库，分页查询完全受读己之写保护，主从一致性口径不受影响。

### 4. `OpenApiContractTest` 护栏核对

- 契约端点 paths 数量断言 ≥ 57（当前 65 条），免鉴权与受保护端点安全标注正常；
- `openapi.yaml` 导出格式正常，无 U+FFFD 替换字符，`servers.url` 保留端口，前端生成 SDK 与契约完全一致。

---

## 对前案设计的核验结论

基于上述调研，对前案既有实现的设计进行对照核验：

1. **端点形状与向后兼容**：
   - 现端点就地扩参，响应恒为分页信封 `{ rows, total, graded }`；
   - `page/size` 缺省时返回全量信封（`rows` 为该题全部行，`total` 为总行数，`graded` 为已批行数），既向下兼容老调用方，也是 409 冲突全量回填的天然保底。前案设计完全成立。
2. **409/1012 冲突回填链路**：
   - 发生乐观锁并发冲突时，`refreshRow` 以 `submissionId` 单行拉取最新数据（亦可通过缺省参数全量拉取后回填），`casSaveScore` 与 `expectedVersion` 乐观锁协议保持零改动。前案设计完全成立。
3. **排序与翻页稳定性**：
   - SQL 恒定按 `ORDER BY g.student_id ASC` 严格递增排序；
   - 本案进一步补强护栏断言：相邻页严格递增且无缝隙无重叠（`page1.last.student_id < page2.first.student_id`），越界页（`page > total/size`）返回空数组 `rows: []` 且保留真实 `total/graded`。
4. **前端筛选与统计一致性**：
   - 信封 `graded` 恒代表全局该题已批学生总数，与题级进度 `SubjectiveQuestionItem.gradedStudents` 口径一致，绝不同屏冲突；
   - 本案进一步补强前端面板行为断言：越界空态下信封统计仍正常显示，筛选状态变化不破坏题级进度统计。

---

## 补强范围与边界

- **可改动范围**：
  - 后端：在既有 `SubjectiveRowsPaginationIntegrationTest.java` 中追加越界页与相邻页无重叠测试用例；
  - 前端：在既有 `gradingServerPaging.spec.ts` 中追加越界空态与筛选统计一致性测试用例；
  - 规范资产：`proposal.md`、`spec-delta.md`、`tasks.json` 真实记录护栏补强过程。
- **不可改动范围**：
  - 不碰业务代码、Mapper、SQL、Controller 与 DTO（既有实现已完备且通过验证）；
  - 不碰 `openapi.yaml` 与 API 生成层；
  - 不碰数据库表、schema.sql 与迁移脚本；
  - 不碰 auth/security、config 及 application*.yml。

---

## 验收判据

1. **护栏净增通过**：
   - 后端 `SubjectiveRowsPaginationIntegrationTest` 净增 2 例，全量测试总数由基线 382 净增至 384（384/0/0/1）；
   - 前端 `gradingServerPaging.spec.ts` 净增 2 例，全量测试总数由基线 557 净增至 559（77 文件 559 例）。
2. **三项变异校验（基于既有实现）**：
   - 变异 ①：撤 Service 层分页参数透传 → 分页测试变红 → 复原变绿；
   - 变异 ②：撤 Service 层 graded 口径（恒返回 total）→ 统计一致性测试变红 → 复原变绿；
   - 变异 ③：撤前端 409 回填（refreshRow 返回 null）→ 409 行为测试变红 → 复原变绿。
3. **全量双端门禁**：后端 `.\mvnw.cmd clean test` exit 0；前端 lint:check / type-check:check / test 全绿。

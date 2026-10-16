# 阶段 2 证据留证：错题重练池热路径与批改读路径优化（optimize-practice-pool-hot-path）

本目录记录阶段 2 的完整证据链：后端单测**先红后绿**、四项变异 **m1/m2/m3/m4 击红-复原-回绿**、
全量后端门禁实测，以及关键红线核对。

- 分支：`feature/optimize-practice-pool-hot-path`
- 基线 revision：`f1583e3`（阶段 0&1 立项笔）
- 受控白名单：仅 `src/main` 与 `src/test` 的 `com/exam/score/**` 与 `com/exam/grading/**`
  （mapper 落在 `com.exam.grading.mapper`，属白名单内，无 submission 包触碰）；**零 DDL**
  （`schema.sql` 零 diff）、`docker/`、`openapi.yaml`、`pom.xml`、`frontend/` 均未触碰。
- 命令均在仓库根执行，留证日志为完整控制台输出的裁剪版（仅保留判定性行与结果汇总）。

---

## 一、后端单测先红后绿

新增 3 个 Mockito 隔离单测类（无 Spring 上下文，无 `@Sql`）：
- `src/test/java/com/exam/score/service/StudentPracticeServiceGateAndLimitTest.java`（5 例）：
  门控命中即短路（逐场装配至命中即止，装配次数=1）、门控 miss 全量扫描后 400 拒绝、
  门控定位循环异常记 warn 并跳过该场、attempt 回读 SQL 必须 LIMIT 3、最近 3 连对即消灭；
- `src/test/java/com/exam/grading/service/GradingQueryServiceProjectionTest.java`（3 例）：
  答卷统计查询投影 id+grading_status、主观批改行统计查询投影 submission_id+score、
  窄投影下进度计数口径不变；
- `src/test/java/com/exam/grading/service/SubjectiveGradingServiceSaveScoreReadBackTest.java`（4 例）：
  保存成功路径单行回读（不再全量工作台行内存过滤）、判分未运行先批路径同走单行回读、
  回读失败仍抛 INTERNAL_ERROR「批改结果回读失败」、乐观锁 CAS 冲突语义不变。

### 红（实现前）

将四处服务实现临时回退为改动前形态（仅保留新增 mapper 单行方法 `selectWorkbenchRow`——
新增接口方法是纯增量死代码，服务未消费它）后运行：
`mvnw.cmd -o clean test -Dtest=StudentPracticeServiceGateAndLimitTest,GradingQueryServiceProjectionTest,SubjectiveGradingServiceSaveScoreReadBackTest`
→ `Tests run: 12, Failures: 7, Errors: 0, Skipped: 0` **BUILD FAILURE**，七条断言逐点击红
（即缺陷存在性证明，与优化后行为一一对应）：
- `gateShortCircuitsAtHitExam`：`Wanted 1 time / But was 3 times`——旧门控全量重建集合，装配 3 场；
- `gateLogsWarnAndSkipsBrokenExamThenFindsQuestionInLaterExam`：无 warn 日志（旧门控循环静默吞）；
- `attemptHistoryReadCappedAtThree`：回读 SQL 无 `LIMIT 3`；
- `submissionsQueryProjectsOnlyConsumedColumns` / `subjectiveRowsQueryProjectsOnlySubmissionIdAndScore`：
  `getSqlSelect()` 为 null（旧查询无列投影）；
- `saveScoreReadsBackSingleRowInsteadOfFullWorkbenchRows` / `saveScoreInsertPathAlsoReadsBackSingleRow`：
  `Wanted but not invoked: selectWorkbenchRow`（旧实现走全量 `selectWorkbenchRows` + 内存 filter）。
- 日志：`red-before-impl.log`

另 5 例语义护栏用例（门控 miss 拒绝、消灭判定、回读失败、CAS 冲突、进度计数口径）在
改动前即绿——证明优化只替换取数形态、不改变判定与错误语义（不扩大红面）。

### 绿（实现后）

恢复实现后重跑同一命令 → `Tests run: 12, Failures: 0, Errors: 0, Skipped: 0` **BUILD SUCCESS**。
- 日志：`green-after-impl.log`

---

## 二、变异击红留证（m1 / m2 / m3 / m4）

每项均执行：注入违规 → 跑定向用例确认变红 → 复原 → 确认回绿。

### m1 —— 门控退回全量重建（短路失效）

- 注入点：`submitPractice` 门控从 `isWrongQuestionOf(studentId, questionId)`
  退回 `getStudentWrongQuestionIds(studentId).contains(questionId)`（全量重建错题集合）。
- 击红：`mvnw.cmd -o test -Dtest=StudentPracticeServiceGateAndLimitTest#gateShortCircuitsAtHitExam`
  → `Tests run: 1, Failures: 1`；`Wanted 1 time / But was 3 times`
  —— 短路失效即被装配次数断言击中（每笔提交重新装配全部考试）。
  - 日志：`m1-gate-red.log`
- 复原 + 回绿：同命令 → `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0` **BUILD SUCCESS**。
  - 日志：`m1-gate-green.log`

### m2 —— attempt 回读去 LIMIT 3

- 注入点：`submitPractice` 连对推导查询移除 `.last("LIMIT 3")`。
- 击红：`mvnw.cmd -o test -Dtest=StudentPracticeServiceGateAndLimitTest#attemptHistoryReadCappedAtThree`
  → `Tests run: 1, Failures: 1`；回读 SQL 无 `LIMIT 3`（全量历史回读）
  —— 连对窗口读收敛失效即红。
  - 日志：`m2-limit-red.log`
- 复原 + 回绿：同命令 → `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0` **BUILD SUCCESS**。
  - 日志：`m2-limit-green.log`

### m3 —— saveScore 单行回读退回全量 filter

- 注入点：`SubjectiveGradingService.saveScore` 回读退回
  `selectWorkbenchRows(examId, questionId).stream().filter(...).findFirst()`（全量工作台行 + 内存过滤）。
- 击红：`mvnw.cmd -o test -Dtest=SubjectiveGradingServiceSaveScoreReadBackTest#saveScoreReadsBackSingleRowInsteadOfFullWorkbenchRows`
  → `Tests run: 1, Failures: 1`；`Wanted but not invoked: selectWorkbenchRow(...)` 且
  `selectWorkbenchRows(...)` 不应被调用——每笔批改保存白载全考场的读放大回归即红。
  - 日志：`m3-readback-red.log`
- 复原 + 回绿：同命令 → `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0` **BUILD SUCCESS**。
  - 日志：`m3-readback-green.log`

### m4 —— progress 两处 .select 投影移除

- 注入点：`GradingQueryService.progress` 两处统计查询移除 `.select(...)`（退回全列载）。
- 击红：`mvnw.cmd -o test -Dtest=GradingQueryServiceProjectionTest#submissionsQueryProjectsOnlyConsumedColumns+subjectiveRowsQueryProjectsOnlySubmissionIdAndScore`
  → `Tests run: 2, Failures: 2`；两处 `getSqlSelect()` 均为 null（answers/comment 等重列随查询全载）。
  - 日志：`m4-projection-red.log`
- 复原 + 回绿：同命令 → `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0` **BUILD SUCCESS**。
  - 日志：`m4-projection-green.log`

> 说明：任务卡建议「m2 投影移除 或 attempt 去 LIMIT 击红」二选一；本笔为把两处优化都纳入
> 变异护栏，选择 m2=attempt 去 LIMIT、m4=投影移除，共四项变异（含等价信息：均非等价变异体，
> 全部击红属实）。

---

## 三、全量后端门禁实测

- 命令：`mvnw.cmd -o clean test`（仓库根）
- 结果（原始输出）：`Tests run: 599, Failures: 0, Errors: 0, Skipped: 1`，`BUILD SUCCESS`。
- 日志：`full-gate.log`
- 基线对照：`.github/ci/gate-baseline.json` 当前 `backendTests = 587`，本笔实测 **599**（净 +12：
  三新单测类 12 例，与基线无减少）。
  **按阶段分工，`gate-baseline.json` 的上调属阶段 3 收口笔，本阶段不修改该文件。**

---

## 四、红线核对

| 红线 | 落实 | 证据 |
|---|---|---|
| ①门控改短路定位 `isWrongQuestionOf`（命中即短路：该生某场 GRADED 快照含该题且判分严格小于满分即通过）；`getStudentWrongQuestionIds` 保留不动 | 新增 `isWrongQuestionOf`，判定口径与 `getStudentWrongQuestionIds` 逐字一致（`rs.score() < rs.max()`，无题型过滤——与旧门控接受集完全等价）；列表页 `getPracticePool`/`getStudentWrongQuestionIds` 零改动 | `gateShortCircuitsAtHitExam`（装配=1）/ m1 击红；`git diff` 确认 `getStudentWrongQuestionIds` 无改动 |
| ②门控定位循环 catch 补 `log.warn` 对齐同文件口径；异常场跳过语义不变 | `isWrongQuestionOf` 循环 catch 记 `log.warn("错题池门控定位成绩单异常，跳过考试...")`（对齐 L93/L265 同型循环），continue 跳过语义不变 | `gateLogsWarnAndSkipsBrokenExamThenFindsQuestionInLaterExam`（红=静默吞、绿=记 warn 且命中场照常通过） |
| ③attempt 连对推导 `.last("LIMIT 3")`；消灭判定连对≥3 不变 | 回读查询 `.last("LIMIT 3")`；mastered 判定仍 `consecutiveCorrect >= 3`；响应 `consecutiveCorrect` 上限即 3（窗口语义，≥3 后数值无意义） | `attemptHistoryReadCappedAtThree` / m2 击红；`masteryDecisionUnchangedWhenThreeRecentCorrect` |
| ④progress 两处 `.select` 消费列投影（submissions：id+grading_status；subjectiveRows：submissionId+score）；统计口径与 `@DS("slave")` 零改动 | 两处 `selectList` 分别 `.select(getId, getGradingStatus)` 与 `.select(getSubmissionId, getScore)`（按方法尾部实际消费直读定列）；`@DS("slave")` 注解与方法体计数逻辑零改动 | `GradingQueryServiceProjectionTest` 两例 / m4 击红；`progressCountsUnchangedWithNarrowProjections` |
| ⑤saveScore 单行回读（优先复用既有 submissionId 单行取数，形态不匹配则新增 mapper 单行方法）；响应形状与 INTERNAL_ERROR 语义不变；乐观锁 CAS 与读己之写零改动 | 新增 `SubjectiveGradeMapper.selectWorkbenchRow(examId, questionId, submissionId)`（列与 `selectWorkbenchRows` 完全一致）；`saveScore` 回读改单行查询，取不到行仍抛 `INTERNAL_ERROR「批改结果回读失败」`；`casSaveScore`、`readYourWriteMark.mark()`、`refreshSubmissionSubjectiveScore` 零改动 | `SubjectiveGradingServiceSaveScoreReadBackTest` 4 例 / m3 击红 |
| 错题判定口径、列表页全量装配、快照回退判分链路零改动 | 门控接受集与旧 `getStudentWrongQuestionIds` 等价；`getPracticePool`/`findSnapshotGradingQuestion`/`loadPublishedExams` 零改动 | `gateScansAllExamsAndRejectsWhenQuestionNotInPool`；`StudentWrongQuestionPracticeIntegrationTest` 全绿（含软删快照回退、列表隐藏已消灭等） |
| 测试严禁真实 `@Sql` 注解 | 新增/改写用例均走 Mockito 隔离单测或既有 `IntegrationTestBase` 真实链路，无 `@Sql` | `grep -rn '@Sql' src/test` 唯一命中为既有类注释（见下） |

### 未触碰面

- `schema.sql`：零 diff（零 DDL）；`docker/`、`openapi.yaml`、`pom.xml`、`frontend/`：零 diff。
- `getStudentWrongQuestionIds`（列表页消费）、`getPracticePool` 全量装配、`findSnapshotGradingQuestion`
  快照回退链路、`loadPublishedExams` 排序口径：零改动。
- `progress` 的 `@DS("slave")` 读写分离注解与计数语义、`saveScore` 的乐观锁 CAS /
  读己之写打点 / 防重与越权校验：零改动。
- 门禁基线 `gate-baseline.json`：本阶段不修改（上调属阶段 3）。

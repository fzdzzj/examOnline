# 候选台账：exam_submissions 标量只读站点（project-scalar-only-submission-reads 阶段 1）

> 本文件是**受版本控制的新候选清单**，替代已过期且被本任务禁改的 `docs/backend-optimization-candidates.md`。
> 阶段 1 只做静态归因与运行时护栏，**零 `src/main` 改动**；是否实施由阶段 2（真引擎分账）按
> `PREREGISTRATION.md` 的 S1–S4 逐站点裁决，阶段 3 只动 GO 站点。
>
> 证据基线：rev `f6e336c`（HEAD），测量工具 sha256 与捕获产物见 §5。

## 0. 站点清单与裁决状态（阶段 2 前，尚无 GO/NO-GO）

| 站点 | 入口（file:line） | 目标语句 ms id | 冻结投影列 | 阶段 1 |
|---|---|---|---|---|
| s1 学生考试列表 | `com.exam.taking.service.ExamTakingService#myExams`（src/main/java/com/exam/taking/service/ExamTakingService.java:148） | `com.exam.submission.mapper.ExamSubmissionMapper.selectList` | `exam_id,status,deadline_time` | ✅ 捕获+护栏全过 |
| s2 缺考标记 | `com.exam.exam.service.AbsenceService#markAbsence`（src/main/java/com/exam/exam/service/AbsenceService.java:70） | 同上 | `student_id` | ✅ 捕获+护栏全过 |
| s3 学生查分 | `com.exam.score.service.ScoreService#myScore`（src/main/java/com/exam/score/service/ScoreService.java:272） | `com.exam.grading.mapper.GradingSubmissionMapper.selectList` | `status,objective_score,subjective_score,total_score,partial_graded` | ✅ 捕获+护栏全过 |
| s4 复核同意调分 | `com.exam.score.service.ScoreReviewService#handle` AGREE 分支（src/main/java/com/exam/score/service/ScoreReviewService.java:134） | 同上 | `id` | ✅ 捕获+护栏全过 |
| s5 补考合并取分 | `com.exam.exam.service.MakeupScoreService#collectFamilyScores`（src/main/java/com/exam/exam/service/MakeupScoreService.java:118） | 同上 | `total_score,submit_time` | ✅ 捕获+护栏全过（2 条语句） |

## 1. 调用链（入口 → 目标语句）

- **s1**：`myExams()` → `examMapper.selectPage`（exams 表，非目标）→ 若考试非空则
  `submissionMapper.selectList(student_id = ? AND exam_id IN (50 个 id))` → `forEach(s -> mine.put(s.getExamId(), s))`
  → 流式组装 `ExamListItem`。
- **s2**：`markAbsence(examId)` → `examMapper.selectById` → `classService.listStudentIds(classId)`（user_class 表）
  → `submissionMapper.selectList(exam_id = ? AND student_id IN (应考名单))` → `map(ExamSubmission::getStudentId)`
  → 差集写成 `absent` 行（INSERT IGNORE）。
- **s3**：`myScore(examId)` → `examMapper.selectById` → 状态校验（须 PUBLISHED）
  → `gradingSubmissionMapper.selectOne(exam_id = ? AND student_id = ?)`（运行时 ms id 即
  `GradingSubmissionMapper.selectList`，`selectOne` 为默认方法委托）
  → 名次 `selectCount`（同表聚合，非本次归因对象）→ 复核中隐藏判定。
- **s4**：`handle(reviewId, req)` → `reviewMapper.selectById` → 状态校验 → `examMapper.selectById`
  → `OwnershipGuard.assertOwner` → AGREE 分支 `gradingMapper.selectOne(exam_id = ? AND student_id = ?)`
  → 仅 `update.setId(submission.getId())` → `gradingMapper.updateById`（只更新 total_score）。
- **s5**：`finalScore(examId, studentId)` → `examMapper.selectById` → `resolveRoot`（沿 parent 向上）
  → `collectFamilyScores(root, studentId)`：`examMapper.selectList(parent_exam_id = root)`（exams 表）
  → 对家族每场（主考 + 补考）`gradingMapper.selectOne(exam_id = ? AND student_id = ? AND total_score IS NOT NULL LIMIT 1)`。

## 2. getter 机械清单（代码引用；与冻结投影逐列对应）

| 站点 | 被取实体 | 入口路径实际调用的 getter（file:line） | 与冻结列对应 |
|---|---|---|---|
| s1 | `ExamSubmission` | `getExamId`（ExamTakingService.java:160）；`getStatus`（:169、:187）；`getDeadlineTime`（:170、:175） | exam_id / status / deadline_time ✅ |
| s2 | `ExamSubmission` | `getStudentId`（AbsenceService.java:84 查询参数、:86 流式 map） | student_id ✅ |
| s3 | `GradingSubmission` | `getTotalScore`（ScoreService.java:291、:305、:327）；`getStatus`（:292）；`getObjectiveScore`（:325）；`getSubjectiveScore`（:326）；`getPartialGraded`（:329） | status / objective_score / subjective_score / total_score / partial_graded ✅ |
| s4 | `GradingSubmission` | `getId`（ScoreReviewService.java:162，AGREE 分支唯一实体 getter） | id ✅ |
| s5 | `GradingSubmission` | `getTotalScore`（MakeupScoreService.java:131、:132）；`getSubmitTime`（:132） | total_score / submit_time ✅ |

清单产法（可复现，只读）：

```bash
grep -n 'submission\.get\|s\.get\|ExamSubmission::get' src/main/java/com/exam/taking/service/ExamTakingService.java
grep -n 'ExamSubmission::getStudentId' src/main/java/com/exam/exam/service/AbsenceService.java
grep -n 'submission\.get' src/main/java/com/exam/score/service/ScoreService.java
grep -n 'submission\.getId()' src/main/java/com/exam/score/service/ScoreReviewService.java
grep -n 'grade\.get' src/main/java/com/exam/exam/service/MakeupScoreService.java
```

域外说明：`ExamTakingService` 其余 getter（:199 起）属于 `buildAnsweringContext`（进考上下文），
本就不在五个只读站点内，且其路径**需要** `paperJson`（个人快照题目）——归入 §4 排除项。

## 3. 捕获的 SQL 原文与 SELECT 列表（PROJ 臂实测，不手写）

工具：`src/test/java/com/exam/scalar/measure/ScalarReadsAttributionMeasureIT.java`（`StatementHandler.prepare`
拦截器读最终 BoundSql + 等价 DefaultParameterHandler 的值解析；写法沿用 archive 的
`MyScoreCountCaptureMeasureIT`）。n=200 形状实测捕获（三形状逐字同构，仅 IN 列表规模不同）：

- **s1（OLD vs PROJ）**
  - OLD：`SELECT id,exam_id,student_id,start_time,deadline_time,submit_time,submit_type,paper_json,answers,status,version,created_time,updated_time FROM exam_submissions WHERE (student_id = ? AND exam_id IN (50 个 ?))`
  - PROJ：`SELECT exam_id,status,deadline_time FROM exam_submissions WHERE (student_id = ? AND exam_id IN (50 个 ?))`
- **s2**：OLD 同 13 列；PROJ `SELECT student_id FROM exam_submissions WHERE (exam_id = ? AND student_id IN (n+5 个 ?))`
- **s3**：OLD 为 GradingSubmission 15 列（`id,exam_id,student_id,submit_time,answers,status,objective_score,subjective_score,total_score,grading_status,grading_error,partial_graded,version,created_time,updated_time`）；
  PROJ `SELECT status,objective_score,subjective_score,total_score,partial_graded FROM exam_submissions WHERE (exam_id = ? AND student_id = ?)`
- **s4**：PROJ `SELECT id FROM exam_submissions WHERE (exam_id = ? AND student_id = ?)`
- **s5（2 条）**：PROJ `SELECT total_score,submit_time FROM exam_submissions WHERE (exam_id = ? AND student_id = ? AND total_score IS NOT NULL) LIMIT 1`
  （主考 E 与补考 F 各 1 条；OLD 为 15 列）

三形状 × 五站点 × 两臂全部 `status=OK`，`problems=[]`（见 §5 捕获 JSON）。

## 4. 排除项（不评估、不改动，逐条登记）

- `MonitorService.overview`：已 NO-GO（前序归因结论），不在本次范围。
- 一切需要 `answers` 做逐题得分的路径：判分、成绩预览、导出个人报告、复核展示、补发扫描。
- `ExamTakingService#buildAnsweringContext`（进考上下文，需 `paperJson` 组题）与交卷/兜底路径
  （`ExamSubmissionMapper.selectByExamStudent` 等其它语句）——不在五个锚点内。
- `MakeupScoreService` 家族 N+1 的批量改造：本任务明确禁止（阶段 3 禁令）。

## 5. 运行时护栏证据与产物

- **隔离机制（环境发现）**：`application-test.yml` 只把 taking-sweep 拉到 1h，
  **考试状态机仍是 10s tick**（`exam.schedule.*`）；其 `autoAdvance` 的「进行中→已结束」分支会调
  `absenceService.markAbsence`，而 markAbsence 的扫描语句正是 s1/s2 的目标语句——会在臂窗口内污染
  条数/行数记账。处置：本 IT 用 `@TestPropertySource` 把 `exam.schedule.initial-delay-ms` 与
  `fixed-delay-ms` 同样拉到 3600000，测量窗口内无任何定时写入或目标语句级联流量。
- **护栏**：目标语句返回实体包裹为 Mockito spy，`getAnswers()/getPaperJson()` 一经调用即抛
  `AssertionError`（「读取即失败」）；非空计数在包裹前统计。三形状全部：
  PROJ 臂 `answersNonNull=0 ∧ paperJsonNonNull=0`（返回实体长字段全 null），
  OLD 臂 `answersNonNull>0`（长字段确实过了传输层），库内同谓词行长字段非空计数 > 0。
- **护栏 canary**（本节自证不空转）：`guardCanary.examSubmissionAnswersThrows=true`、
  `gradingSubmissionAnswersThrows=true`（wrap 后 getter 必抛）。
- **S1 语义等价（阶段 1 预演）**：逐形状两臂输出对拍（s1/s3/s5 响应逐字段、s2/s4 落库快照逐字段），
  三形状全部 `equivalent=true`；s3 名次与独立推算一致、s5 合并值=30.0。
- **产物（仓库外，本文档不搬运二进制）**：
  - 捕获 JSON：`D:\code\examOnline-measure\project-scalar-reads\scalar-reads-capture-cap1.json`
    （sha256 `03b7c6c502c16642de30d1aa254042036ca2f6e98b1ba34f5b2a021316ecd508`）
  - 运行日志：`D:\code\examOnline-measure\project-scalar-reads\mvn-cap1.log`
  - 工具：`src/test/java/com/exam/scalar/measure/ScalarReadsAttributionMeasureIT.java`
    （sha256 `7f03395acdc969cc4ca22c5c8cb37d4d39aa09c7c31a9415e117aac932b5617c`）
  - 运行命令：`./mvnw test -Dtest=ScalarReadsAttributionMeasureIT -DfailIfNoTests=false -Dmeasure.rev=f6e336c -Dmeasure.label=cap1 -Dmeasure.out=D:\code\examOnline-measure\project-scalar-reads`
  - 判据绑定：IT 运行时重算 `evidence/PREREGISTRATION.md` sha256 并与记录比对
    （`preregistration.match=true`，recorded=recomputed=`3366b1eb…3805`）。

## 6. 工具自审记录（首轮缺陷，已修复后才产出本文档证据）

1. 首轮编译失败：`checkArmPair` 入参 `JsonNode`→`ObjectNode` 静态类型不符、误用 `arm.json()`（入参本身即该节点）。
   修复后改走空节点兜底（期望值对不上会如实记 problem，而不是 CCE 掩盖）。
2. **证据节点覆盖缺陷**：Jackson `putObject(field)` 会替换已有同名子节点，逐站点循环里
   `shapeNode.putObject("sites")` 把前四个站点的证据节点整轮清掉（首轮 JSON 只剩 `s5`——
   断言仍全部执行且通过，但 s1–s4 证据丢失）。修复：循环外取一次 `sitesNode`。
   本文档所引捕获 JSON 为修复后重跑的产物。
3. 首版无 canary（护栏可能「没接上也全绿」）；补 canary 后重跑，见 §5。
4. 首版 s1 考试夹具 INSERT 参数 8 个 vs 占位符 7 个、`compareS1Items` 对 null 输出 NPE、多余 import——
   均在首次运行前修复。

## 7. 阶段 2 待办（本台账移交）

- 按 `PREREGISTRATION.md` §2 起一次性 MySQL 8 容器，造 3 形状并实测 `SUM(LENGTH(paper_json)+LENGTH(answers))`。
- 每站点 OLD/PROJ/OLDrep 三臂 ≥5 轮轮转，机械复算 S1–S4，逐站点出 GO/NO-GO。
- 用语纪律：S2 不成立写「字节收益不成立」；S3 不成立写「不稳定/无净收益」；不得混用。

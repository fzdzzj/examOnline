# 口径判定（阶段 1，只读）——fix-my-exams-list-scope

> 开工基点：分支 `feature/update-monitor-overview-submission-projection` @ `7ceabef`（K1–K4 现场核对记录见 `tasks.json` 阶段 0）。
> 判定对象：`ExamTakingService.myExams`（现码）与其前端消费方。
> 判定依据只认 `docs/需求决策记录.md`、`spec/specs/*`、现码与前端实现；打字处均给可回源指针。

## 0. 现行为事实（读码复核，非推断）

- `myExams` 现行为：`published=1` **全局**取数（`Page(1, 50)`、`start_time DESC`）→ 再读该窗口内本人答卷 → 分组。
  窗口之外的考试（无论本人是否参加、是否指派给本人）直接从「我的考试」消失——这是问题陈述第 1 点的核实结论：**成立**。
- 分组：现码已在后端算 `group/canEnter/remainingSeconds`（`ExamListItem` javadoc 口径「以学生下一步动作为准」）。
- 排序：现码列表序 = 全局 `start_time DESC`（窗口副产物，并非为个人列表而排）；**未**按状态分组优先级排——问题陈述第 2 点：**成立**。
- 前端**零**分组/零排序：`frontend/src/hooks/useStudentTaking.ts`「后端 `GET /api/exam-taking/exams` 已经分好组，前端不再按时间重排或过滤」；`frontend/src/components/student/StudentExamList.vue`「三态**只读后端 `group`**……这里一处都不推」——问题陈述第 3 点（前端是否补齐）：**未补齐**。
- 撤回（取消发布）：**不存在该路径**——`setPublished` 全仓库仅 `ExamService.create`（置 0）与 `ExamService.publish`（置 1）两处，无 1→0 的撤回；该场景无需口径。
- 软删：`Exam.isDeleted` 带 `@TableLogic`，查询自动过滤；维持现状。

## 1. 判定 A：列表应包含哪些考试 ——「我的考试」

**判定**：`published=1 ∧ 未软删 ∧ ( 本人有答卷的考试 ∪ 本人当前所在班级绑定的普通考试（parent_exam_id IS NULL） ∪ 本人为候选人的补考 )`。

逐渠道出处：

1. **端点语义＝我的考试**：`ExamTakingController#myExams` 注释「我的考试列表」；openapi `operationId: myExams`；学生成绩页注释「`GET /api/exam-taking/exams` 我的考试列表」。
2. **发布门槛**：`docs/examOnline需求规格说明书.md` 4.2「发布考试（学生可见，支持定时发布）」；`schema.sql` `exams.published` 注释「0=未发布（学生不可见）1=已发布」；现码 `requireEnterableExam` 同门槛；现码 `myExams` 只取 `published=1`。→ 未发布不可见，保持。
3. **「答过的」渠道（答卷）**：`§12.6 学生转班——转班成绩随人。历史成绩跟随学生个人`；`spec/changes/archive/add-class-and-post-exam-closure/proposal.md`「历史转班学生以答卷为准不受影响」；答卷是「参加过的」事实载体（现码 FINISHED 判据同样以答卷为准）。
4. **「被指派的」渠道（班级名册）**：`spec/specs/class-management/spec.md`「班级是应考名单推导的地基（ClassService.listStudentIds）」；`spec/specs/absence-makeup/spec.md` 实施注记「缺考口径 = 应考名单（考试 `class_id` → `user_class` 当前学生）− 有答卷者」；转班仅更新 `user_class.class_id`（`ClassService.transfer` 现码）——「被指派」的常规载体即当前班级名册。
5. **「被指派的」渠道（补考名单）**：`spec/specs/absence-makeup/spec.md`「SHALL 限制仅名单内学生可进入」「准入由 `exam_candidates` 名单限制」；`MakeupService.createMakeup` 落名单、`assertCanEnter` 拦名单外。

边界逐条结论（含两处登记推定）：

- **已结束/已批改/已发布成绩的考试**：包含（`ExamListItem` javadoc FINISHED 定义「已交卷，或考试已结束/已批改」）。
- **撤回**：路径不存在（见 §0），无需口径。
- **软删**：不含（`@TableLogic` 自动过滤，维持现状）。
- **补考对非候选同学**：**不进入**其列表。补考的指派载体是**名单**而非班级名册；否则列表会展示一个点击必 403 的考试（进入闸 `assertCanEnter` 的现码行为与 `PostExamClosureIntegrationTest`「名单外 403」场景一致）。
  〔登记〕规范只逐字约束「进入」的名单限制，未逐字约束「列表显示」；本条为按指派载体语义的推定，已在红测试中显式钉住（阶段 2）。
- **无班级且本人无答卷的已发布考试**：**不进入**任何学生列表（归属渠道缺失 ⇒ 不是任何人的「我的考试」）。
  〔登记〕说明书 4.2 的「学生可见」是发布门槛而非名册规则、§12.9「考试绑定课程+班级」表明班级绑定是既定形态；前端创建页「班级（可选）」允许无班级考试存在，但其「可见给谁」在现存资料中**无逐字出处**，本条为按「我的考试」语义（含本变更问题陈述给出的并集）的推定。若产品需要「公开考试」语义，属另案裁决。
  佐证不冲突：loadtest 夹具的无班级考试不依赖列表可见性（压测直接按 examId 进入，`loadtest/db/01-prepare.sql`）。

## 2. 判定 B：分组与排序职责 —— 都在后端

- 分组：现码已算、前端只读（见 §0 引文）→ 归后端。
- 排序：`§12.1 考试列表组织——状态分组 + 时间排序。待考→进行中→已完成分组，组内按开始时间近→远`；前端不做任何排序（见 §0 引文）→ **判定排序归后端**，本变更要在 `myExams` 内实现。
- 「近→远」读法〔登记〕：采用字面读法「开始时间距当前时刻由近及远」——待考组＝先考的先显示；进行中/已完成＝最近开始的先显示；同距并列以 `start_time`、`exam_id` 兜底保证确定性。无更细出处；测试按此钉住（与需求决策记录 §十九「排序方向为对外契约的一部分，须有测试钉住」同纪律）。

## 3. 判定 C：上界 —— 保留 50，但作用域改为「我的考试」内

- 出处：50 是现码常数（`Page(1, 50)`）；规范无具体数值。
- 判定：保留 50 这一上界形状，改为「排序后对本人集合截断」；不再是全局窗口第 1 页。

## 4. 不变量（本变更不动）

- 分组三态逻辑与 `canEnter/remainingSeconds` 计算、鉴权（类级 `@RequirePermission("exam:take")` + `requireStudent`）、发布语义、软删过滤。
- **s1 列投影不回退**：答复取数仍只取 `exam_id/status/deadline_time`（`ScalarProjectionGuardTest` 继续钉住执行条数/行数/长字段 null）。
- schema.sql/索引/缓存/JVM/线程池/MQ/前端：零改动。

## 5. 既有测试影响预登记（阶段 3 按此处理并如实登记）

- `ExamTakingIntegrationTest.examListGroups`：3 场考试无班级绑定，其中 2 场学生无答卷、依赖全局窗口可见 → 按新口径改为真实指派（建班+入班+`classId`），**分组断言全部保留**（不削弱、不改语义）。
- `ScalarProjectionGuardTest` S1 夹具：`+2` 场无答卷无班级 → 补 `class_id` + `user_class`（该测试 S2 已有同款 JDBC 手法）；列表顺序断言由「start_time 降序」改为「分组优先级 待考→进行中→已完成」；投影三断言（EXECUTIONS/ROWS/LONG_FIELD_NON_NULL）不动。
- `MonitorOverviewProjectionGuardTest` 与其余用例：不消费 `myExams`，不受影响（全仓库仅上述两处测试消费列表端点，另加本变更新增用例）。

## 6. 停手条款自查

- 三项判定（范围/排序职责/上界）均有出处；边界「补考非候选可见性」「无班级考试可见性」为判定 A 并集定义的直接推论，已逐条登记为推定并以红测试显式钉住，不属「无出处空白」。
- 阶段 1 无停手项。

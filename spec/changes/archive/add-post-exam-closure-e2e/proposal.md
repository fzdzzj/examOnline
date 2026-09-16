# 提案：考后闭环端到端验收（并修复 force-end 缺考漏标记，阶段 12）

## Why

`spec/README.md` 遗留 #2 写着「考后闭环缺端到端串联验收」，#3 写着「缺考/补考的真实链路只有 Mockito 单测覆盖」。本提案把这两条真正收口——但在取证过程中发现：**这条链上有两个真缺陷，其中一个只有端到端用例才能暴露**。

### 已核实的事实（全部可复算）

**1）闭环端点至今零集成覆盖。** 全仓库测试里从未出现过这四个端点的调用：

| 端点 | 测试中出现次数 |
|---|---|
| `GET /api/exams/{id}/absences` | 0 |
| `GET /api/exams/{id}/makeup-eligible` | 0 |
| `POST /api/exams/{id}/makeups` | 0 |
| `POST /api/exams/{id}/score-reviews` | 0 |

`grep -rn 'absences\|makeup-eligible\|/makeups\|score-reviews' src/test/java/` 只命中 `AbsenceServiceTest`（用 `ArgumentCaptor` 断言批量写入参数）与几处 `force-end`。唯一的 `AbsenceServiceTest` 把 `ClassService` mock 掉了——**应考名单的真实推导（`class_id` → `user_class`）从未被执行过**。

**2）覆盖率最低的类与闭环完全重合（JaCoCo 实测，基线 182 用例）：**

| 类 | 指令 | 行 |
|---|---|---|
| `MakeupService` | **11.8%** | 16.3% (16/98) |
| `GradingQueryService` | 16.5% | 24.3% |
| `ScoreReviewController` | 22.2% | 42.9% |
| `MakeupScoreService` | 41.3% | 42.3% |
| `AbsenceService` | 60.9% | 63.2% |
| （全项目） | 86.9% | 87.5%（分支 67.8%） |

**3）`@Sql` 自建表仍在掩盖 schema 回归——且已被实测证明是死代码。**
`ClassManagementIntegrationTest` 是全仓库**唯一**用 `@Sql` 的测试，它自建 `classes` / `user_class`；而 `schema.sql` 早已含这两表（318 行 / 333 行，共 24 表）。测试类 javadoc 甚至写着「测试库（H2）只加载 schema.sql，classes/user_class 由本类用 @Sql 幂等补建」——**这句话现在是错的**。

> **实测（本次取证，非推测）**：把该 `@Sql` 注解块整段删掉后单独跑 `ClassManagementIntegrationTest` → **2/2 通过，BUILD SUCCESS**。说明 `schema.sql` 自己能建这两张表，该 `@Sql` 是纯冗余。
> 它的危害不是"多余"，而是**恰好掩盖了曾经真实发生过的回归**：`schema.sql` 曾缺 `classes`/`user_class`（commit `ecc0fe6` 才补上），而当时 CI 全绿。只要这段 `@Sql` 还在，同类回归会再次静默通过。

**4）实锤缺陷一：`force-end` 路径永不标记缺考。**
只有两处把考试迁到 `已结束`，而 `markAbsence` 全仓库**只有一个调用点**：

```
ExamService.forceEnd()                    → casTransition(进行中, 已结束)   ← 不调用 markAbsence ❌
ExamStateMachineService.autoAdvance()     → casAdvanceQuietly(已结束) + absenceService.markAbsence(id) ✅
```

- `autoAdvance()` 的结束扫描条件是 `eq(status, STATUS_IN_PROGRESS).le(endTime, now)`。
- 考试一旦被 `force-end` 置为 `已结束`，**就再也不会出现在该列表里** → `markAbsence` 永不触发，**且无法自愈**。
- `ExamSweepService`（force_end 的强制交卷收口）经 grep 确认**完全不涉及缺考**；`AbsenceService` 自身没有 `@Scheduled` / `@EventListener`。

**后果**：教师「提前结束」是真实且常用的操作（`ExamTakingIntegrationTest.sweepForceSubmitsAfterTeacherForceEnd` 就在用它），而**这类考试一条缺考记录都不会有** → `makeup-eligible` 的缺考来源恒为空，教师无法按缺考勾选补考学生。这与已合入的规范直接矛盾：

> `spec/specs/absence-makeup/spec.md`：WHEN **考试结束**, 系统 SHALL 将「应考名单中无答卷记录的学生」标记为缺考。

规范原文只写了"考试结束"这个泛化条件，实现把它读成了"自然到点结束"——**规范的模糊处正是缺陷的藏身处**，故本次不只在代码里修，也把规范写清楚。

**5）实锤缺陷二（超出本提案范围，见下节）**：`MakeupScoreService` 无任何调用方。

**6）实锤缺陷三：`score_review` 表缺 `created_time` 列 → 复核申请接口 100% 失败（实施期由新用例挖出）。**

新串联用例一跑，**复核相关的 3 个用例全部 500**。从 surefire 报告取到根因（非推测）：

```
Caused by: org.h2.jdbc.JdbcSQLSyntaxErrorException: Column "created_time" not found; SQL statement:
INSERT INTO score_review ( exam_id, student_id, status, reason, apply_time, created_time ) VALUES ( ... )
```

对照取证：

| 位置 | 事实 |
|---|---|
| `score/entity/ScoreReview.java:66-67` | `@TableField(fill = FieldFill.INSERT) private LocalDateTime createdTime;` |
| `config/MybatisPlusConfig.java:22-25` | 存在全局 `MetaObjectHandler.insertFill`，项目统一自动填充 `createdTime` |
| `schema.sql:369-382` | `score_review` 建表语句里**没有** `created_time`（只有 `apply_time`） |
| `docker/mysql/migrations/2026-W10-add-score-review.sql` | 同样**没有** `created_time` |

即：**新建库与存量库都缺这一列**，MyBatis-Plus 的 insert 带上了实体字段 → 该 INSERT 在任何环境都必失败。**复核申请接口从未成功执行过一次**。

**全库扫描确认这是孤立缺陷**（脚本比对 19 个含 `createdTime` 的实体的 `@TableName` 与 `schema.sql` 对应建表语句）：

```
OK  classes / exam_absence / exam_behavior_logs / exam_candidates / exam_snapshots
OK  exam_submissions / exam_submit_dedups / exams / invite_codes / paper_snapshots
OK  papers / permissions / questions / roles / score_audit_logs / subjective_grades
OK  tags / users
MISS score_review          ← 唯一一处
```

**这正是遗留 #3 的同型问题**：闭环四个端点零集成覆盖，所以"代码与 schema 不一致"可以一直不被发现——`ScoreReviewService`/`Controller` 在 JaCoCo 里只有 22.2%，因为没有任何用例真的走到 INSERT。

**为什么必须并入本提案而不是单独立项**：本提案的验收标准就是「复核申请 → 复核处理」这段链路跑通；不修这一列，主用例与两个复核边界用例**永远红**。修它是本提案的**前置条件**，不是可选项。

### 期望状态

一条真实链路的端到端用例（建班 → 入班 → 建卷 → 建考试 → 结束 → 缺考标记 → 筛补考名单 → 建补考 → 名单限制 → 批改 → 汇总 → 发布 → 复核申请 → 复核处理），**两条结束路径（自然到点 / 教师提前结束）都覆盖**，全程**不使用 `@Sql`**，从而同时证明「schema.sql 足以建起整条链所要的表」。过程中挖出的两个真缺陷（`force-end` 漏标记缺考、`score_review` 缺列致复核申请必失败）一并修掉——**它们正是"零集成覆盖"这个状态长期存在的产物**。

## What Changes

### 1. 新增 `src/test/java/com/exam/closure/PostExamClosureIntegrationTest.java`（零 `@Sql`）

继承既有 `IntegrationTestBase`（`@SpringBootTest` + MockMvc + H2 + 真实 Redis db15），复用既有手法：
`@MockitoBean RabbitTemplate` + 手工驱动 `submitConsumer.onBatch(...)` 让答案真落库；`stateMachineService.autoAdvance()` 驱动状态机。

**主用例 `fullClosureChainEndToEnd`**（自然到点结束路径）：

1. 建班 → 两名学生入班（`user_class`）；
2. 建题 → 建卷 → 绑题 → 建考试（**`classId` 必填**——`markAbsence` 靠它推导应考名单，`class_id` 为空会直接 return 0）；
3. 发布考试 → `autoAdvance()` 推进到进行中；
4. 学生 A `enter` + `submit`，再驱动消费者落库（A 有答卷）；**学生 B 从不进入**（无答卷 → 缺考）；
5. 把 `endTime` 拨到过去 → `autoAdvance()` → 已结束 + 标记缺考；
6. 断言 `GET /{id}/absences` 恰好只有 B；
7. 断言 `GET /{id}/makeup-eligible` 只有 B 且 `reason = ABSENT`；
8. `POST /{id}/makeups`（名单 `[B]`）→ 断言补考是独立记录（`parentExamId` 指向主考、独立时间窗/时长）；
9. 补考发布 + `autoAdvance()` → 断言**名单限制**：B 可进入、A 被拒（403）；
10. 主考批改（`POST /{id}/grading/run`）→ 汇总（`POST /{id}/scores/summarize`，此处 `已结束→已批改`）→ 发布（`POST /api/scores/publish`，`已批改→已发布` + 落 PUBLISH 审计）；
11. B 申请复核（`POST /api/exams/{examId}/score-reviews`）→ 断言之（成绩隐藏期）；
12. 教师处理（`POST /api/score-reviews/{reviewId}/handle`，`AGREE` + 调分）→ 断言成绩被更新、复核状态 `AGREED`、隐藏解除。

**边界用例**（每条都对应一个真实不变量，不做凑数断言）：

- `forceEndAlsoMarksAbsence` —— **覆盖缺陷一**：走教师提前结束路径，断言缺考照样被标记（修复前必红）；
- `markAbsenceIsIdempotent` —— 同一场考试重复触发结束/重复 `markAbsence`，`exam_absence` 行数不变（唯一索引 + `INSERT IGNORE` 的真实执行，这也是该 SQL 在 H2 上的首次真跑）；
- `examWithoutClassSkipsAbsence` —— `class_id` 为空的考试不标记缺考（无应考名单可言）；
- `makeupRequiresEndedMainExam` / `makeupCannotChain` —— 主考未结束不能开补考；不能以补考再开补考；
- `reviewOncePerExamAndOwnerOnly` —— 复核限 1 次（重复 → `DATA_ALREADY_EXISTS`）；非归属教师处理 → 403；已处理再处理 → `BAD_REQUEST`。

### 2. 修复 `force-end` 缺考漏标记（`ExamService.forceEnd`）

- 在 `casTransition(进行中, 已结束)` 之后调用 `absenceService.markAbsence(id)`——这是 `autoAdvance` 同一步骤的**对称补齐**：两者都是「进行中→已结束」，缺考标记本就该锚定这一刻（`AbsenceService` 的类注释写的就是「状态机『进行中→已结束』迁移时」）。
- `ExamService` 构造器新增 `AbsenceService` 形参。**无循环依赖**：`AbsenceService` 只依赖 Mapper + `ClassService`，不反向依赖 `ExamService`（`ExamStateMachineService` 亦已如此注入）。
- 事务安全：`forceEnd` 已是 `@Transactional(rollbackFor = Exception.class)`，`markAbsence` 同传播级别 → 同事务，不会出现「状态已结束但缺考未标记」的半成品。
- **为什么不会误伤"答了但没交卷"的学生**：`markAbsence` 的「有答卷者」查询按 `exam_id + student_id` 取 `exam_submissions` 行、**不筛 status**。force-end 时进行中的学生已有 `IN_PROGRESS` 答卷行 → 不在缺考名单；只有从未进入（无答卷行）的学生才判缺考。与自然结束路径语义完全一致。

### 3. 删除 `ClassManagementIntegrationTest` 的 `@Sql` 死代码

- 删掉 `@Sql(statements = {...})` 注解块与 `import org.springframework.test.context.jdbc.Sql;`；
- 同步改掉类 javadoc 里那句已不成立的「classes/user_class 由本类用 @Sql 幂等补建」，改为「由 `schema.sql` 建全，本类不自行建表」。
- 安全性已实测（上文事实 3）：删掉后该测试 2/2 通过。
- 收益：该测试从"自建表、掩盖 schema 缺表"变为**真正证明 `schema.sql` 建得起 `classes`/`user_class`**；`grep -rn '@Sql' src/test` 归零，让这条规则可被机器检查。

### 4. 覆盖率收口（量化目标）

| 类 | 现在 | 目标 |
|---|---|---|
| `MakeupService` | 11.8% | ≥ 70% |
| `AbsenceService` | 60.9% | ≥ 85% |
| `ScoreReviewController` | 22.2% | ≥ 80% |

以"走真实链路 + 真实 SQL"达成，而非堆 mock 单测。`MakeupScoreService` **不在本目标内**（它需要先被接入，见下节）。

### 5. 修复 `score_review` 缺 `created_time`（缺陷三，实施期挖出）

- `src/main/resources/schema.sql`：「新建库一次建全」的 `score_review` 建表语句补上
  `created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP`（与 `score_audit_logs` 等 18 张表的既有写法一致），位置紧接 `handler_id` 之后、约束之前。
- 新增 `docker/mysql/migrations/2026-W15-add-score-review-created-time.sql`：存量库手工执行
  `ALTER TABLE score_review ADD COLUMN created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP;`
  （幂等性说明写在脚本头：MySQL 8 重复执行报 Duplicate column name，可忽略——与既有迁移文件的约定一致。）
- **为什么是补列而不是删实体的 `createdTime` 字段**：`MybatisPlusConfig` 的全局 `MetaObjectHandler` 自动填充 `createdTime`，全库 19 个实体、18 张表都按此约定；`score_review` 是**唯一**例外。补列让它与全库一致，改实体则会引入"这一张表为什么特殊"的额外解释成本。
- **修复的验证方式**：三个此前 500 的用例（`fullClosureChainEndToEnd`、`reviewOncePerExamAndOwnerOnly`、`reviewAlreadyHandledRejected`）转绿，即证明该 INSERT 真的能执行。**不接受"改了 schema 但没跑用例"作为完成**。
- **注意**：本步骤改的是 `schema.sql`——本提案**原先把它列为"不要触碰"**，现据实测证据放开（原假设"闭环所需表已齐"被证伪）。这是提案在实施期被证据修正的正常情况，如实记录在此。

## 核验中发现、但**不属本提案范围**的问题

**`MakeupScoreService` 无任何调用方 → 规范「补考成绩规则」从未生效。**

- `grep -rn 'finalScore|mergeFinalScore|MakeupScoreService' src/` 结果：`finalScore(...)` 全仓库**零调用**；`mergeFinalScore(...)` 只被 `MakeupScoreServiceTest`（纯函数单测）调用；没有任何"补考最终成绩"接口。
- 而 `spec/specs/absence-makeup/spec.md` 的 `Requirement: 补考成绩规则` 已合入并承诺：WHEN 计算补考最终成绩, 系统 SHALL 按配置规则（取最高分/取最近一次/取平均分）合并——**这是一条已验收但未接线的需求**。
- 为什么不在本提案里做：接线需要**新增接口/查询路径**（属功能变更，且要想清"最终成绩在哪个入口暴露：学生查成绩？导出？"），与"补测试、清死代码"的变更性质不同。混在一起会让归档产出的规范差异含混。
- 建议单独立项 `add-makeup-final-score`（阶段 13），并在 `spec/README.md` 遗留清单先登记，避免它继续隐形。

## Impact

### 受影响的规范
- `spec/specs/absence-makeup/spec.md`：
  - `MODIFIED`「缺考标记」——把"考试结束"写清为**所有**结束路径（自然到点 / 教师提前结束），并补「重复触发不重复标记」场景；
  - `ADDED`「考后闭环跨环节一致性」——缺考名单与补考可选名单同源、补考独立于主考且仅名单内可进入。

### 受影响的文件（写入边界）
- 新增 `src/test/java/com/exam/closure/PostExamClosureIntegrationTest.java`
- 修改 `src/main/java/com/exam/exam/service/ExamService.java`（仅 `forceEnd` + 构造器注入 `AbsenceService`）
- 修改 `src/test/java/com/exam/clazz/ClassManagementIntegrationTest.java`（删 `@Sql` + 改 javadoc）
- 修改 `src/main/resources/schema.sql`（**仅**给 `score_review` 补 `created_time` 一列，不动其他 23 张表）
- 新增 `docker/mysql/migrations/2026-W15-add-score-review-created-time.sql`

**不要触碰**：`AbsenceService` / `MakeupService` / `ScoreReviewService` 的业务逻辑（本次只覆盖与验证）、`MakeupScoreService`（属另一立项）、`application-test.yml`、`pom.xml`。

### 需要迁移
- [x] 数据库迁移：**给存量库补 `score_review.created_time`**（`docker/mysql/migrations/2026-W15-add-score-review-created-time.sql`）；新库由 `schema.sql` 建全
- [ ] 配置变更（无）
- [x] 文档更新（本提案 + 规范 delta + `spec/README.md` 遗留 #2/#3 收口、新登记 `MakeupScoreService` 未接线）

## 时间线评估

中：约 1–1.5 天（W13-W14）。串联用例本身不难，难在把 12 步的前置条件都摆对（尤其 `classId`、`published`、状态机各跳）。

## 风险

- **缺陷一修复会改变已有行为** —— `force-end` 之后会新增 `exam_absence` 写入。需确认既有用例不受影响：现有 `force-end` 用例的考试**都没绑 `classId`**，`markAbsence` 会在 `exam.getClassId() == null` 处直接 return 0，因此**不写任何行**，既有断言不受影响。这一点必须在回归里显式确认（全量测试应仍全绿）。
- **H2 与 MySQL 的方言差** —— 缺考链路依赖 `INSERT IGNORE`（MySQL 专有）。**已实测**：H2 2.3.232 与 2.2.224 在 `MODE=MySQL` 下均支持，且重复插入影响 0 行、批量形态正确跳过已存在行。故该风险已排除，但串联用例会成为这条 SQL 的**首个真实执行者**，凡是"首次真跑"都可能翻出新问题，这是本提案的主要不确定来源。
- **端到端用例易碎** —— 12 步链路对时间窗口/状态敏感。缓解：用例内不复用其他用例的数据（`unique()` 造唯一名）；时间窗用 `now()` 相对量；各步显式断言 HTTP 状态便于定位；`application-test.yml` 已把定时扫描间隔拉到 1h，不会与手工驱动冲突。
- **覆盖率目标可能达不到** —— 若 `MakeupService` 的某些分支在真实链路下确实不可达（如 `validateWindowAndDuration` 的负值分支已由单测覆盖），则不硬凑：在任务里写明实际值与未达原因，**不为了数字写假测试**。
- **"删 `@Sql` 后测试仍绿"不等于"schema 永远够用"** —— 它只证明**这次**够用。真正的保障是让 `@Sql` 归零后，任何缺表都会让测试立刻红。故删除本身即是修复。
- **该预测已被验证（实施期）** —— 上文风险里写过"凡是首次真跑都可能翻出新问题"，结果第一次跑串联用例就翻出了缺陷三（`score_review` 缺 `created_time`）。这恰好说明了本提案的价值：**不是测试写得漂亮，而是它让一个从未被执行过的 INSERT 第一次被执行**。
- **改 `schema.sql` 后，存量库必须手工跑迁移** —— 否则 dev/prod 仍会在复核申请上 500。这条要写进 `spec/README.md` 工作流或迁移脚本头注释（新库 `CREATE TABLE IF NOT EXISTS` **不会**为已存在的表补列，这是本仓库的既有坑，与 `classes`/`user_class` 那次同源）。
- **已交付的用例里 4 个红，其中 3 个红在缺陷三、1 个红在缺陷一** —— 这两个红都是**预期的红**（先红后绿才能证明用例有效）。收尾时必须确认：修完之后这 4 个全部转绿，且**不是因为放宽断言而转绿**。

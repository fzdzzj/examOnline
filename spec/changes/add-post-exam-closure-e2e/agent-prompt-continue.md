# 子 agent 提示词（续做）—— `add-post-exam-closure-e2e`（阶段 12）

> 用法：整份复制发给子 agent。它是自包含的，不依赖任何先前对话。
> 适用场景：上一轮已交付测试文件但**未完成源码修改**，本提示词用于续做并收尾。

---

## 现状（已核实的起始状态，不要重复劳动）

在 `D:\code\examOnline` 项目里，上一轮已经交付：

- ✅ **新增文件 `src/test/java/com/exam/closure/PostExamClosureIntegrationTest.java`**（约 26KB，8 个 `@Test`，已能编译通过）。它包含主链路用例 `fullClosureChainEndToEnd` 与 7 个边界用例：`forceEndAlsoMarksAbsence` / `markAbsenceIsIdempotent` / `examWithoutClassSkipsAbsence` / `makeupRequiresEndedMainExam` / `makeupCannotChain` / `reviewOncePerExamAndOwnerOnly` / `reviewAlreadyHandledRejected`。**这个文件基本可用，以修补为主，不要推倒重写。**

- ❌ **`src/main/java/com/exam/exam/service/ExamService.java` 未改**（`forceEnd` 里没有 `absenceService.markAbsence(...)`，构造器也没有注入 `AbsenceService`）。
- ❌ **`src/test/java/com/exam/clazz/ClassManagementIntegrationTest.java` 未改**（`@Sql(statements = {...})` 注解散块与那句已不成立的 javadoc 都还在）。
- ❌ **`src/main/resources/schema.sql` 未改**（`score_review` 表仍缺 `created_time` 列）。

**当前实跑结果（已实测，不是推测）**：

```
[ERROR] Tests run: 8, Failures: 4, Errors: 0, Skipped: 0
[ERROR]   PostExamClosureIntegrationTest.forceEndAlsoMarksAbsence:216 提前结束同样必须标记缺考 ==> expected: <1> but was: <0>
[ERROR]   PostExamClosureIntegrationTest.fullClosureChainEndToEnd:175->IntegrationTestBase.perform:219 Response status expected:<200> but was:<500>
[ERROR]   PostExamClosureIntegrationTest.reviewAlreadyHandledRejected:321->applyReview:446->IntegrationTestBase.perform:219 Response status expected:<200> but was:<500>
[ERROR]   PostExamClosureIntegrationTest.reviewOncePerExamAndOwnerOnly:303->applyReview:446->IntegrationTestBase.perform:219 Response status expected:<200> but was:<500>
```

**这 4 个红是"预期的红"**——它们证明了用例有效（不是空转）。你要做的是把它们**用修缺陷的方式**变绿，**绝不允许靠放宽断言、注释用例、加 `@Disabled` 变绿**。

**两个缺陷的根因都已查清（直接照做，不要重新排查）：**

### 缺陷一：`force-end` 路径永不标记缺考
- 全仓库把考试迁到「已结束」的只有两处：`ExamStateMachineService.autoAdvance()`（**有**调 `absenceService.markAbsence(id)`）与 `ExamService.forceEnd()`（**没有**）。
- 而 `markAbsence` 的唯一调用点就是 `autoAdvance` 的结束分支，其扫描条件是 `status = IN_PROGRESS` → 考试被 force-end 置为 ENDED 后再也不会被扫到，**永久漏标记且无法自愈**。
- **修法**：`ExamService` 构造器注入 `AbsenceService`（无循环依赖：`AbsenceService` 只依赖 Mapper + `ClassService`，不反向依赖 `ExamService`）；在 `forceEnd` 的 `casTransition(IN_PROGRESS, ENDED)` 成功之后调用 `absenceService.markAbsence(id)`。`forceEnd` 已是 `@Transactional(rollbackFor = Exception.class)`，同事务、无半成品。
- 注意：既有 force-end 用例造的考试都没有 `classId`，`markAbsence` 会在 `exam.getClassId() == null` 处 `return 0`、不写行，所以既有断言不受影响（收尾时要在回归里确认这一点）。

### 缺陷三：`score_review` 表缺 `created_time` 列 → 复核申请 100% 失败
surefire 报告里的根因（已取到，非推测）：

```
Caused by: org.h2.jdbc.JdbcSQLSyntaxErrorException: Column "created_time" not found; SQL statement:
INSERT INTO score_review ( exam_id, student_id, status, reason, apply_time, created_time ) VALUES ( ... )
```

取证链：
| 位置 | 事实 |
|---|---|
| `src/main/java/com/exam/score/entity/ScoreReview.java:66-67` | `@TableField(fill = FieldFill.INSERT) private LocalDateTime createdTime;` |
| `src/main/java/com/exam/config/MybatisPlusConfig.java:22-25` | 存在全局 `MetaObjectHandler.insertFill`，项目统一自动填充 `createdTime` |
| `src/main/resources/schema.sql` 的 `score_review` 建表语句 | **没有** `created_time` |
| `docker/mysql/migrations/2026-W10-add-score-review.sql` | 同样**没有** `created_time` |

即**新建库与存量库都缺这一列** → 该 INSERT 在任何环境都必失败，**复核申请接口从未成功执行过一次**。全库脚本比对确认这是**孤立**缺陷（19 个含 `createdTime` 的实体里，只有 `score_review` 对应表缺列，其余 18 张全 OK）。

- **修法**：补列，**不要**删实体的 `createdTime` 字段（全库 18 张表都按"自动填充 `createdTime`"的约定，改实体反而制造特例）。
- `schema.sql` 的 `score_review` 建表语句里，在 `handler_id` 之后、`CONSTRAINT uk_review_exam_student` 之前，插入 `created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP`（与 `score_audit_logs` 的写法一致）。
- 新增 `docker/mysql/migrations/2026-W15-add-score-review-created-time.sql`：
  `ALTER TABLE score_review ADD COLUMN created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP;`
  脚本头注释写明：存量库**必须**手工执行（`CREATE TABLE IF NOT EXISTS` 不会为已存在的表补列，这是本仓库的既有坑）；重复执行 MySQL 8 报 Duplicate column name，可忽略。

---

## 你要完成的事（对照 `spec/changes/add-post-exam-closure-e2e/tasks.json`）

1. **tasks 阶段 1**：修 `ExamService.forceEnd`（注入 `AbsenceService` + CAS 成功后 `markAbsence(id)` + 补注释）。
2. **tasks 阶段 5**：修 `score_review` 缺列（改 `schema.sql` + 新增迁移脚本 + 补注释说明为何是补列而非改实体）。
3. **tasks 阶段 4**：删 `ClassManagementIntegrationTest` 的 `@Sql` 注解散与 `import org.springframework.test.context.jdbc.Sql`，把 javadoc 里那句「classes/user_class 由本类用 @Sql 幂等补建」改成「由 `schema.sql` 建全，本类不自行建表」；确认 `grep -rn '@Sql' src/test` 为空。
4. **重跑** `PostExamClosureIntegrationTest` → 8/8 全绿（那 4 个红必须全部转绿）。
5. **覆盖率核对**（JaCoCo）：`MakeupService` ≥70%、`AbsenceService` ≥85%、`ScoreReviewController` ≥80%；未达到就在回报里写实际值与原因，**不为了数字写假测试**。
6. **全量回归**：`mvn test` 全绿（测试只增不减）。基线以你自己开工时实测的数字为准（开工前先跑一次记下来）。
7. 若发现 `Connection refused`（`java.net.ConnectException`）之类的日志，**先确认它是测试环境的既有噪声**（本测试环境用 `@MockitoBean RabbitTemplate`，没有真 RabbitMQ），不要为了消除它去改配置；若它确实导致某个用例失败，如实回报。

---

## 必须遵守的项目硬约定（违反任何一条都算未完成）

1. **集成测试禁止用 `@Sql` 自建表**。测试库建表只以 `src/main/resources/schema.sql` 为唯一来源（`application-test.yml` 已配 `mode: always` + `continue-on-error: false`）。本任务结束后 `grep -rn '@Sql' src/test` **必须为空**。
2. **不要为了让测试变绿而放宽断言、注释用例、加 `@Disabled`**，也不要为了让删除生效而放宽任何业务边界。
3. **不要改动 `PostExamClosureIntegrationTest` 已通过的 4 个用例**，也不要为了迁就实现而削弱那 4 个失败用例的断言强度。如果某条断言确实写错了（例如端点路径/枚举名与实际不符），**在回报里明确说明"是哪条断言写错、依据是什么、改了什么"**，不要默默改。
4. **不新增依赖**（`pom.xml` 不动）；**不改** `AbsenceService` / `MakeupService` / `ScoreReviewService` 的业务逻辑（本次只覆盖与验证）；**不改** `MakeupScoreService`（属单独立项）；**不改** `application-test.yml`。
5. **`@Transactional(rollbackFor = Exception.class)`** 是本项目统一写法，新增/修改的事务方法照此写。
6. 包/表命名坑：`class` 是关键字 → 包 `com.exam.clazz`、实体 `ClassEntity`、表 `classes`。错误码 `ResponseCode.TOO_MANY_REQUESTS`（**不存在 `RATE_LIMITED`**）。
7. 强一致读（答卷详情/成绩）**不加 `@DS("slave")`**，走主库。

---

## 构建与测试命令（本机环境特殊，必须用这条）

本机 `JAVA_HOME` 未设置，PATH 里 `java` 是 1.8 而 `javac` 是 21，Git Bash 的 `mvn` 脚本跑不起来（Plexus classworlds Launcher 路径错）。**必须直调 launcher**：

```bash
cd /d/code/examOnline
'D:\develop\jdk177\bin\java.exe' -classpath 'D:\develop\Maven\apache-maven-3.9.4\boot\plexus-classworlds-2.7.0.jar' -Dclassworlds.conf='D:\develop\Maven\apache-maven-3.9.4\bin\m2.conf' -Dmaven.home='D:\develop\Maven\apache-maven-3.9.4' -Dmaven.multiModuleProjectDirectory='D:\code\examOnline' org.codehaus.plexus.classworlds.launcher.Launcher -o test
```

单类跑：结尾换成 `-o test -Dtest=PostExamClosureIntegrationTest -DfailIfNoTests=false`。
`-o`（离线）必须带，`.mvn/maven.config` 会自动附加 `-s maven-settings.xml`。

---

## 回报格式（请严格按此回报）

1. **基线**：你开工前跑的那次全量 `tests run / failures / errors`。
2. **改动清单**：每个文件一行，说明改了什么（关键方法名）。
3. **修复前后对照**：4 个红 → 修复后各自的状态，**必须贴出修复后 `PostExamClosureIntegrationTest` 的实际结果行**（例：`Tests run: 8, Failures: 0, Errors: 0`）。
4. **覆盖率**：三个类的实测指令/行覆盖，与目标的差距。
5. **收尾数字**：全量 `tests run / failures / errors` + 新增用例数。
6. **`grep -rn '@Sql' src/test` 的实际输出**（应为空）。
7. **逐条对照 `tasks.json`**：每个 step 是"已完成"还是"未完成/替代做法"，未完成的**必须说明原因**，不要勾满。
8. **意外发现**：任何与上述描述不符的事实（包括你发现我之前写的断言其实是错的），如实写。
9. **不要自称"已验证"**：只能报告你实际跑过的命令与输出数字。

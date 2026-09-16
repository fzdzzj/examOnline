# 子 agent 提示词（第 2 轮续做）—— `add-post-exam-closure-e2e`（阶段 12）

> 用法：整份复制发给子 agent。它是自包含的，不依赖任何先前对话。
> 适用场景：第 1 轮已交付测试文件；第 2 轮已修 3 处中的 2 处并走了一条需纠正的路线。本提示词用于收尾。

---

## 现状（已核实的起始状态，不要重复劳动）

### 已经做对的部分（保持，不要回退）

**① `ExamService.forceEnd` 的缺考修复已完成且正确**（`src/main/java/com/exam/exam/service/ExamService.java`）：
构造器已注入 `AbsenceService`，`stateMachineService.casTransition(id, STATUS_IN_PROGRESS, STATUS_ENDED)` 之后已调用 `absenceService.markAbsence(id)`，注释也已写明"先迁状态再标记缺考"。**这部分不要动。**

**② `ClassManagementIntegrationTest` 的 `@Sql` 已删除**（注解散与 `import` 均去掉了），实测该测试 **2/2 通过**。`grep -rn '@Sql' src/test` 已为空。**这部分不要动。**

**③ `src/test/java/com/exam/closure/PostExamClosureIntegrationTest.java` 已存在**（8 个 `@Test`），**以修补为主，不要推倒重写**。

### 当前实测结果

```
PostExamClosureIntegrationTest: Tests run: 8, Failures: 0, Errors: 1, Skipped: 0
  fullClosureChainEndToEnd -- NullPointerException
    Cannot invoke "JsonNode.isNull()" because the return value of "JsonNode.get(String)" is null
    at PostExamClosureIntegrationTest.java:182
ClassManagementIntegrationTest: Tests run: 2, Failures: 0, Errors: 0
```

**从 4 红降到 1 个 error**，只差最后一步。

### 需要纠正的部分（重点）

**④ 缺陷三的修法走错了路线，必须改回"补列"。**

上一轮把 `src/main/java/com/exam/score/entity/ScoreReview.java` 里的

```java
@TableField(fill = FieldFill.INSERT)
private LocalDateTime createdTime;
```

**整段删掉了**（连同 `FieldFill`、`TableField` 两个 import），走的是"删实体字段"路线。

**已确认的决策：改回"补列"路线**——保留实体的 `createdTime` 字段，改成给表补上 `created_time` 列。理由（用户确认）：全库 18 张表都按"`MybatisPlusConfig` 的全局 `MetaObjectHandler.insertFill` 自动填充 `createdTime`"这一约定，`score_review` 不应成为唯一特例。

另外该文件现在**末尾缺少换行**（diff 显示 `\ No newline at end of file`），一并修掉。

**⑤ 剩余 1 个 error 是测试断言写错了**（不是产品缺陷，但也不是"放宽断言"，见下）。

`fullClosureChainEndToEnd` 第 182 行：

```java
assertTrue(hidden.get("totalScore").isNull(), "复核中不返回分数");
```

**根因（已取证）**：`src/main/resources/application.yml:13` 配了 `spring.jackson.default-property-inclusion: non_null`，**全局把 null 字段从 JSON 里整个省略**。而 `ScoreService` 在"复核中"分支里做的是 `response.setTotalScore(null)`（`ScoreService.java:281-288`）——所以响应里 **`totalScore` 这个 key 根本不存在**，`hidden.get("totalScore")` 返回 Java `null`，`.isNull()` 于是 NPE。

**正确写法**（语义不变、事实上更强——"字段完全不存在"比"字段为 null"是更强的陈述）：

```java
assertNull(hidden.get("totalScore"), "复核中不返回分数");
```

若 `assertNull` 未 import，补 `import static org.junit.jupiter.api.Assertions.assertNull;`。

**注意**：这是一处**断言与项目全局 Jackson 配置相矛盾**的事实性错误，修正它是正确的。**不要**借这个理由去放宽其他任何断言；同一文件里其他 `get("...")` 的调用请自查一遍是否也踩了同款陷阱（凡是被显式 `setXxx(null)` 的字段都会从 JSON 里消失），若发现同类问题按同样方式修正并在回报里逐条列出。

---

## 你要完成的事

1. **回退 `ScoreReview.java`**：恢复 `createdTime` 字段与 `@TableField(fill = FieldFill.INSERT)`，恢复 `FieldFill`、`TableField` 两个 import，补文件末尾换行。
2. **`src/main/resources/schema.sql`**：在 `score_review` 建表语句里，`handler_id` 之后、`CONSTRAINT uk_review_exam_student` 之前，插入
   `created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP`（与同文件 `score_audit_logs` 的写法一致）。**只动 `score_review` 一张表**，其他 23 张不要碰。
3. **新增 `docker/mysql/migrations/2026-W15-add-score-review-created-time.sql`**：
   `ALTER TABLE score_review ADD COLUMN created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP;`
   脚本头注释写明：存量库**必须**手工执行（`CREATE TABLE IF NOT EXISTS` 不会为已存在的表补列，这是本仓库的既有坑，`classes`/`user_class` 那次就是这么翻车的）；重复执行 MySQL 8 报 Duplicate column name，可忽略。文件风格对齐同目录既有的 `2026-W10-add-score-review.sql`。
4. **修 `fullClosureChainEndToEnd:182`** 的断言（按上文写法）。
5. **重跑 `PostExamClosureIntegrationTest` → 必须 8/8 全绿**（`Failures: 0, Errors: 0`）。**必须贴出实际结果行**。
6. **覆盖率核对**（JaCoCo）：`MakeupService` ≥70%、`AbsenceService` ≥85%、`ScoreReviewController` ≥80%；未达到就在回报里写实际值与原因，**不为了数字写假测试**。
7. **全量回归**：`mvn test` 全绿（测试只增不减）。基线 = 你自己开工前跑一次记下的数字。

---

## 必须遵守的项目硬约定（违反任何一条都算未完成）

1. **集成测试禁止用 `@Sql` 自建表**。建表只以 `schema.sql` 为唯一来源。任务结束后 `grep -rn '@Sql' src/test` **必须为空**（现在已经是空的，别破坏）。
2. **不得为了让测试变绿而放宽断言、注释用例、加 `@Disabled`**。第 5 步那处修正是有取证的事实性纠错，除此之外任何断言强度的削弱都算未完成。
3. **不新增依赖**（`pom.xml` 不动）；**不改** `AbsenceService` / `MakeupService` / `ScoreReviewService` 的业务逻辑；**不改** `MakeupScoreService`（属单独立项）；**不改** `application-test.yml`；**不改** `application.yml`（尤其不要动 `default-property-inclusion: non_null`——那是全项目序列化口径）。
4. **不改 `ExamService.forceEnd` 与 `ClassManagementIntegrationTest`**（已完成且已验证）。
5. **`@Transactional(rollbackFor = Exception.class)`** 是本项目统一写法。
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
2. **改动清单**：每个文件一行，说明改了什么（关键方法名 / 新增/删除的行）。
3. **`ScoreReview.java` 回退确认**：贴出 `git diff` 该文件的**实际输出**（期望是"回到原样"，即该文件不再出现在 `git status` 的修改列表里）。
4. **`schema.sql` 的 diff**：只允许 `score_review` 一段变化，贴出实际 diff 证明其他 23 张表没被碰。
5. **修复前后对照**：`PostExamClosureIntegrationTest` 修复前的 `8/0/1` → 修复后的实际结果行。
6. **同类陷阱自查**：你在该测试文件里逐一检查了哪些 `get("...")`，有没有发现同款 null 省略问题，结论是什么。
7. **覆盖率**：三个类的实测指令/行覆盖，与目标的差距。
8. **收尾数字**：全量 `tests run / failures / errors` + 新增用例数。
9. **`grep -rn '@Sql' src/test` 的实际输出**（应为空）。
10. **逐条对照 `tasks.json`**：每个 step 是"已完成"还是"未完成/替代做法"，未完成的**必须说明原因**，不要勾满。
11. **意外发现**：任何与上述描述不符的事实（包括你发现我写的断言/描述其实是错的），如实写。
12. **不要自称"已验证"**：只能报告你实际跑过的命令与输出数字。

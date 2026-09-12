# newLab 后端源码分析 —— 面向 examOnline 的经验与参考代码块

> **源项目**：`D:\code\newLab\back\newlab-back`（广东工业大学实验教学管理平台，Spring Boot 3.5.5 + Java 17）
> **目标项目**：`D:\code\examOnline`（在线考试系统，当前为空目录）
> **分析日期**：2026-09-10
>
> newLab 已沉淀一套完整的"课堂小测 / 限时答题 / 题库 / 自动判分"链路，与在线考试系统核心域高度重合。本文档按"可直接迁移 → 需要裁剪 → 仅供参考"三档整理，所有引用均指向源文件路径，便于对照查看。

---

## 目录

1. [技术栈与工程骨架](#一技术栈与工程骨架可直接迁移)
2. [鉴权与角色体系](#二鉴权与角色体系可直接迁移)
3. [考试核心域模型](#三考试核心域模型可直接照抄并裁剪)
4. [考试流程关键代码](#四考试流程关键代码可直接迁移)
5. [答案契约（最容易踩坑）](#五答案契约最容易踩坑的地方强烈建议照抄)
6. [题库导入导出（EasyExcel）](#六题库导入导出easyexcel-模板)
7. [签到 / 二维码（考场入场）](#七签到--二维码考场入场可用)
8. [成绩加权汇总](#八成绩加权汇总多题型多模块考试)
9. [其他工程细节](#九其他工程细节值得借鉴)
10. [需要警惕的缺陷](#十需要警惕的缺陷examonline-应改进)
11. [examOnline 起步建议](#十一examonline-起步建议基于-newlab-的最小可行架构)
12. [最值得直接复制的 10 个文件](#十二最值得直接复制的-10-个文件按优先级)

---

## 一、技术栈与工程骨架（可直接迁移）

**来源**：`pom.xml`、`src/main/resources/application.yml`

| 类别 | 组件 | 对考试系统的价值 |
|---|---|---|
| 语言/框架 | Java 17 + Spring Boot 3.5.5（web / validation / aop / actuator / thymeleaf） | 主流稳定，Jakarta Validation 做参数校验 |
| ORM | `mybatis-plus-ext-spring-boot3-starter 3.5.9-EXT727` + `auto-table-spring-boot-starter 2.5.0` | **注解即 DDL**，实体上加 `@AutoTable/@Table/@Column` 自动建表，考试系统初期迭代极快 |
| 认证 | `jjwt 0.11.5` + `jbcrypt 0.4`（未引入 Spring Security） | 轻量、可控；考试系统角色简单，无需 Spring Security 全套 |
| 文件/Excel | `easyexcel 3.1.2` + `poi 5.2.4` | **题库批量导入、成绩单导出** 可直接照搬 |
| 二维码 | `zxing 3.5.2` | 考场入场码 / 试卷码 |
| 视频 | `javacv-platform 1.5.10` | 若考试含"看视频答题"可用（否则可裁） |
| 环境变量 | `java-dotenv 5.2.2` + `local-config.env` | 敏感信息（JWT secret / DB 密码）外置，避免入库 |

### MyBatis-Plus 关键约定

```yaml
mybatis-plus:
  global-config:
    db-config:
      logic-delete-field: isDeleted
      logic-delete-value: 1
      logic-not-delete-value: 0
```

→ 所有实体带 `isDeleted` 字段，删除走软删；考试系统尤其需要（历史试卷不可物理删）。

---

## 二、鉴权与角色体系（可直接迁移）

### 2.1 JWT 工具类

**来源**：`util/JwtUtil.java`

**亮点**：
- Token 仅承载 `username + role`（claims 极简，敏感信息不落 token）；
- 提供 `generateLongTermToken(days)` 用于"记住我"；
- `parseToken` 一次性返回 username/role/isExpired，便于调试接口。

**考试系统建议扩展**：在 claims 加一个 `examId`，把 token 与当前考试绑定，防止跨考试重放。

### 2.2 BCrypt 密码

**来源**：`util/PasswordUtil.java`

```java
public String encode(String rawPassword) {
    return BCrypt.hashpw(rawPassword, BCrypt.gensalt());
}
public boolean matches(String raw, String encoded) {
    return BCrypt.checkpw(raw, encoded);
}
```

`isValidPassword` 强制 6–16 位 + 字母数字，可直接作为考试系统的密码策略。

### 2.3 拦截器 + ThreadLocal（替代 Spring Security）

**来源**：`interceptor/AuthenticationInterceptor.java` + `util/SecurityUtil.java` + `config/WebMvcInterceptorConfig.java`

**链路**：

```
请求 → AuthenticationInterceptor.preHandle
     → 白名单放行 / Bearer Token 解析
     → SecurityUtil.setRoleAO(new RoleAO(username, role))  // ThreadLocal
     → afterCompletion 清理 ThreadLocal（防内存泄漏）
```

**白名单写法**支持 `*`（单级）与 `**`（多级）通配，考试系统需要的 `/api/auth/login`、`/api/public/exam-status/**`、`/actuator/**` 都能覆盖。

### 2.4 层级角色注解 + AOP（强烈推荐）

**来源**：`annotation/RequireRole.java` + `aspect/RoleValidationAspect.java` + `enums/UserRole.java`

```java
public enum UserRole {
    ADMIN("admin", "管理员", 3),
    TEACHER("teacher", "教师", 2),
    STUDENT("student", "学生", 1);

    public boolean hasPermission(UserRole required) {
        return this.level >= required.level;
    }
}
```

**用法**：

```java
@RequireRole(UserRole.TEACHER)   // 类级：整个 Controller 都需教师
@RestController
public class ExamController {
    @RequireRole(UserRole.ADMIN) // 方法级覆盖类级
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) { ... }
}
```

切面逻辑：取注解中所有要求角色的**最低 level**，用户 level ≥ 它即放行。考试系统典型映射：`ADMIN > TEACHER(出卷/判分) > STUDENT(答题)`。

### 2.5 统一响应 / 分页 / 异常

**来源**：`pojo/response/ApiResponse.java`、`PageResponse.java`、`exception/BusinessException.java`、`GlobalExceptionHandler.java`、`enums/ResponseCode.java`

- `ApiResponse<T>{code, message, data}` + 静态工厂 `success/error`；
- `PageResponse.of(current, size, total, records)` 自动算 `pages`；
- `ResponseCode` 枚举把 HTTP 状态与业务码（1001/2001/3003…）合并。

**考试系统可扩展业务码**：

```java
EXAM_NOT_STARTED(6001, "考试未开始"),
EXAM_ENDED(6002, "考试已结束"),
ALREADY_SUBMITTED(6003, "已提交，不可重复交卷"),
EXAM_TIMEOUT(6004, "答题超时"),
PAPER_NOT_FOUND(6005, "试卷不存在"),
QUESTION_BANK_EMPTY(6006, "题库无匹配题目");
```

- `GlobalExceptionHandler` 覆盖 `MethodArgumentNotValidException`、`DataIntegrityViolationException`（Duplicate entry → "数据已存在"）、`DateTimeParseException`（返回具体错误值），生产可用。

---

## 三、考试核心域模型（可直接照抄并裁剪）

newLab 的"课堂小测 + 限时答题"就是**一场小型在线考试**，实体设计非常贴近 examOnline 需求。

### 3.1 考试场次（≈ Exam）

**来源**：`pojo/entity/ClassroomQuiz.java`

```java
@AutoTable
@Table(value = "classroom_quiz", comment = "课堂小测表")
public class ClassroomQuiz {
    private Long id;
    private Long classExperimentId;   // → examOnline: courseId / examPaperId
    private Long procedureTopicId;    // → examOnline: 试卷配置ID（题目从哪来）
    private String quizTitle, quizDescription;
    private Integer quizTimeLimit;    // 分钟
    private Integer status;           // 0-未开始 1-进行中 2-已结束
    private LocalDateTime startTime, endTime, createdTime;
    private String createdBy;
}
```

**状态机**清晰：`0 → 1（startQuiz）→ 2（endQuiz）`；教师端 `startQuiz` 会先 `stopOngoingQuiz` 停掉同课次进行中的小测，避免并发冲突——考试系统需要类似"同一课程同时只允许一场进行中考试"的约束。

### 3.2 试卷/题目配置（≈ Paper）

**来源**：`pojo/entity/ProcedureTopic.java`、`TimedQuizProcedure.java`

```java
public class ProcedureTopic {
    private Boolean isRandom;         // 是否随机抽题
    private Integer number;           // 抽几道
    private String tags;              // "id1,id2,id3" 逗号分隔
    private String topicTypes;        // "1,2,3" 单选/多选/判断
    private Boolean tagMatchAll;      // true=必须命中所有标签 false=分组匹配
}
```

**两种出卷模式**（考试系统直接复用）：
- **固定卷**：教师手选题目 → `ProcedureTopicMap` 映射表保存顺序；
- **随机卷**：按标签+题型+数量抽题 → 每个学生看到的题目可以不同（防作弊）。

### 3.3 题目（≈ Question）

**来源**：`pojo/entity/Topic.java`

```java
public class Topic {
    private Long paperId;             // 归属试卷（可空=题库通用题）
    private Integer number;           // 题号
    private Integer type;             // 1单选 2多选 3判断 4填空 6其他
    private String content;           // 题干
    private String choices;           // JSON: {"A":"...","B":"..."}
    private String correctAnswer;     // 单选"A" / 多选"A-B-C" / 判断"T/F"
    private Boolean isDeleted;
    private String createdBy;
}
```

**choices 用 JSON 存**而非拆表，读写都简单；`correctAnswer` 有严格的契约（见下文 §5）。

### 3.4 标签（分类抽题的关键）

**来源**：`pojo/entity/Tag.java`、`TopicTagMap.java`、`enums/TagType.java`

```java
public enum TagType {
    SUBJECT("1","学科"),
    DIFFICULTY("2","难度"),
    QUESTION_TYPE("3","题型"),
    CUSTOM("4","自定义");
}
```

`TopicTagMap` 多对多，带 `uk_topic_tag(topicId,tagId)` 唯一索引，抽题时按标签过滤。考试系统典型标签：`章节1..N`、`简单/中等/困难`、`期末/期中/模拟`。

### 3.5 答题记录（≈ Submission）

**来源**：`pojo/entity/ClassroomQuizAnswer.java`

```java
public class ClassroomQuizAnswer {
    private Long classroomQuizId;
    private String studentUsername;
    private String classCode;
    private String answer;            // JSON: {"type":"TOPIC","data":{"topicId":"answer"}}
    private BigDecimal score;         // decimal(5,2)
    private Boolean isCorrect;        // 是否全对
    private LocalDateTime submissionTime;
}
```

**answer 用一列 TEXT 存整张答卷的 JSON**，而不是每题一行——写入原子、读取一次到位，考试系统强烈推荐（避免交卷时多行事务）。

---

## 四、考试流程关键代码（可直接迁移）

### 4.1 教师创建考试

**来源**：`service/impl/TeacherClassroomQuizServiceImpl.createClassroomQuiz`

**要点**：
1. 先落 `ProcedureTopic`（试卷配置），再落 `ClassroomQuiz`（场次），用 `procedureTopicId` 关联；
2. **随机模式**：校验 `topicNumber > 0`、`tags` 非空；
3. **固定模式**：校验 `teacherSelectedTopicIds` 都存在（`selectCount` 比对数量）；
4. 事务：`@Transactional(rollbackFor = Exception.class)`。

### 4.2 开始/结束考试（状态机）

**来源**：`service/impl/TeacherClassroomQuizServiceImpl.startQuiz / endQuiz`

```java
public void startQuiz(Long quizId) {
    ClassroomQuiz quiz = mapper.selectById(quizId);
    if (quiz.getStatus() != 0) throw new BusinessException(400, "小测状态不正确");
    stopOngoingQuiz(quiz.getClassExperimentId());   // 互斥：同课次只允许一场进行中
    quiz.setStatus(1);
    quiz.setStartTime(LocalDateTime.now());
    if (quiz.getQuizTimeLimit() != null && quiz.getQuizTimeLimit() > 0)
        quiz.setEndTime(quiz.getStartTime().plusMinutes(quiz.getQuizTimeLimit()));
    mapper.updateById(quiz);
}
```

考试系统可直接复用"互斥启动"模式（同一班级/课程同时只能有一场进行中的考试）。

### 4.3 学生答题提交（核心链路）

**来源**：`service/impl/StudentClassroomQuizServiceImpl.submitAnswer`

**校验清单**（考试系统直接照抄）：
1. 考试存在；
2. 考试属于声称的课次；
3. 状态 == 1（进行中）；
4. 当前时间 < endTime；
5. **该学生未提交过**（防重复交卷）；
6. 学生属于该考试关联的班级之一；
7. **固定卷模式下**，提交的 topicIds 必须与试卷题目集合完全相等（`validateSubmittedTopicIds`，防漏答/多答/伪造题号）；
8. 答案格式归一化（`TopicAnswerContractUtil.normalizeAnswerMapForWrite`）。

**判分 + 落库**（原子）：

```java
BigDecimal score = scorer.calculateScore(normalizedAnswers, topics);
Boolean allCorrect = scorer.isAllCorrect(normalizedAnswers, topics);
String answerJson = AnswerMapJSONUntil.toTopicJson(normalizedAnswers);
// insert ClassroomQuizAnswer
```

### 4.4 随机抽题（防作弊核心）

**来源**：`service/impl/StudentClassroomQuizServiceImpl.getTopicsForQuiz`

```java
topicWrapper.last("ORDER BY RAND() LIMIT " + procedureTopic.getNumber());
return topicMapper.selectList(topicWrapper);
```

**注意**：这是"学生侧"抽题，每个学生进考试时各自抽一次 → **不同学生题目不同**，天然防作弊。教师侧 `getTopicsForQuiz` 用固定顺序（`orderByAsc(number)`）以便统计。

`ORDER BY RAND()` 在题库 < 1 万条时性能可接受；若题库巨大，建议改为"先查 ID 列表 → 内存 shuffle → 取前 N"，或用 `FLOOR(RAND()*count)` 偏移。

### 4.5 限时考试"无状态密钥"（强烈推荐）

**来源**：`util/TimedQuizKeyGenerator.java` + `util/CryptoUtil.java`

**痛点**：限时考试需要记录"开始时间"以计算剩余时间。常规做法是在 DB 加一行 `student_quiz_session`，但 newLab 用**加密 token 把状态甩给客户端**，服务端零存储。

```java
// 学生点"开始考试"时，服务端返回：
String rawData = studentUsername + "|" + System.currentTimeMillis() + "|" + randomCode;
String encryptedKey = cryptoUtil.encrypt(rawData);   // AES/ECB/PKCS5Padding + Base64Url

// 学生交卷时，把 encryptedKey 带回来：
KeyValidationResult r = generator.validateKey(encryptedKey, studentUsername, quizTimeLimit);
// r.valid / r.remainingTime / r.message
```

**校验规则**：
- 解密后 username 必须匹配（防冒名）；
- `elapsed > timeLimit + 5min 缓冲` → 超时；
- 返回剩余毫秒数。

考试系统可直接复用这个模式（`examId|studentId|startTs|nonce` AES 加密），**省掉一张 session 表**。

### 4.6 自动评分

**来源**：`util/ClassroomQuizScorer.java`

```java
public BigDecimal calculateScore(Map<Long,String> answers, List<Topic> topics) {
    int correct = 0;
    for (Topic t : topics)
        if (TopicAnswerContractUtil.answersEqual(t.getType(),
                answers.get(t.getId()), t.getCorrectAnswer()))
            correct++;
    return new BigDecimal(correct).multiply(new BigDecimal(100))
            .divide(new BigDecimal(topics.size()), 2, RoundingMode.HALF_UP);
}
```

**按题数等权给分**（每题 100/N 分）。若考试系统需要"单选 2 分、多选 3 分、判断 1 分"，把 `Topic` 加一个 `score` 字段，公式改成 `Σ(题目分 × 是否正确) / Σ(题目分) × 100`。

### 4.7 教师统计（考后分析）

**来源**：`service/impl/TeacherClassroomQuizServiceImpl.getQuizStatistics`

**输出**：
- 总参与人数 / 已提交人数 / 完成率；
- 平均分 / 全对率；
- **每题统计**：正确数、错误数、答题人数、正确率（按答题人数降序，方便看"哪些题最难"）；
- 学生列表（带姓名、班级、得分、是否全对、提交时间）。

考试系统"考后分析页"几乎一模一样，可直接迁移。

---

## 五、答案契约（最容易踩坑的地方，强烈建议照抄）

**来源**：`util/TopicAnswerContractUtil.java` + `topic-answer-contract.md`

| 题型 | 学生提交 | 写库 | 返回前端 |
|---|---|---|---|
| 单选 | `"a"` / `"A"` | `"A"`（强制 `^[A-Z]$`） | `"A"` |
| 多选 | `"b-a-c"` / `"A-B-C"` | `"A-B-C"`（字母序、去重、横杠分隔） | `"A-B-C"` |
| 判断 | `"正确"` / `"错误"` / `"T"` / `"F"` / `"A"` / `"B"` | `"T"` 或 `"F"` | `"正确"` / `"错误"` |
| 填空/简答 | 原文 | 原文（trim） | 原文 |

### 三个核心方法

- `normalizeForWrite(type, answer)`：入库前归一化，非法直接抛 `IllegalArgumentException`；
- `normalizeForApi(type, answer)`：出库返前端时把 `T/F` 翻成 `正确/错误`；
- `answersEqual(type, a, b)`：判分用，判断题走语义归一化后比较，其余 `trim().equals`。

### 校验选项存在性

`validateChoiceAnswer` 会解析 `Topic.choices` JSON，确认学生答案里的每个字母都在选项中——防止学生提交 `"Z"` 这种伪造选项。

> 考试系统若没有这层契约，很容易出现"同一个正确答案在 DB 里有 `A`/`a`/`正确`/`T` 四种写法"，判分全乱。

### 答案 JSON 统一封装

**来源**：`util/AnswerMapJSONUntil.java`

**存储结构**：

```json
{ "type": "TOPIC", "data": { "1001": "A", "1002": "A-B-C" } }
```

`type` 可取 `TOPIC`（普通答题）/ `TIMED_QUIZ`（限时）/ `DATA_COLLECTION`（填空表格）/ `VIEWED`（视频观看标记）。考试系统可加 `EXAM`、`HOMEWORK` 等类型，**同一张 submission 表支撑多种作答场景**。

工具类提供双向转换：
- `parseTopicData(json) → Map<Long,String>`
- `toTopicJson(map) → String`
- `buildTopicAnswerJson(List<TopicAnswerItem>)` 直接从前端 DTO 转 JSON

---

## 六、题库导入导出（EasyExcel 模板）

### 6.1 Excel 实体

**来源**：`pojo/excel/TopicImportExcel.java`

```java
@Data
@ContentRowHeight(25) @HeadRowHeight(30)
public class TopicImportExcel {
    @ExcelProperty(value="课程标签", index=0) @ColumnWidth(20) private String courseTag;
    @ExcelProperty(value="难度标签", index=1) @ColumnWidth(15) private String difficultyTag;
    @ExcelProperty(value="自定义标签", index=2) @ColumnWidth(20) private String customTag;
    @ExcelProperty(value="题目类型", index=3) @ColumnWidth(15) private String topicType;
    @ExcelProperty(value="题目内容", index=4) @ColumnWidth(40) private String content;
    @ExcelProperty(value="题目答案", index=5) @ColumnWidth(15) private String correctAnswer;
    @ExcelProperty(value="选项",    index=6) @ColumnWidth(60) private String choices;
}
```

### 6.2 导入监听器（批量 + 容错）

**来源**：`listener/TopicImportListener.java`

**亮点**：
- `BATCH_SIZE = 50`，每读 50 行落一次库，避免 OOM；
- **标签缓存** `Map<String, Long> tagCache`：同一标签只查/建一次 DB；
- `getOrCreateTag(name, type, desc)`：标签不存在则自动创建；
- **逐行 try-catch**：单行失败不影响其他行，错误消息带行号 `"第X行数据错误: ..."`；
- 返回 `ImportResult{successCount, failCount, errorMessages}`，前端可展示"成功 95 条、失败 5 条 + 错误明细"。

考试系统题库导入可直接照搬。

### 6.3 模板下载

**来源**：`controller/teacher/TeacherTopicController.downloadTemplate`

```java
response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
String fileName = URLEncoder.encode("题目导入模板", StandardCharsets.UTF_8)
                            .replaceAll("\\+", "%20");
response.setHeader("Content-disposition", "attachment;filename*=utf-8''" + fileName + ".xlsx");
// 写 4 条示例数据（单选/多选/判断×2）
EasyExcel.write(response.getOutputStream(), TopicImportExcel.class)
         .sheet("题目导入").doWrite(demoData);
```

**中文文件名编码** + `filename*=utf-8''` 是关键，避免浏览器乱码。

### 6.4 成绩导出

**来源**：`service/DataExportService.java`

`CourseGradeExportExcel`、`AttendanceRecordExportExcel` 两个 DTO + `exportCourseGrades(courseId, semester)` / `exportAttendanceRecords(courseId, startDate, endDate)`。考试系统可仿制 `ExamScoreExportExcel`。

---

## 七、签到 / 二维码（考场入场可用）

**来源**：`util/QrCodeUtil.java`、`service/QrService.java`、`pojo/entity/AttendanceRecord.java`

### 7.1 二维码生成（Base64 直接返前端）

```java
public String generateQrCodeBase64(String content, int w, int h) {
    BitMatrix m = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, w, h, hints);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    MatrixToImageWriter.writeToStream(m, "PNG", out);
    return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
}
```

### 7.2 二维码内容编码（带时效）

```java
// content = courseId|teacherCode|classCode|timestamp|randomCode  → Base64
public boolean isQrCodeValid(Long timestamp, int validSeconds) {
    long diff = System.currentTimeMillis()/1000 - timestamp;
    return diff >= 0 && diff <= validSeconds;
}
```

考试系统可用：教师端展示"入场二维码"（10 秒刷新），学生扫码 → 后端校验时效 → 写入 `AttendanceRecord`（带 IP）。`AttendanceRecord` 有 `uk_course_student(courseId, studentUsername, experimentId)` 唯一索引防重复签到。

### 7.3 签到状态枚举

**来源**：`enums/AttendanceStatus.java`

```java
NORMAL(1), LATE(3), MAKEUP(2), CROSS_CLASS(4);
```

考试系统可扩展 `ABSENT(5)`、`CHEATING(6)`。

---

## 八、成绩加权汇总（多题型/多模块考试）

**来源**：`util/ScoreCalculationUtil.java`、`service/GradeCalculationService.java`

- **实验总分** = `Σ(步骤得分 × 步骤占比 / 100)`，**任一占比步骤未完成/未批改 → 总分直接为 0**（强制完整性）。
- **课程总分** = `Σ(实验分 × 实验占比 / 100)`。

**考试系统映射**：
- "步骤" → 考试的"大题"（听力/阅读/写作）；
- "占比" → 大题权重；
- "未批改 → 0 分" 策略适合"必须全部批改完才出成绩"的场景；
- 若允许"客观题先出分"，可改成"未批改部分按 0 计入，但标记 `pending=true`"。

`BigDecimal` 全程 `HALF_UP` 保留 4 位，上限 100——**金额/分数计算的标准姿势**，避免 double 精度丢失。

---

## 九、其他工程细节（值得借鉴）

### 9.1 RequestId 过滤器（链路追踪）

**来源**：`filter/RequestIdFilter.java`

```java
String requestId = UUID.randomUUID().toString().substring(0, 8);
MDC.put("requestId", requestId);
response.setHeader("X-Request-Id", requestId);
```

配合 logback pattern `%X{requestId}` 即可全链路追踪。考试系统排障必备。

### 9.2 HTTP 日志拦截器

**来源**：`interceptor/HttpLoggingInterceptor.java`

- `preHandle` 记开始时间；
- `afterCompletion` 算耗时 + 记 status；
- `getRemoteAddr` 兼容 `X-Forwarded-For` / `X-Real-IP`（反代后取真实 IP，考试系统防作弊要记 IP）。

### 9.3 Jackson 时间反序列化（UTC+8 兜底）

**来源**：`config/JacksonConfig.java`

- 无时区时间按 `Asia/Shanghai` 解析；
- 带时区（`Z` / `+08:00`）统一换算为 UTC+8 后存 `LocalDateTime`；
- 兼容 `"2026-9-1 8:00"` 这种非标准格式（自动补零）。

考试系统时间敏感（开考/交卷时间），这个配置能省掉大量时区 bug。

### 9.4 AdminInitializer（启动时自动建管理员）

**来源**：`config/AdminInitializer.java`

```java
if (userMapper.selectOne(eq(username,"admin")) == null) {
    admin.setPassword(passwordUtil.encode("admin123"));
    userMapper.insert(admin);
    log.warn("请尽快修改默认管理员密码！");
}
```

考试系统首次部署时自动建 `admin/admin123`，避免"装完系统登不进去"。

### 9.5 DirectoryInitializer（启动建上传目录）

**来源**：`config/DirectoryInitializer.java`

`CommandLineRunner.run()` 里 `Files.createDirectories`，考试系统若有"上传附件/答题卡扫描件"需求可直接复用。

---

## 十、需要警惕的缺陷（examOnline 应改进）

**来源**：`docs/security-fix-plan.md` + HANDOVER §3.2

1. **缺资源归属校验**：教师接口按 ID 直接查改，未验证"该课程/班级是否属于当前教师"。考试系统必须在 Service 层加 `assertTeacherOwnsExam(examId, currentUsername)`，否则 A 老师能改 B 老师的试卷。
2. **`ORDER BY RAND()` 性能**：题库大时全表扫描 + filesort。建议改成"先 `SELECT id` 再内存 shuffle"。
3. **CryptoUtil 用 AES/ECB**：ECB 模式同明文 → 同密文，不安全。考试系统的"限时密钥"建议改 `AES/GCM/NoPadding` + 随机 IV。
4. **JWT 无刷新机制**：过期即 401，考试中途 token 过期会丢答卷。建议加 refresh token，或把 token 有效期设为"考试最长时长 + 缓冲"。
5. **CORS `allowedOriginPatterns("*")` + `allowCredentials(false)`**：开发期方便，生产必须收紧到具体域名。
6. **`ClassroomQuizAnswer` 无 `examId + studentUsername` 唯一索引**：靠代码 `selectOne` 判重，并发下可能重复插入。考试系统必须加 DB 唯一约束兜底。

---

## 十一、examOnline 起步建议（基于 newLab 的最小可行架构）

```
exam-online-back/
├── pom.xml                     # 照抄 newLab，去掉 javacv/thymeleaf/wechat
├── src/main/resources/
│   ├── application.yml         # 照抄，改 jwt.secret / db 名
│   └── local-config.env        # 环境变量
└── src/main/java/com/exam/
    ├── annotation/RequireRole.java                 # 照抄
    ├── aspect/RoleValidationAspect.java            # 照抄
    ├── interceptor/AuthenticationInterceptor.java  # 照抄，改白名单
    ├── config/{MybatisPlus,WebMvc,Jackson,AdminInitializer}.java  # 照抄
    ├── enums/{UserRole,ResponseCode,ExamStatus,QuestionType}.java # 仿制
    ├── exception/{BusinessException,GlobalExceptionHandler}.java  # 照抄
    ├── pojo/
    │   ├── entity/{User,Exam,Paper,Question,Tag,QuestionTagMap,
    │   │           ExamSubmission,ExamAnswer}.java
    │   ├── request/{CreateExamRequest,SubmitExamRequest,...}.java
    │   ├── response/{ApiResponse,PageResponse,ExamDetailResponse,...}.java
    │   └── excel/{QuestionImportExcel,ScoreExportExcel}.java
    ├── mapper/                  # 全部 extends BaseMapper
    ├── service/
    │   ├── AuthService.java     # 仿 newLab
    │   ├── ExamService.java     # 创建/开始/结束考试（仿 ClassroomQuiz）
    │   ├── PaperService.java    # 组卷（固定/随机，仿 ProcedureTopic）
    │   ├── QuestionService.java # 题库 CRUD + 标签
    │   ├── SubmissionService.java # 交卷 + 自动判分
    │   └── StatisticsService.java # 考后统计（仿 getQuizStatistics）
    ├── util/
    │   ├── JwtUtil.java         # 照抄
    │   ├── PasswordUtil.java    # 照抄
    │   ├── SecurityUtil.java    # 照抄
    │   ├── CryptoUtil.java      # 照抄（建议改 GCM）
    │   ├── ExamKeyGenerator.java # 仿 TimedQuizKeyGenerator（无状态限时）
    │   ├── QuestionAnswerContractUtil.java # 照抄 TopicAnswerContractUtil
    │   ├── AnswerJsonUtil.java  # 照抄 AnswerMapJSONUntil
    │   └── ExamScorer.java      # 照抄 ClassroomQuizScorer（可扩展按题分加权）
    ├── listener/QuestionImportListener.java   # 照抄 TopicImportListener
    └── controller/
        ├── AuthController.java
        ├── admin/AdminController.java
        ├── teacher/{Exam,Paper,Question,Statistics}Controller.java
        └── student/{Exam,Submission}Controller.java
```

### 第一周可跑通的最小闭环

1. 用户登录（JWT + BCrypt）；
2. 教师建题库（手输 + Excel 导入）；
3. 教师组卷（固定选题）；
4. 教师创建考试（设时间窗）；
5. 学生进入考试 → 拿题（不含正确答案）；
6. 学生交卷 → 自动判分 → 落库；
7. 教师看统计（完成率/平均分/每题正确率）。

### 第二周增量

- 随机抽题 + 标签匹配；
- 限时密钥（无状态防作弊）；
- 考后答案可见性控制；
- 成绩 Excel 导出；
- 二维码入场签到。

---

## 十二、最值得直接复制的 10 个文件（按优先级）

| # | 文件 | 价值 |
|---|---|---|
| 1 | `util/TopicAnswerContractUtil.java` | 答案归一化契约，**判分正确性的基石** |
| 2 | `util/AnswerMapJSONUntil.java` | 答卷 JSON 编解码，一列存整张卷 |
| 3 | `util/TimedQuizKeyGenerator.java` | 无状态限时密钥，省一张 session 表 |
| 4 | `interceptor/AuthenticationInterceptor.java` + `util/SecurityUtil.java` | JWT 拦截 + ThreadLocal 用户上下文 |
| 5 | `aspect/RoleValidationAspect.java` + `annotation/RequireRole.java` + `enums/UserRole.java` | 层级角色注解，一行 `@RequireRole(TEACHER)` 搞定权限 |
| 6 | `exception/GlobalExceptionHandler.java` + `BusinessException.java` + `enums/ResponseCode.java` | 全局异常 + 业务码枚举 |
| 7 | `pojo/response/ApiResponse.java` + `PageResponse.java` | 统一响应壳 |
| 8 | `service/impl/StudentClassroomQuizServiceImpl.java` | 学生交卷完整链路（校验→归一化→判分→落库） |
| 9 | `service/impl/TeacherClassroomQuizServiceImpl.java` | 教师创建/开始/结束/统计完整链路 |
| 10 | `listener/TopicImportListener.java` + `pojo/excel/TopicImportExcel.java` | 题库 Excel 批量导入（带标签缓存、行级容错） |

---

## 总结

newLab 的"课堂小测"模块本质上就是一套**已上线验证过的轻量在线考试系统**，其核心价值在于：

1. **答案契约统一**（`TopicAnswerContractUtil`）—— 解决判分一致性的根本问题；
2. **无状态限时密钥**（`TimedQuizKeyGenerator`）—— 优雅地避免 session 表；
3. **层级角色 AOP**（`@RequireRole` + `UserRole.level`）—— 比 Spring Security 轻 10 倍；
4. **随机抽题 + 标签匹配**（`TopicTagMatchService` 分组/全部两种语义）—— 天然防作弊；
5. **EasyExcel 批量导入**（`TopicImportListener` 带标签缓存 + 行级容错）—— 题库冷启动神器；
6. **AutoTable 注解建表**—— 实体即 DDL，前期迭代飞快；
7. **统一响应/异常/分页**—— 工程规范一步到位。

examOnline 直接以 newLab 为骨架起步，**第一周即可跑通"建题库→组卷→考试→交卷→判分→统计"闭环**，后续再按考试场景特有需求（防切屏、人脸识别、答题卡扫描等）增量扩展即可。

---

## 附录：关键源文件路径索引

| 模块 | 文件路径（相对 `D:\code\newLab\back\newlab-back\src\main\java\com\example\demo\`） |
|---|---|
| 应用入口 | `Demo1Application.java` |
| 鉴权拦截器 | `interceptor/AuthenticationInterceptor.java` |
| 角色切面 | `aspect/RoleValidationAspect.java`、`annotation/RequireRole.java` |
| 当前用户 | `util/SecurityUtil.java`（ThreadLocal） |
| 成绩计算 | `util/ScoreCalculationUtil.java`、`service/GradeCalculationService.java` |
| 答案契约 | `util/TopicAnswerContractUtil.java`、`util/AnswerMapJSONUntil.java` |
| 标签抽题 | `service/TopicTagMatchService.java` |
| 限时密钥 | `util/TimedQuizKeyGenerator.java`、`util/CryptoUtil.java` |
| 课堂小测评分 | `util/ClassroomQuizScorer.java` |
| 全局异常 | `exception/GlobalExceptionHandler.java`、`exception/BusinessException.java` |
| 统一响应 | `pojo/response/ApiResponse.java`、`PageResponse.java` |
| 教师小测服务 | `service/impl/TeacherClassroomQuizServiceImpl.java` |
| 学生小测服务 | `service/impl/StudentClassroomQuizServiceImpl.java` |
| 题库导入 | `listener/TopicImportListener.java`、`pojo/excel/TopicImportExcel.java` |
| 数据导出 | `service/DataExportService.java` |
| 二维码 | `util/QrCodeUtil.java`、`service/QrService.java` |
| 实体目录 | `pojo/entity/`（23 个实体，均带 `@AutoTable`） |

> 本文档基于 newLab 后端源码静态分析整理，所有代码片段均为节选或简化，完整实现请对照源文件。

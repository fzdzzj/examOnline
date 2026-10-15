# 变更提案：add-student-learning-analytics

## 为什么（Why）

当前系统已建立单场考试的数据分析报告（`ExamAnalysisReport`：班级概览、逐题难度区分度、单场知识点薄弱项）与基于历史答卷的错题本重练功能（`StudentPracticeService`）。但在成熟在线考试系统的演进路线上（`docs/examOnline需求规格说明书.md` §9.2「学情分析」、§9.3「个性化推荐」与 Phase 3 P2），仍存在两项关键能力空白：

1. **学生缺乏跨考试学情画像与知识点掌握度视图**：
   - 目前学生只能查看单场考试的成绩单，无法感知自己在整个学习周期中的学业走势（历史均分、最高/最低分、及格率波动趋势）；
   - 知识点（Tag）维度仅停留在教师端单场考试分析中，学生端无法获知自己在各个知识点上的长期累计掌握度（总分值 vs 实际得分率），难以定位系统性的薄弱短板。

2. **错题重练缺乏题库级的靶向练习推荐**：
   - 现有的错题重练池仅局限于做错过的题目（`practice_attempt`），一旦学生答对消灭后便无题可练；
   - 无法基于学生历史最薄弱的知识点，从题库中主动发掘和推荐同类知识点的机判客观题（单选/多选/判断/填空）进行靶向加固。

3. **教师缺乏班级维度的跨考试综合学情**：
   - 教师目前只能逐场查看考试分析报告，缺乏班级整体的历史平均分走势与全班跨考试 Top 5 薄弱知识点汇总。

本案落实产品愿景 Phase 3 P2 学情分析与个性化推荐要求，在**零 DDL** 前提下，基于既有答卷、题目与标签数据做内存聚合，构建跨考试学情画像与薄弱点靶向推荐闭环。

---

## 改什么（What）

### 1. 后端学情分析与推荐服务
- **落点包**：`com.exam.score.service` 与 `com.exam.score.dto`；
- **核心数据对象**：
  - `StudentLearningAnalyticsResponse`：
    - `overview`：`totalExams`（参测场次）、`averageScore`（平均分）、`highestScore`（最高分）、`lowestScore`（最低分）、`passRate`（及格率）；
    - `trend`：按考试时间排序的历史考试趋势列表（`examId`, `examTitle`, `examTime`, `studentScore`, `totalScore`, `scoreRate`）；
    - `tagMastery`：跨考试知识点掌握度列表（`tagId`, `tagName`, `questionCount`, `totalAvailableScore`, `studentAchievedScore`, `masteryRate`, `isWeak`）；
    - `weakTags`：按掌握度升序排列的 Top 5 薄弱知识点。
  - `RecommendedPracticeItem`：
    - `questionId`, `stem`, `type`, `score`, `difficulty`, `tagId`, `tagName`, `tagMasteryRate`, `recommendReason`。
  - `ClassLearningAnalyticsResponse`：
    - `classId`, `className`, `studentCount`, `examCount`；
    - `trend`：班级各场考试平均分走势；
    - `tagMastery`：班级在各知识点上的平均得分率；
    - `weakTags`：班级 Top 5 薄弱知识点。
- **服务类 `StudentLearningAnalyticsService`**：
  - `getStudentAnalytics(Long studentId)`：聚合该学生所有已发布考试（`status >= STATUS_PUBLISHED`）答卷数据与 Tag 关联，计算学情总览、趋势与掌握度；空数据优雅返回空态对象；
  - `getRecommendedPractice(Long studentId, Integer limit)`：定位学生最薄弱知识点，从题库（`status=1`, `is_deleted=0`, 机判客观题型）匹配推荐题目，排除已消灭题目，默认 limit 5（上限 10）；
  - `getClassAnalytics(Long teacherId, Long classId)`：校验教师班级归属，聚合班级跨考试平均分与薄弱点。
- **控制器端点（`LearningAnalyticsController` 或 `ScoreController` 扩展）**：
  - `GET /api/scores/my/analytics`：学生查询个人跨考试学情画像；
  - `GET /api/scores/my/recommendations?limit=5`：学生查询薄弱点靶向推荐练习；
  - `GET /api/scores/classes/{classId}/analytics`：教师查询班级跨考试综合学情。

### 2. 前端学情分析与推荐交互
- **学生端**：
  - 在 `frontend/src/pages/(dashboard)/student/scores/index.page.vue` 新增 Tab：「我的考试成绩」与「学情画像与靶向练习」；
  - 呈现：学情总览 Statistic 卡片、成绩趋势可视化、知识点掌握度进度条（红/黄/绿区分）、薄弱点推荐练习卡片（含推荐原因与一键练习展开）；
  - 健壮性：三态分离（加载态、暂无数据空态引导、API 异常显性 Alert 报错）。
- **教师端**：
  - 在 `frontend/src/pages/(dashboard)/teacher/classes/index.page.vue` 班级列表操作列增加「学情分析」入口，Drawer/Modal 展示班级跨考试均分走势与 Top 5 薄弱知识点。

### 3. API 契约与 SDK
- 重新导出 `openapi.yaml`（新增 3 个端点，paths 92 → 95）；
- 执行 `pnpm run gen:api` 同步生成前端 SDK。

---

## 影响面（Impact）
- **数据库**：**零 DDL**，基于既有 `exams`, `exam_submissions`, `questions`, `tags`, `question_tags`, `practice_attempt`, `classes` 等表进行聚合计算，`schema.sql` 与 `migrations/` 零触碰；
- **向后兼容**：既有单场考试分析与错题练习端点行为不变，新增独立端点向后兼容；
- **性能与隔离**：单学生仅聚合其本人已发布考卷；教师端严格做班级所有权隔离校验，避免水平越权。

---

## 时间线与任务
- **阶段 0 & 1**：立项三件套与工作流登记；
- **阶段 2**：实施后端学情分析服务、端点暴露、前端交互组件与契约同步、先红后绿与变异击红留证；
- **阶段 3**：全量门禁实测、基线上调（paths 92→95）、规范合入与收口归档。

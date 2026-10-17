# Proposal: 学生题目个人收藏夹 (add-student-question-favorites)

## 1. 为什么（Why）

在当前的练习与考试体系中，系统已具备被动归集的错题本（`student_wrong_questions`，由交卷判分自动沉淀）。但在实际学习场景中：
1. 学生经常做对具有代表性、启发性解法或经典考点的题目，希望主动收藏以备考前复习；
2. 缺乏主动收藏入口导致优质题库资源无法个性化沉淀为学生的专属知识库；
3. 本特性为成熟在线考试系统的标配功能（愿景 §3 学习闭环），弥补题库与自主学习间的交互断层。

---

## 2. 改什么（What）

### 2.1 数据模型（DDL 与实体）
- 新建表 `student_question_favorites`：
  ```sql
  CREATE TABLE IF NOT EXISTS student_question_favorites (
      id           BIGINT   NOT NULL AUTO_INCREMENT,
      student_id   BIGINT   NOT NULL,
      question_id  BIGINT   NOT NULL,
      created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
      PRIMARY KEY (id),
      UNIQUE KEY uk_stu_question (student_id, question_id),
      KEY idx_stu_time (student_id, id)
  );
  ```
- 更新 `src/main/resources/schema.sql`；
- 编写增量迁移脚本 `docker/mysql/migrations/2026-W42-add-student-question-favorites.sql`；
- 登记 `docker/mysql/migrations/APPLIED.md`。

### 2.2 后端服务与端点 (`com.exam.question`)
- 新增实体 `StudentQuestionFavorite`、Mapper `StudentQuestionFavoriteMapper`；
- 新增服务 `StudentQuestionFavoriteService`：
  - `favorite(Long questionId)`：幂等收藏，校验题目有效且未删除（不存在则抛 404 `QUESTION_NOT_FOUND`）；
  - `unfavorite(Long questionId)`：幂等取消收藏；
  - `listFavorites(int page, int size, Integer type, String keyword)`：分页查询本人收藏题目（按收藏时间倒序），联查题目基础数据；
  - `checkFavorites(List<Long> questionIds)`：批量检测题目是否已由当前学生收藏（返回已收藏 ID 集合）；
- 新增控制器 `StudentQuestionFavoriteController`：
  - `POST /api/student/favorites/{questionId}`
  - `DELETE /api/student/favorites/{questionId}`
  - `GET /api/student/favorites`
  - `GET /api/student/favorites/check`
- 权限与安全：端点限制 `STUDENT` 角色或通过 `@PreAuthorize` 鉴权，严格按 `SecurityUtil.getUserId()` 隔离。

### 2.3 API 契约与基线
- 更新 `openapi.yaml`，新增 4 个端点（paths 由 97 增至 101）；
- 同步上调 `.github/ci/gate-baseline.json` 中的 `openapiPaths`（97 $\to$ 101）及 `backendTests`。

### 2.4 前端实现 (`frontend`)
- 学生端新增「我的收藏」路由与页面组件（支持题型筛选、关键字搜索、展开解析、一键取消收藏）；
- 在考后答卷回顾（`ExamReview`）与题目卡片（`QuestionAnswerCard`）中增加收藏切换图标（⭐），自动批量查询回显收藏状态；
- 补充前端单元测试。

---

## 3. 变异验证计划

- **m1（题目不存在校验短路）**：移除 `favorite` 中的题目有效性检查，验证向不存在的 questionId 收藏时断言失败（应 404 但返回 200）；
- **m2（学生数据隔离移除）**：在查询收藏列表或批量检测时忽略 `student_id` 过滤，验证跨学生隔离单元/集成测试确定性变红。

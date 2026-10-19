# 提案：简答题双评盲评与分差仲裁工作流（add-subjective-double-blind-grading）

> **变更 ID**：`add-subjective-double-blind-grading`  
> **能力域**：`grading`（主观题批改）  
> **级别**：M 级大考级评卷与业务创新特性（双评盲评 + 分差仲裁 + 匿名脱敏 + 终分回填）  
> **前置依赖**：`add-student-wrong-question-mastery`（已合入 main）  
> **影响面**：`SubjectiveGradingService`、`GradingController`、`schema.sql`、`docker/mysql/migrations/`、`openapi.yaml`、前端主观题批改面板

---

## 1. 为什么做（Why）

- **业务痛点**：
  1. **单评主观随意性大**：当前主观题批改仅由单一教师打分评语，主观题存在打分尺度不一、个人偏见等问题，在高严肃性考试（如期末统考、选拔考、模拟高考/考研）场景下无法满足严肃考试公信力要求；
  2. **缺乏盲评保护**：当前批改界面直接展示考生姓名、且多名教师批改时能直接看见他人打分，无法防范人情分与打分趋同；
  3. **缺乏分差仲裁争议化解机制**：大考标准流程中，当两位教师独立打分分差超出允许容差（如超过 2.0 分）时，必须进入三评/组长终审仲裁，当前系统完全缺失仲裁队列与流转闭环。
- **治理目标**：
  全面引入高考/考研大考级标准「背靠背匿名双评盲评 + 分差自动裁决 + 超差仲裁工作流」机制，实现主观题公平可信评卷。

---

## 2. 改什么（What）

### 2.1 数据模型（DDL 与实体）
- 新建表 `subjective_blind_reviews`：
  ```sql
  CREATE TABLE IF NOT EXISTS subjective_blind_reviews (
      id            BIGINT        NOT NULL AUTO_INCREMENT,
      exam_id       BIGINT        NOT NULL,
      submission_id BIGINT        NOT NULL,
      question_id   BIGINT        NOT NULL,
      round_type    VARCHAR(16)   NOT NULL, -- 'FIRST', 'SECOND', 'ARBITRATION'
      grader_id     BIGINT        NOT NULL,
      score         DECIMAL(5,1)  NOT NULL,
      comment       VARCHAR(1024)          DEFAULT NULL,
      created_time  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
      updated_time  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
      PRIMARY KEY (id),
      CONSTRAINT uk_blind_review UNIQUE (submission_id, question_id, round_type),
      KEY idx_blind_exam_grader (exam_id, grader_id),
      KEY idx_blind_sub_q (submission_id, question_id)
  );
  ```
- 更新 `src/main/resources/schema.sql`（H2/MySQL 双兼容，`AUTO_INCREMENT` 块写 `PRIMARY KEY (id)`）；
- 新增迁移脚本 `docker/mysql/migrations/2026-W42-add-subjective-blind-reviews.sql` 并登记 `docker/mysql/migrations/APPLIED.md`；
- 新增实体 `SubjectiveBlindReview` 与 Mapper `SubjectiveBlindReviewMapper`。

### 2.2 核心业务逻辑与流程
1. **背靠背盲评与打码脱敏**：
   - 教师 A 提交一评（`FIRST`），记录评分与评语；
   - 教师 B 提交二评（`SECOND`），严格防刷：一评与二评教师不能为同一人（`grader_id != teacherA`，违规抛 400）；
   - 盲评视图打码脱敏：盲评工作台查询时不透漏考生姓名与另一评教师评分。
2. **分差裁决机制**：
   - 容差阈值 `tolerance`（缺省 2.0 分，可按题目/考试配置）；
   - 若 $|Score_1 - Score_2| \le \text{tolerance}$：分差在允许范围内，系统自动裁决终分 $Score_{final} = \text{round}((Score_1 + Score_2) / 2, 1)$；终分自动回填至 `subjective_grades.score`，刷新答卷主观总分；
   - 若 $|Score_1 - Score_2| > \text{tolerance}$：分差超标，状态流转为 `ARBITRATION_PENDING`，`subjective_grades.score` 保持 NULL（待仲裁）。
3. **仲裁工作流**：
   - 仲裁员（Arbitrator，教研组长/主考教师）查询分差超标题目清单，对比一评、二评分数与评语；
   - 仲裁员提交仲裁分（`ARBITRATION`），终分以仲裁分为准，回填 `subjective_grades.score` 并结案。

### 2.3 接口契约与端点
- `POST /api/exams/{examId}/grading/subjective/blind-submit`：提交盲评打分（一评/二评自动分流与裁决）；
- `GET /api/exams/{examId}/grading/subjective/arbitration-list`：查询待仲裁分差超标题目清单；
- `POST /api/exams/{examId}/grading/subjective/arbitrate`：仲裁员提交裁决终分；
- `openapi.yaml` 补充 3 个端点（paths 104 $\to$ 107）及对应 DTO schemas。

### 2.4 前端增强
- 主观题批改工作台增加“双评盲评”与“待仲裁”工作流面板；
- 盲评视角对考生身份打码脱敏（“考生 ***”），隐藏他人评阅信息；
- 仲裁视角并排展示一评、二评分数与评语对比卡片，提供终审仲裁打分控件。

---

## 3. 约束与硬红线（Guardrails）

- **AGENTS.md 硬约定**：
  - 建表唯一入口为 `src/main/resources/schema.sql`；
  - 集成测试严禁用 `@Sql` 自建表；
  - 实体字段与建表定义必须双向一致；
  - 存量脚本入库 `docker/mysql/migrations/` 并同步执行台账 `APPLIED.md`。
- **评卷安全与防舞弊**：
  - 二评教师严禁与一评教师为同一人；
  - 盲评打分未裁决前，绝不可向另一位教师暴露分数或评语；
- **双端门禁与变异**：
  - TDD 先红后绿，双端门禁全绿，`gate-baseline.json` 同步上调；
  - 三变异验证（m1 抽除同教师互斥校验 / m2 抽除分差超标流转仲裁 / m3 抽除仲裁分回填终分）必须全部断言级击红并回绿留证。

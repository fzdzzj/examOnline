# score-review 规范

> 能力域：成绩复核（阶段 9，W11）。
> 来源：`spec/changes/archive/add-class-and-post-exam-closure` 合入（成绩复核申请、复核中隐藏成绩、复核处理）。
> 实施注记：`score_review` 以 `uk_review_exam_student(exam_id, student_id)` 保证「一场一学生限 1 次」的幂等；`status` 0 待处理 / 1 处理中 / 2 已同意 / 3 已驳回，其中 0/1 视为「进行中」，命中隐藏成绩逻辑；idx_review_exam_status 支撑教师按考试/状态查复核清单。

## Requirements

### Requirement: 成绩复核申请

WHEN 学生质疑已发布成绩,

系统 SHALL 支持提交复核申请，每场考试 SHALL 限申请 1 次，且 SHALL 在成绩发布后 7 天内。

#### Scenario: 限次申请

GIVEN 学生已提交过一次复核申请

WHEN 学生再次申请同一考试复核

THEN 拒绝重复申请

#### Scenario: 限时申请

GIVEN 成绩发布已超过 7 天

WHEN 学生申请复核

THEN 拒绝申请

#### Scenario: 未参加考试或未完成批改的学生拒绝申请复核 (harden-core-security-and-state-boundaries)

GIVEN 学生调用复核申请接口（`POST /api/exams/{examId}/score-reviews`）
WHEN 该学生在本次考试中无有效答卷、未参加考试或答卷尚未处于已批改（STATUS_GRADED）且总分非空状态
THEN 系统 SHALL 拒绝该操作，返回 HTTP 400 Bad Request，错误信息明确说明未参加本场考试或成绩尚未批改完成，无法申请复核

---

### Requirement: 复核中隐藏成绩

WHEN 学生有进行中的复核申请,

系统 SHALL 在学生端隐藏成绩并显示"复核中"，防止查看分数后再申请。

#### Scenario: 复核中隐藏

GIVEN 学生提交复核申请且尚未处理

WHEN 学生查看本人成绩

THEN 不显示分数

AND 显示"复核中"

---

### Requirement: 复核处理

WHEN 教师处理复核,

系统 SHALL 支持同意（重新批改/调整分数）或驳回，处理后 SHALL 更新学生端成绩显示。

#### Scenario: 同意调整分数

GIVEN 教师同意复核并调整分数

WHEN 完成处理

THEN 更新该学生成绩

AND 学生端显示新成绩

#### Scenario: 驳回恢复显示

GIVEN 教师驳回复核

WHEN 完成处理

THEN 恢复原成绩显示

#### Scenario: 复核调分终局操作不可逆警示防呆 (harden-review-flow-and-resource-limits)

WHEN 教师处理学生成绩复核申请并选择「同意」且填入「调整后总分」
THEN 界面 SHALL 显性展示不可逆操作 warning 警示提示，告知调分将直接覆写答卷最终得分
AND 提交操作 SHALL 具备知情防呆确认机制，杜绝教师手误直接提交覆写成绩

#### Scenario: 复核处理 CAS 原子更新与并发冲突防守 (harden-cross-cutting-edge-cases)

GIVEN 存在成绩复核申请
WHEN 教师处理成绩复核申请
THEN 系统 SHALL 通过状态条件 `status IN (STATUS_PENDING, STATUS_PROCESSING)` 进行原子 CAS 更新
AND 若因并发重复操作导致受影响行数为 0，系统 SHALL 抛出业务异常返回 HTTP 400 Bad Request 拒绝重复处理，防止并发下状态覆盖或重复通知


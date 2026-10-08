# exam-management spec-delta：考试列表服务端筛选（add-exam-list-filtering）

## ADDED Requirements

### Requirement: 考试列表服务端筛选与分页

系统 SHALL 提供教师考试列表分页与条件筛选能力（`GET /api/exams`），支持可选参数 `page`、`size`、`title`、`status`，响应维持 `ApiResponse<List<ExamResponse>>` 形状。系统 SHALL 维持教师所有权隔离（非管理员仅查本人考试）与稳定倒序排序（`ORDER BY id DESC`）。

#### Scenario: 缺省不传筛选参数返回全量分页
GIVEN 教师名下存在多场考试
WHEN 教师调用 `GET /api/exams`（未传 `title` 与 `status`）
THEN 系统返回该教师按 `id DESC` 排序的全量分页考试列表

#### Scenario: 按标题关键词模糊匹配
GIVEN 教师名下存在标题含"期中测试"与"期末考评"的考试
WHEN 教师调用 `GET /api/exams?title=期中`
THEN 仅返回标题包含"期中"的考试，且命中结果按 `id DESC` 排序

#### Scenario: 标题不匹配返回空列表
GIVEN 教师名下存在考试
WHEN 教师调用 `GET /api/exams?title=不存在的标题`
THEN 系统返回 200 且数据列表为空

#### Scenario: 纯空白标题视为不过滤
GIVEN 教师名下存在多场考试
WHEN 教师调用 `GET /api/exams?title=%20%20%20`（全为空格）
THEN 系统视为空白不过滤，返回该教师全量分页考试

#### Scenario: 按考试状态精确筛选
GIVEN 教师名下存在状态为未开始(0)与进行中(1)的考试
WHEN 教师调用 `GET /api/exams?status=0`
THEN 仅返回状态为未开始的考试

#### Scenario: 标题与状态组合筛选
GIVEN 教师名下存在标题含"数学"且状态为未开始(0)的考试，以及标题含"数学"但已结束(2)的考试
WHEN 教师调用 `GET /api/exams?title=数学&status=0`
THEN 仅返回同时满足标题包含"数学"且状态为未开始的考试

#### Scenario: 维持教师所有权隔离
GIVEN 教师 A 与教师 B 各自创建包含"物理"关键词的考试
WHEN 教师 A 调用 `GET /api/exams?title=物理`
THEN 仅返回教师 A 自己创建的物理考试，不包含教师 B 的考试

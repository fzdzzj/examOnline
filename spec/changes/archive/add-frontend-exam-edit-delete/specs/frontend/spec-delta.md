# frontend spec-delta：考试编辑与删除入口（add-frontend-exam-edit-delete）

## ADDED Requirements

### Requirement: 考试编辑与删除入口

教师考试列表 SHALL 为「未开始且未发布」的考试提供「编辑」入口（与「发布考试」同源乐观门控）、为「未发布」的考试提供「删除」入口（乐观放宽，最终裁决在后端）；编辑 SHALL 复用创建页（route query 携带 examId 时呈现「编辑考试」）并经详情端点回填、提交调用 `PUT /api/exams/{id}`，删除 SHALL 经知情确认弹窗（形态对齐「强制结束」）调用 `DELETE /api/exams/{id}`；操作失败 SHALL 原文呈现后端 message，SHALL NOT 本地编造失败文案或拦截请求；操作成功 SHALL 失效列表查询刷新（对齐发布/强制结束的既有做法）。

#### Scenario: 操作列乐观门控

GIVEN 列表行按后端返回的 status/published 渲染

WHEN 行为「未开始 + 未发布」

THEN 「编辑」与「删除」均出现

AND 行为「进行中 + 未发布」或「已结束 + 未发布」时仅「删除」出现（编辑不出现，删除乐观放宽）

AND 行为「已发布」时两者均不出现

#### Scenario: 编辑复用创建页并回填

GIVEN 教师点击「编辑」进入 create?examId=&lt;id&gt;

WHEN 页面加载

THEN 标题呈现「编辑考试」且详情端点返回值回填全部表单字段（含防作弊两开关）

AND 提交调用 `PUT /api/exams/{id}`（path 携带 examId）成功后跳回列表并失效 exams 查询

#### Scenario: 删除知情确认

GIVEN 教师点击「删除」

WHEN 确认弹窗呈现

THEN 弹窗明示删除不可逆（形态对齐「强制结束」弹窗）

AND 确认后调用 `DELETE /api/exams/{id}` 并失效 exams 查询

#### Scenario: 失败原文呈现不本地编造

GIVEN 后端拒绝编辑或删除（如非未开始考试的删除请求）

WHEN 操作失败

THEN message 呈现后端返回的原始 message，且不跳转、不失效查询

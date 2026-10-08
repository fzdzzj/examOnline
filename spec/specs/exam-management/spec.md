# exam-management 规范

> 能力域：考试管理（阶段 4，W3-W4）。
> 来源：`spec/changes/add-exam-management` 合入（考试创建、考试发布、考试状态机、教师提前结束、考试快照、试卷锁定）+ `spec/changes/archive/add-exam-list-filtering` 合入（考试列表服务端筛选与分页，2026-10-08）。

## Requirements

### Requirement: 考试创建

WHEN 教师创建考试,
系统 SHALL 支持绑定试卷、课程班级、设定时间窗与个人时长，并 SHALL 校验时间窗与时长合法性。

#### Scenario: 创建考试成功

GIVEN 已存在一份试卷

AND 教师设定合法的开始/结束时间与时长

WHEN 教师提交创建请求

THEN 系统创建考试

AND 初始状态为"未开始"

#### Scenario: 时间窗非法被拒绝

GIVEN 结束时间早于或等于开始时间

WHEN 教师提交创建请求

THEN 系统拒绝创建

AND 返回"时间窗非法"错误

#### Scenario: 时长非法被拒绝

GIVEN 个人时长小于等于 0

WHEN 教师提交创建请求

THEN 系统拒绝创建

AND 返回"时长非法"错误

---

### Requirement: 考试发布

WHEN 教师发布考试,
系统 SHALL 使考试对学生可见，并 SHALL 在到达开始时间后自动进入进行中。

#### Scenario: 定时发布

GIVEN 考试设定未来开始时间

WHEN 教师点击发布

THEN 考试对学生可见但状态仍为未开始

AND 到达开始时间后自动进入进行中

---

### Requirement: 考试状态机

WHEN 考试生命周期流转,
系统 SHALL 遵循 未开始→进行中→已结束→已批改→已发布，并 SHALL 以乐观锁保证并发安全。

#### Scenario: 标准流转

GIVEN 考试处于未开始

WHEN 到达开始时间

THEN 状态迁至进行中

WHEN 到达结束时间

THEN 状态迁至已结束

#### Scenario: 并发流转仅一次

GIVEN 两个请求同时迁移同一考试状态

WHEN 乐观锁 CAS 执行

THEN 仅一个请求影响行数为 1

AND 另一个请求影响 0 行并重试或报错

---

### Requirement: 教师提前结束

WHEN 教师提前结束考试,
系统 SHALL 将进行中的考试迁移到已结束，并 SHALL 记录提前结束标记。

#### Scenario: 提前结束

GIVEN 考试处于进行中

WHEN 教师执行提前结束

THEN 状态迁至已结束

AND 记录提前结束标记留待强制交卷处理

---

### Requirement: 考试快照

WHEN 考试被发布,
系统 SHALL 生成考试快照（含考试配置与试卷内容），之后答题、判分、回看 SHALL 以快照为准。

#### Scenario: 发布时生成快照

GIVEN 考试已绑定试卷

WHEN 考试发布

THEN 系统生成考试快照

AND 快照含完整试卷题目/答案/分值/顺序

#### Scenario: 试卷变更不影响快照

GIVEN 考试快照已生成

AND 之后试卷或题目被修改

WHEN 读取考试快照

THEN 快照内容不变

AND 历史考试不受影响

---

### Requirement: 试卷锁定

WHEN 考试进入进行中,
系统 SHALL 锁定其绑定的试卷，禁止修改、删除试卷与题目、禁止改动分值。

#### Scenario: 进行中锁定生效

GIVEN 考试处于进行中

WHEN 教师尝试修改其绑定试卷或题目

THEN 系统拒绝操作

AND 返回"考试进行中，试卷已锁定"错误

#### Scenario: 未开始不受锁定影响

GIVEN 考试处于未开始

WHEN 教师修改其绑定试卷

THEN 系统允许修改

---

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


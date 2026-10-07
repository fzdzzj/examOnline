# grading spec-delta：主观题批改行分页与筛选（add-subjective-grading-pagination）

## ADDED Requirements

### Requirement: 主观题批改行分页与筛选

批改工作台主观题行端点（`GET /api/exams/{examId}/grading/subjective`）SHALL 支持服务端分页与筛选参数（page/size/onlyUngraded/name/submissionId，全部可选），响应 SHALL 恒为分页信封 `{rows, total, graded}`；分页 SHALL 以稳定排序（按 student_id）保证翻页不丢行不错行；乐观锁批改协议（casSaveScore / expectedVersion / 409 冲突）SHALL 零改动，冲突回填 SHALL 改用 submissionId 单行取数。

#### Scenario: 分页取数

GIVEN 一场考试某主观题已有若干交卷学生行
WHEN 以 page 与 size 调用
THEN 返回该页行子集（按 student_id 稳定排序）与 total（总行数）、graded（已批行数），graded 与题级进度口径一致

#### Scenario: 缺省全量

WHEN 不传 page/size 调用
THEN 信封内 rows 为该题全部行（与既有全量行为等价，仅形状改信封）

#### Scenario: 筛选下沉

WHEN 以 onlyUngraded 或 name 调用
THEN 服务端按未批改 / 学生姓名包含过滤后返回信封（语义对齐既有面板客户端筛选）

#### Scenario: 冲突回填单行取数

GIVEN 教师提交批改收到 409/1012 冲突
WHEN 前端回填最新行
THEN 以 submissionId 参数单行取数（至多 1 行），不再全量拉取后查找；expectedVersion 协议与「拉最新行回填、教师重看重打」语义不变

#### Scenario: 非法参数与越权

GIVEN page<1 或 size 超上限，或非本场归属教师调用
WHEN 请求到达服务端
THEN 非法参数返回 400；越权按既有批改 403 口径拒绝

#### Scenario: 批改提交协议不变

GIVEN 分页取数上线
WHEN 教师逐行提交批改（saveSubjectiveScore）
THEN 校验链、打回重批语义、读己之写口径与分页前完全一致

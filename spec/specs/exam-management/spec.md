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

#### Scenario: 预约开关仅在主考创建链暴露

GIVEN 教师创建或编辑普通考试

WHEN 设定「需预约」开关（`reservationRequired`）

THEN 开关随建考/编辑请求持久化，默认关闭；默认关闭下既有考试与既有行为零变化

AND 补考创建链（`MakeupCreateRequest`）不暴露该开关——补考白名单制与预约制正交，补考不适用预约

#### Scenario: 预约开关修改窗口与考试状态绑定

GIVEN 「需预约」考试已推进为进行中或更晚状态

WHEN 教师请求修改该考试的 `reservationRequired`

THEN 服务端拒绝修改（开关仅未开始状态可改），开考后预约名单与开关一并锁定

AND 考试其余字段的编辑语义维持既有约束零变化

#### Scenario: 作答时长不能大于考试时间窗跨度

WHEN 教师创建或更新考试时传入的 `durationMinutes` 大于由 `startTime` 与 `endTime` 计算的时间窗总分钟数

THEN 系统 SHALL 拒绝该操作，返回 HTTP 400，错误信息明确说明作答时长不能大于考试时间窗跨度（由 harden-exam-paper-and-practice-boundaries 合入）

#### Scenario: 迟到容忍时长不能大于或等于作答时长

WHEN 教师创建或更新考试时传入的 `allowLateMinutes` 大于或等于 `durationMinutes`

THEN 系统 SHALL 拒绝该操作，返回 HTTP 400，错误信息明确说明迟到容忍分钟数不能大于或等于个人作答时长（由 harden-exam-paper-and-practice-boundaries 合入）

---

### Requirement: 防作弊配置语法校验

WHEN 教师创建或更新考试时提交 `antiCheatConfig`,
系统 SHALL 对其中的 IP 白名单配置进行合法性前置校验。

#### Scenario: 建考与改考防作弊 IP 白名单格式校验

WHEN 教师创建或更新考试时提交的 `antiCheatConfig` 中包含 `ipWhitelist`

THEN 系统 SHALL 逐项校验白名单中的字符串是否符合合法 IP（单 IPv4/IPv6、合法通配符、有效 CIDR）

AND 若开启了 `enableIpWhitelist=true` 但 `ipWhitelist` 为空，系统 SHALL 返回 HTTP 400 提示白名单不能为空

AND 若存在任一项非法 IP，系统 SHALL 拒绝该请求，返回 HTTP 400 并明确指出非法项（由 harden-exam-paper-and-practice-boundaries 合入）

---

### Requirement: 考试预约前置门禁

WHEN 学生调用考试预约相关端点,
系统 SHALL 校验目标考试是否为预约制考试。

#### Scenario: 非预约制考试拒绝预约与取消预约

WHEN 学生对 `reservationRequired != 1` 的普通考试调用预约端点 `POST /api/exam-reservations/{id}` 或取消端点 `DELETE /api/exam-reservations/{id}`

THEN 系统 SHALL 拒绝该操作，返回 HTTP 400，错误提示明确说明该考试非预约制考试（由 harden-exam-paper-and-practice-boundaries 合入）

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

#### Scenario: 状态机流转与成绩发布撤回主动失效详情缓存 (harden-core-security-and-state-boundaries)

WHEN 考试状态机定时扫表推进考试状态（未开始→进行中、进行中→已结束），或教师/管理员执行成绩发布（`publish`）与撤回（`revoke`）使考试状态发生改变
THEN 系统 SHALL 主动清空 `examDetail` 缓存（`CACHE_EXAM_DETAIL`），确保后续查询立即读取到最新数据库状态，杜绝长达 5 分钟的过期缓存脏读（由 harden-core-security-and-state-boundaries 合入）

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

---

### Requirement: 考试发布

> 合入注记（2026-10-15，变更 `add-exam-lifecycle-notifications` 收口）：
> 教师发布考试时，系统旁路为该考试绑定班级的所有学生投递【考试发布通知】未读站内消息，通知异常不影响主考务发布。
>
> 合入注记（2026-10-16，变更 `optimize-backend-batch-write-paths` 收口）：
> 投递写入方式升级为批量 INSERT（每批 ≤500 行，显式全列对齐 schema.sql）；旁路降级、收件人、内容、触发时机不变。同口径约束 `notifyScorePublished`（成绩发布通知）；`notifyScoreReviewHandled`（单收件人）保持单条写入。

#### Scenario: 考试发布向关联班级学生旁路推送通知 (ADDED)
- **WHEN**: 教师发布一场已绑定班级的考试（`POST /api/exams/{id}/publish`，状态迁为未开始且 published=1）
- **THEN**: 系统 SHALL 旁路获取该班级的全部学生 ID（`user_class` 关联列表）
- **AND**: 系统 SHALL 为每位班级学生生成一条站内消息（title="考试发布通知"，content 包含试卷标题与开考时间，examId 为该考试 ID，read_flag=0）
- **AND**: 多收件人投递 SHALL 以批量 INSERT 写入（每批 ≤500 行），不得循环逐条 insert；批量写入 SHALL 使用显式全列 INSERT（列清单与 `schema.sql` 的 `user_messages` 建表定义逐列一致，不依赖 MyBatis-Plus 字段推导）
- **AND**: 消息生成过程中的任何异常 SHALL 被旁路捕获并降级记录，绝不影响或回滚考试发布事务（任一批写入失败同此降级）
- **AND**: 投递日志条数口径 SHALL 为学生数（非批数）


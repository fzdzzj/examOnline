# 变更提案：optimize-backend-batch-write-paths

## 为什么（Why）

后端全局只读巡查（2026-10-16，五维度：性能/事务/异常/并发/边界）甄别出两处**尚未治理的批量读写热点**，均集中在站内消息投递路径；其余候选经源码直读甄别为误报（已治理或有意的部分成功语义），不重复立项：

1. **考务生命周期通知循环逐条 insert**：
   - `ExamLifecycleNotificationService.notifyExamPublished` / `notifyScorePublished` 在 `for (studentIds)` 循环内逐条 `messageMapper.insert`（L63-66、L86-89）——大班（数百学生）考试发布时产生数百条独立 INSERT，逐条网络往返放大写延迟；
   - 该路径运行在考试发布/成绩发布**主考务事务内**（旁路降级只兜异常，不改变「同步写在事务里」的事实），条数越多主事务被拖得越长。

2. **开考提醒扫描 N+1**：
   - `ReservationRemindService.remind()` 外层 `for (Exam : exams)` 循环内逐场 `reservationMapper.selectList`（L74-77）——候选考试越多查询次数线性放大，每 10 秒一轮的定时扫描在预约考试密集期反复叠加。

两处均为纯性能治理，**对外行为语义零变化**（通知内容/收件人/触发时机/旁路降级/行级幂等全部不变）。

---

## 改什么（What）

### 1. 通知批量插入（ExamLifecycleNotificationService + UserMessageMapper）
- `UserMessageMapper` 新增 `insertBatch(@Param("messages") List<UserMessage>)`：`@Insert` + `<script><foreach>` 显式全列批量 INSERT——**列清单以 `schema.sql` 的 `user_messages` 建表定义为准逐列写明**（不依赖 MyBatis-Plus 字段推导，规避实体-表漂移面）；沿用本 Mapper 既有显式注解 SQL 惯例（`markReadByOwner`/`markAllByOwner` 先例）；
- 投递循环改为组装 `List<UserMessage>` 后**分批调 insertBatch（每批 ≤500 行）**——对齐 `harden-review-flow-and-resource-limits` 的 500 分片先例，防单语句超 `max_allowed_packet`；
- **硬约束保全**：旁路降级 try-catch 语义逐字不变——任一批失败即整体 warn 降级返回，绝不阻断考试发布/成绩发布/复核处理主事务；
- `notifyScoreReviewHandled`（单条投递）保持现状不动；日志 `投递 N 条` 计数口径不变（N=学生数，非批数）。

### 2. 提醒候选批查（ReservationRemindService）
- `remind()` 中「逐场 selectList 未提醒预约行」改为**一次 `IN (examIds)` + `reminded_time IS NULL` 批量查询**，结果按 examId 内存分组后走原有逐行投递循环；
- **硬约束保全**：`deliverIfAbsent` 行级幂等链路**逐字不动**——`markRemindedIfNull` 单行原子 claim（影响行数=1 才写消息）是防重复投递的核心，claim 逐行天然限定写入量，无需批写；
- `@Transactional` 边界、@Scheduled 扫描间隔、启动补偿扫表均不变。

---

## 影响面（Impact）
- **数据库与契约**：严格零 DDL（schema.sql 与 docker/mysql/migrations 零 diff）、零契约变更（openapi paths 97 不变，无新端点）、零前端；
- **门禁基线**：仅后端 surefire 用例数增长（收口笔上调 backendTests）；
- **既有不变式保全**：通知旁路降级不阻断主考务事务；开考提醒行级幂等（重复扫描/重启/多实例竞争均不重发）；消息内容与收件人零变化；
- **分级**：M 级（消息投递路径行为面虽不变，但写入方式变更触及主考务事务内的批量写），双岗隔离。

---

## 时间线与任务
- **阶段 0 & 1**：立项三件套编制与在途总账登记；
- **阶段 2**：实施两处优化 + 后端单测先红后绿与变异击红留证；
- **阶段 3**：全量双端门禁实测、gate-baseline 上调（仅 backendTests）、规范合入与收口归档、`--no-ff` 合并入 main。

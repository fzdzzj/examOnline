# 变更提案：add-exam-lifecycle-notifications

## 为什么（Why）

在线考试系统的核心价值之一在于考务流程的即时闭环与考务状态透明度。当前系统虽然在 `add-exam-reservation` 中建立了站内消息中心（`user_messages` 表与前端消息中心挂件），但目前消息投递仅用于预约制考试的开考前提醒单一场合：
1. **考试发布零通知**：教师发布考试后，参考班级的学生无法主动获悉考试已发布与开考时间，必须手动刷新考试列表才能发现；
2. **成绩发布零通知**：教师完成判分汇总并发布成绩后，学生无法即时得知成绩已出，导致信息不对称；
3. **复核结果零通知**：学生提交成绩复核申请后，教师进行调分或驳回处理，系统未向申诉学生推送复核结论通知，学生需反复进入成绩界面核对状态；
4. **消息中心缺乏「全部已读」**：随着考务消息增多，学生只能单条点击「标为已读」，缺乏一键清空未读消息的常用能力。

本案落实产品愿景说明书（`docs/examOnline需求规格说明书.md` §2.6「消息通知」与成熟在线考试产品差距），在零 DDL 前提下完成考务生命周期关键节点的消息通知闭环与消息中心体验增强。

## 改什么（What）

### 1. 服务层与旁路降级设计
- **新增 `ExamLifecycleNotificationService`**（落点 `com.exam.message.service`）：
  - `notifyExamPublished(Exam exam)`：向考试关联班级（`exam.getClassId()`）的全体学生（`classService.listStudentIds(exam.getClassId())`）投递【考试发布通知】（`title="考试发布通知"`，包含试卷标题、开考时间与考试 ID）；
  - `notifyScorePublished(Exam exam, List<Long> studentIds)`：向该场考试所有实考考生投递【成绩发布通知】（`title="成绩发布通知"`，包含试卷标题、查分提示与考试 ID）；
  - `notifyScoreReviewHandled(ScoreReview review, Exam exam, String action, BigDecimal adjustedScore)`：向复核申诉学生投递【成绩复核结果通知】（`title="成绩复核结果通知"`，包含处理结论已同意/已驳回、调分分值与考试 ID）；
  - **旁路降级保护**：所有通知方法内部统一 `try-catch` 捕获异常并记录 `warn` 日志，确保即使通知插入失败也绝不回滚或阻断主考务事务（发布/成绩发布/复核处理）。

### 2. 消息中心端点扩展
- **`MessageService`**：
  - 新增 `int markAllRead()` 方法，将当前登录用户的所有未读消息（`user_id = :userId AND read_flag = 0`）批量标记为已读（`read_flag = 1`），返回标记行数；
- **`MessageController`**：
  - 新增 `PUT /api/messages/read-all` 端点，权限要求仅登录即可（继承类级与拦截器安全边界）。

### 3. 主考务链路接线
- **`ExamService.publish`**：考试发布成功后，旁路触发 `notifyExamPublished`；
- **`ScoreService.doPublishOne`**：单场成绩发布 CAS 状态成功后，旁路触发 `notifyScorePublished`；
- **`ScoreReviewService.handle`**：复核处理完成（同意/驳回）后，旁路触发 `notifyScoreReviewHandled`。

### 4. 前端交互增强
- **`MessageCenter.vue`**：
  - 在弹层头部新增「全部已读」操作按钮（当 `unread > 0` 时显性展示）；
  - 点击触发 `markAllRead` API，成功后自动联动清空未读数角标并刷新消息列表为已读态。

### 5. API 契约与 SDK
- 重新导出 `openapi.yaml`（新增 `PUT /api/messages/read-all`，paths 91 → 92）；
- 执行 `pnpm run gen:api` 同步更新前端 SDK。

## 影响面（Impact）
- **数据库**：零 DDL，`user_messages` 表已有 `user_id`, `exam_id`, `title`, `content`, `read_flag`, `created_time` 字段及 `(user_id, id)` 索引，完全满足需求；
- **接口兼容性**：既有 `/api/messages`、`/api/messages/unread-count`、`/api/messages/{id}/read` 行为逐字不变，新增端点向后兼容；
- **考务稳定性**：通知生成为旁路调用，异常静默捕获，主考务状态流转与事务零污染。

## 时间线与任务
- 阶段 0 & 1：立项与三件套制定，更新待办清单与 README；
- 阶段 2：实施后端通知服务与主链路接线、前端全部已读入口与契约同步、先红后绿与变异验证；
- 阶段 3：全量门禁实测、基线上调（paths 91→92）、规范合入与收口归档。

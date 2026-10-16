# 规范增量：exam-management

## MODIFIED Scenario: 考试发布向关联班级学生旁路推送通知

原 Scenario（基线「考试发布」Requirement）语义保全声明：通知触发时机、收件人范围（考试关联班级全体学生）、消息内容与标题、旁路降级硬约定全部不变。本笔仅修改投递写入方式：

- **WHEN**: 考试发布触发站内通知投递
- **THEN**: 投递 SHALL 以批量 INSERT 写入（每批 ≤500 行），不得循环逐条 insert
- **AND**: 批量写入 SHALL 使用显式全列 INSERT（列清单与 `schema.sql` 的 `user_messages` 建表定义逐列一致，不依赖 MyBatis-Plus 字段推导）
- **AND**: 任一批写入失败时旁路降级 SHALL 不变——记录 warn 日志后静默返回，绝不阻断考试发布主事务
- **AND**: 投递日志条数口径 SHALL 为学生数（非批数）

**同笔同口径**：`notifyScorePublished`（成绩发布通知，多收件人路径）与考试发布通知同属 `ExamLifecycleNotificationService` 批量投递，SHALL 遵循同一批量写入约束；`notifyScoreReviewHandled`（单收件人）保持单条写入不动。

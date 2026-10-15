# spec-delta: exam-management (harden-core-security-and-state-boundaries)

### Requirement: 考试详情缓存与状态一致性

#### Scenario: 状态机流转与成绩发布撤回主动失效考试详情缓存 (ADDED)
- **WHEN**: 考试状态机定时扫表推进考试状态（未开始→进行中、进行中→已结束），或教师/管理员执行成绩发布（`publish`）与撤回（`revoke`）使考试状态发生改变
- **THEN**: 系统 SHALL 主动清空 `examDetail` 缓存（`CACHE_EXAM_DETAIL`），确保后续查询立即读取到最新数据库状态，杜绝长达 5 分钟的过期缓存脏读

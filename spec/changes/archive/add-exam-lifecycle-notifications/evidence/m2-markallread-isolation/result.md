# 变异验证 m2：移除 markAllRead 用户隔离 → 击红留证

## 变异点
`UserMessageMapper#markAllByOwner` 的 SQL 去掉 `user_id = #{userId}` 过滤条件，改为
`UPDATE user_messages SET read_flag = 1 WHERE read_flag = 0`（破坏 user_id 水平隔离）。

## 命令
```
./mvnw.cmd test -Dtest=ExamLifecycleNotificationIntegrationTest#markAllReadZeroesOwnUnreadAndRespectsUserIsolation
```

## 红态（注入后）
```
MVN_EXIT=1
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0 <<< FAILURE!
[INFO] BUILD FAILURE

org.opentest4j.AssertionFailedError:
  // A 的全部已读不得影响 B 的未读数
  expected: <2> but was: <0>
```
击红断言：`assertEquals(2, unread-count of B, "A 的全部已读不得影响 B 的未读数")` ——
去掉 user_id 过滤后，A 执行全部已读会把 B 的未读也一并置 1，B 未读数由 2 变成 0，
用户隔离用例变红，证明 user_id 隔离被用例守住。

**复原**：恢复 `WHERE user_id = #{userId} AND read_flag = 0`，用例回绿（见根测试全绿留证）。

参考：实现见 `UserMessageMapper#markAllByOwner` 与 `MessageService#markAllRead`（按当前登录用户
user_id 过滤的批量置位）。
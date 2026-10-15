# 变异验证 m1：移除考试发布通知接线 → 击红留证

## 变异点
`ExamService.publish` 中去掉 `lifecycleNotificationService.notifyExamPublished(exam)` 一行
（旁路通知接线被移除）。

## 命令
```
./mvnw.cmd test -Dtest=ExamLifecycleNotificationIntegrationTest#examPublishNotifiesClassStudents
```

## 红态（注入后）
```
MVN_EXIT=1
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0 <<< FAILURE!
[INFO] BUILD FAILURE

com.exam.message.ExamLifecycleNotificationIntegrationTest.examPublishNotifiesClassStudents <<< FAILURE!
org.opentest4j.AssertionFailedError: expected: <1> but was: <0>
```
击红断言：`assertEquals(1, messageCountFor(aId, examId, "考试发布通知"))` —— 移除接线后班级学生
不再收到「考试发布通知」，通知投递测试变红，证明该接线被用例守住。

**复原**：恢复 `notifyExamPublished(exam)` 调用，用例回绿（见根测试全绿留证）。

参考：完整实现见 `ExamService.java#publish`（旁路通知调用 + 内部 try-catch 降级注释）。
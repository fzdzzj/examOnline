# Mutation kill evidence (change harden-behavior-report-and-monitor, phase 2)

Judge test subset: BehaviorEventCollectServiceTest,BehaviorLogQueryServiceTest,BusinessMetricsTest,ExamTakingControllerRateLimitGuardTest,BehaviorEventCollectorTest (covers all five implementation points).
Each mutant follows inject -> KILL (red) -> snapshot restore -> RE-GREEN. Raw Maven logs: mut-<id>-<red|green>.log.
Equivalent mutants are registered at the end and are NOT force-killed.

## Mutant m1 -- report endpoint loses rate limit

Intent: Remove @RateLimit from the behavior-report endpoint; the reflection guard test must go red.

Injection (src/main/java/com/exam/taking/controller/ExamTakingController.java):
```diff
-    @RateLimit(qps = 200, capacity = 1000, key = "behavior-report")
+
```

KILL (red) -- .\mvnw.cmd -B test "-Dtest=BehaviorEventCollectServiceTest,BehaviorLogQueryServiceTest,BusinessMetricsTest,ExamTakingControllerRateLimitGuardTest,BehaviorEventCollectorTest", log evidence/mut-m1-red.log, exit=1:
```
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 5.015 s -- in com.exam.anticheat.collector.BehaviorEventCollectorTest
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.166 s -- in com.exam.anticheat.service.BehaviorEventCollectServiceTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 27.84 s -- in com.exam.anticheat.service.BehaviorLogQueryServiceTest
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.175 s -- in com.exam.monitoring.metrics.BusinessMetricsTest
[ERROR] Tests run: 3, Failures: 3, Errors: 0, Skipped: 0, Time elapsed: 0.027 s <<< FAILURE! -- in com.exam.taking.controller.ExamTakingControllerRateLimitGuardTest
[ERROR] Tests run: 35, Failures: 3, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
ExamTakingControllerRateLimitGuardTest.behaviorReportIsRateLimited:53->rateLimitOf:34 写端点 reportBehavior 必须声明 @RateLimit（裸写端点＝可无限刷写） ==> expected: not <null>
ExamTakingControllerRateLimitGuardTest.behaviorReportQpsSitsBetweenPullPaperAndSubmit:67->rateLimitOf:34 写端点 reportBehavior 必须声明 @RateLimit（裸写端点＝可无限刷写） ==> expected: not <null>
ExamTakingControllerRateLimitGuardTest.noRemainingUnprotectedPostEndpoint:93 @PostMapping 写端点必须全部受限流保护，裸写端点=reportBehavior ==> expected: <true> but was: <false>
```

RESTORE -- snapshot restore (exact pre-injection bytes; no git checkout): True

RE-GREEN -- same command, log evidence/mut-m1-green.log, exit=0:
```
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.647 s -- in com.exam.anticheat.collector.BehaviorEventCollectorTest
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.189 s -- in com.exam.anticheat.service.BehaviorEventCollectServiceTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 27.42 s -- in com.exam.anticheat.service.BehaviorLogQueryServiceTest
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.119 s -- in com.exam.monitoring.metrics.BusinessMetricsTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.005 s -- in com.exam.taking.controller.ExamTakingControllerRateLimitGuardTest
[INFO] Tests run: 35, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS

```
## Mutant m2 -- eventData size gate disabled

Intent: Remove the size gate at the collect() entry: oversized payloads would be persisted and counted, so the oversize cases must go red.

Injection (src/main/java/com/exam/anticheat/service/BehaviorEventCollectService.java):
```diff
-        requireEventDataWithinLimit(eventData);
+
```

KILL (red) -- .\mvnw.cmd -B test "-Dtest=BehaviorEventCollectServiceTest,BehaviorLogQueryServiceTest,BusinessMetricsTest,ExamTakingControllerRateLimitGuardTest,BehaviorEventCollectorTest", log evidence/mut-m2-red.log, exit=1:
```
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 5.414 s -- in com.exam.anticheat.collector.BehaviorEventCollectorTest
[ERROR] Tests run: 12, Failures: 3, Errors: 0, Skipped: 0, Time elapsed: 0.191 s <<< FAILURE! -- in com.exam.anticheat.service.BehaviorEventCollectServiceTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 25.50 s -- in com.exam.anticheat.service.BehaviorLogQueryServiceTest
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.142 s -- in com.exam.monitoring.metrics.BusinessMetricsTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.009 s -- in com.exam.taking.controller.ExamTakingControllerRateLimitGuardTest
[ERROR] Tests run: 35, Failures: 3, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
BehaviorEventCollectServiceTest.oversizeCheckPrecedesDispatchSoUnknownTypeAlsoRejected:162 Expected com.exam.common.BusinessException to be thrown, but nothing was thrown.
BehaviorEventCollectServiceTest.oversizedEventDataRejectedWithoutPersistNorMetric:145 超限明细必须整笔拒绝（非静默截断） ==> Expected com.exam.common.BusinessException to be thrown, but nothing was thrown.
BehaviorEventCollectServiceTest.sizeLimitCountsUtf8BytesNotCharacters:183 Expected com.exam.common.BusinessException to be thrown, but nothing was thrown.
```

RESTORE -- snapshot restore (exact pre-injection bytes; no git checkout): True

RE-GREEN -- same command, log evidence/mut-m2-green.log, exit=0:
```
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.137 s -- in com.exam.anticheat.collector.BehaviorEventCollectorTest
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.166 s -- in com.exam.anticheat.service.BehaviorEventCollectServiceTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 24.92 s -- in com.exam.anticheat.service.BehaviorLogQueryServiceTest
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.171 s -- in com.exam.monitoring.metrics.BusinessMetricsTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.010 s -- in com.exam.taking.controller.ExamTakingControllerRateLimitGuardTest
[INFO] Tests run: 35, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS

```
## Mutant m3 -- occurredTime anchoring disabled (skew branch neutralised)

Intent: Neutralise the skew computation: clients could poison timeline ordering with arbitrary past/future times, so the anchoring cases must go red while the within-tolerance cases stay green.

Injection (src/main/java/com/exam/anticheat/service/BehaviorEventCollectService.java):
```diff
-        Duration skew = Duration.between(now, occurredTime).abs();
-        if (skew.compareTo(MAX_CLOCK_SKEW) > 0) {
+        Duration skew = Duration.ZERO;
+        if (skew.compareTo(MAX_CLOCK_SKEW) > 0) {
```

KILL (red) -- .\mvnw.cmd -B test "-Dtest=BehaviorEventCollectServiceTest,BehaviorLogQueryServiceTest,BusinessMetricsTest,ExamTakingControllerRateLimitGuardTest,BehaviorEventCollectorTest", log evidence/mut-m3-red.log, exit=1:
```
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.099 s -- in com.exam.anticheat.collector.BehaviorEventCollectorTest
[ERROR] Tests run: 12, Failures: 3, Errors: 0, Skipped: 0, Time elapsed: 0.167 s <<< FAILURE! -- in com.exam.anticheat.service.BehaviorEventCollectServiceTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 23.81 s -- in com.exam.anticheat.service.BehaviorLogQueryServiceTest
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.199 s -- in com.exam.monitoring.metrics.BusinessMetricsTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.010 s -- in com.exam.taking.controller.ExamTakingControllerRateLimitGuardTest
[ERROR] Tests run: 35, Failures: 3, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
BehaviorEventCollectServiceTest.futureOccurredTimeAnchoredToServerNow:230->assertAnchoredToServerNow:243 偏差超阈值时落库时间必须锚定服务端 now，实际=2026-10-16T15:11:53.235441200（客户端声称=2026-10-16T15:11:53.235441200） ==> expected: <true> but was: <false>
BehaviorEventCollectServiceTest.occurredTimeJustBeyondThresholdAnchored:277 刚过阈值即锚定，实际=2026-10-16T14:31:52.325504300 ==> expected: <true> but was: <false>
BehaviorEventCollectServiceTest.staleOccurredTimeAnchoredToServerNow:223->assertAnchoredToServerNow:243 偏差超阈值时落库时间必须锚定服务端 now，实际=2026-10-16T14:11:53.348837800（客户端声称=2026-10-16T14:11:53.348837800） ==> expected: <true> but was: <false>
```

RESTORE -- snapshot restore (exact pre-injection bytes; no git checkout): True

RE-GREEN -- same command, log evidence/mut-m3-green.log, exit=0:
```
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.203 s -- in com.exam.anticheat.collector.BehaviorEventCollectorTest
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.189 s -- in com.exam.anticheat.service.BehaviorEventCollectServiceTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 23.65 s -- in com.exam.anticheat.service.BehaviorLogQueryServiceTest
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.173 s -- in com.exam.monitoring.metrics.BusinessMetricsTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.008 s -- in com.exam.taking.controller.ExamTakingControllerRateLimitGuardTest
[INFO] Tests run: 35, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS

```
## Mutant m4 -- statistics narrow projection removed

Intent: Drop the narrow-projection statistics query, so the projection guard (query must run exactly once, severity-only, no LIMIT) and the statistics-faithfulness assertions must go red.

Injection (src/main/java/com/exam/anticheat/service/BehaviorLogQueryService.java):
```diff
-        List<Integer> severities = behaviorLogMapper.selectSeverities(examId, studentId);
-        BehaviorTimelineResponse response = new BehaviorTimelineResponse();
-        response.setExamId(examId);
-        response.setStudentId(studentId);
-        response.setTotal(severities.size());
-        for (Integer severity : severities) {
-            switch (SeverityLevel.of(severity)) {
-                case LOW -> response.setLowCount(response.getLowCount() + 1);
-                case MEDIUM -> response.setMediumCount(response.getMediumCount() + 1);
-                case HIGH -> response.setHighCount(response.getHighCount() + 1);
-            }
-        }
+        BehaviorTimelineResponse response = new BehaviorTimelineResponse();
+        response.setExamId(examId);
+        response.setStudentId(studentId);
+        response.setTotal(0);
```

KILL (red) -- .\mvnw.cmd -B test "-Dtest=BehaviorEventCollectServiceTest,BehaviorLogQueryServiceTest,BusinessMetricsTest,ExamTakingControllerRateLimitGuardTest,BehaviorEventCollectorTest", log evidence/mut-m4-red.log, exit=1:
```
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.961 s -- in com.exam.anticheat.collector.BehaviorEventCollectorTest
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.149 s -- in com.exam.anticheat.service.BehaviorEventCollectServiceTest
[ERROR] Tests run: 2, Failures: 2, Errors: 0, Skipped: 0, Time elapsed: 20.57 s <<< FAILURE! -- in com.exam.anticheat.service.BehaviorLogQueryServiceTest
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.119 s -- in com.exam.monitoring.metrics.BusinessMetricsTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.006 s -- in com.exam.taking.controller.ExamTakingControllerRateLimitGuardTest
[ERROR] Tests run: 35, Failures: 2, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
BehaviorLogQueryServiceTest.timelineBelowLimitReturnsEverything:182 expected: <1> but was: <0>
BehaviorLogQueryServiceTest.timelineCapsItemsButKeepsStatisticsFaithful:110 统计查询必须恰一次（拆两查而非逐行统计） ==> expected: <1> but was: <0>
```

RESTORE -- snapshot restore (exact pre-injection bytes; no git checkout): True

RE-GREEN -- same command, log evidence/mut-m4-green.log, exit=0:
```
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.348 s -- in com.exam.anticheat.collector.BehaviorEventCollectorTest
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.148 s -- in com.exam.anticheat.service.BehaviorEventCollectServiceTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 24.89 s -- in com.exam.anticheat.service.BehaviorLogQueryServiceTest
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.176 s -- in com.exam.monitoring.metrics.BusinessMetricsTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.011 s -- in com.exam.taking.controller.ExamTakingControllerRateLimitGuardTest
[INFO] Tests run: 35, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS

```
## Mutant m5 -- statistics recomputed from truncated items

Intent: Recompute statistics from the truncated item list: total drops to 1000 and the distribution loses the 5 truncated rows, so the statistics-faithfulness assertions must go red.

Injection (src/main/java/com/exam/anticheat/service/BehaviorLogQueryService.java):
```diff
-        response.setItems(logs.stream().map(this::toItem).toList());
-        return response;
+        response.setItems(logs.stream().map(this::toItem).toList());
+        response.setTotal(logs.size());
+        response.setLowCount(0);
+        response.setMediumCount(0);
+        response.setHighCount(0);
+        for (ExamBehaviorLog row : logs) {
+            switch (SeverityLevel.of(row.getSeverity())) {
+                case LOW -> response.setLowCount(response.getLowCount() + 1);
+                case MEDIUM -> response.setMediumCount(response.getMediumCount() + 1);
+                case HIGH -> response.setHighCount(response.getHighCount() + 1);
+            }
+        }
+        return response;
```

KILL (red) -- .\mvnw.cmd -B test "-Dtest=BehaviorEventCollectServiceTest,BehaviorLogQueryServiceTest,BusinessMetricsTest,ExamTakingControllerRateLimitGuardTest,BehaviorEventCollectorTest", log evidence/mut-m5-red.log, exit=1:
```
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.509 s -- in com.exam.anticheat.collector.BehaviorEventCollectorTest
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.168 s -- in com.exam.anticheat.service.BehaviorEventCollectServiceTest
[ERROR] Tests run: 2, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 26.14 s <<< FAILURE! -- in com.exam.anticheat.service.BehaviorLogQueryServiceTest
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.173 s -- in com.exam.monitoring.metrics.BusinessMetricsTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.011 s -- in com.exam.taking.controller.ExamTakingControllerRateLimitGuardTest
[ERROR] Tests run: 35, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
BehaviorLogQueryServiceTest.timelineCapsItemsButKeepsStatisticsFaithful:136 total 取自全量统计，不得等于 items 条数 ==> expected: <1005> but was: <1000>
```

RESTORE -- snapshot restore (exact pre-injection bytes; no git checkout): True

RE-GREEN -- same command, log evidence/mut-m5-green.log, exit=0:
```
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.761 s -- in com.exam.anticheat.collector.BehaviorEventCollectorTest
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.181 s -- in com.exam.anticheat.service.BehaviorEventCollectServiceTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 22.79 s -- in com.exam.anticheat.service.BehaviorLogQueryServiceTest
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.128 s -- in com.exam.monitoring.metrics.BusinessMetricsTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.008 s -- in com.exam.taking.controller.ExamTakingControllerRateLimitGuardTest
[INFO] Tests run: 35, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS

```
## Equivalent-mutant register (no observable behaviour change; registered, NOT force-killed)

| ID | Injection | Verdict | Note |
|----|-----------|---------|------|
| eq1 | In the anchoring branch, eturn now; -> eturn LocalDateTime.now(); | equivalent | 
ow is read by the method's first statement; the extra call differs only at nanosecond level, and the assertion contract is second-level (ssertKeptWithinOneSecond). Equivalent by construction. |
| eq2 | Size gate if (bytes > MAX) -> if (bytes > MAX + 1) | killable, not needed | The boundary pair (exactly 4096 accepted / 4097 rejected) would catch it; that strength comes from m2's judge class, which holds both boundary assertions. Registered instead of adding a redundant mutant. |
| eq3 | Merge tag "unknown" -> "other" | killable, not needed | The merge assertions pin the tag value fixed by the spec delta; renaming it goes red. The tag name is a spec-fixed literal, so no separate mutant is spent on it. |
| eq4 | MAX_CLOCK_SKEW 10 min -> 10 min + 1 ns | equivalent in practice | The only observable difference needs a client timestamp within 1 ns of the threshold; the preserved/anchored test pair sits ~1 s apart, so no assertion can distinguish it. Registered as equivalent. |

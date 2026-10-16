# Mutation kill evidence (change harden-auth-edge-cases, phase 2)

Each mutant follows inject -> KILL (red) -> snapshot restore -> RE-GREEN. Raw Maven logs: mut-<id>-<red|green>.log.
Equivalent mutants are registered at the end and are NOT force-killed.

## Mutant m1 -- login audit falls back to remoteAddr

Intent: Revert the login endpoint to raw getRemoteAddr(): behind a reverse proxy the audit would record the proxy address, so the XFF real-IP integration test must go red.

Judge subset: LoginAuditRealIpIntegrationTest

Injection (src/main/java/com/exam/auth/controller/AuthController.java):
```diff
-        String ip = ClientIpResolver.resolve(http);
-        return ApiResponse.success(authService.login(request.getUsername(), request.getPassword(), ip));
+        String ip = http.getRemoteAddr();
+        return ApiResponse.success(authService.login(request.getUsername(), request.getPassword(), ip));
```

KILL (red) -- .\mvnw.cmd -B test "-Dtest=LoginAuditRealIpIntegrationTest", log evidence/mut-m1-red.log, exit=1:
```
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 29.68 s <<< FAILURE! -- in com.exam.auth.LoginAuditRealIpIntegrationTest
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
LoginAuditRealIpIntegrationTest.loginFailureAuditRecordsClientIpResolvedFromForwardedHeader:85 登录失败审计必须记录解析后的真实客户端 IP（XFF 首项），实际=[AuditLog(id=1, traceId=1e83fe588ecc552f722f0997418741ff, userId=2, username=stu_ip1, action=LOGIN, ipAddress=127.0.0.1, status=FAILURE, details=账号或密码错误, createdTime=2026-10-16T17:22:16.482398)] ==> expected: <true> but was: <false>
```

RESTORE -- snapshot restore (exact pre-injection bytes; no git checkout): True

RE-GREEN -- same command, log evidence/mut-m1-green.log, exit=0:
```
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 32.67 s -- in com.exam.auth.LoginAuditRealIpIntegrationTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS

```
## Mutant m2 -- markUsed reverts to whole-entity updateById

Intent: Revert to read-modify-write updateById: concurrent teachers lose increments and an invalidated code can be revived, so the targeted-update unit tests must go red.

Judge subset: InviteCodeServiceTest

Injection (src/main/java/com/exam/auth/service/InviteCodeService.java):
```diff
-        int rows = inviteCodeMapper.update(null, Wrappers.<InviteCode>lambdaUpdate()
-                .setSql("used_count = used_count + 1")
-                .eq(InviteCode::getId, ic.getId())
-                .eq(InviteCode::getStatus, 0));
-        if (rows == 0) {
-            throw new BusinessException(ResponseCode.INVITE_CODE_INVALID);
-        }
+        ic.setUsedCount((ic.getUsedCount() == null ? 0 : ic.getUsedCount()) + 1);
+        inviteCodeMapper.updateById(ic);
```

KILL (red) -- .\mvnw.cmd -B test "-Dtest=InviteCodeServiceTest", log evidence/mut-m2-red.log, exit=1:
```
[ERROR] Tests run: 2, Failures: 2, Errors: 0, Skipped: 0, Time elapsed: 4.443 s <<< FAILURE! -- in com.exam.auth.service.InviteCodeServiceTest
[ERROR] Tests run: 2, Failures: 2, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
InviteCodeServiceTest.markUsedIssuesTargetedAtomicIncrementUpdateNotWholeEntity:56
InviteCodeServiceTest.markUsedThrowsInviteCodeInvalidWhenNoActiveRowUpdated:79 Expected com.exam.common.BusinessException to be thrown, but nothing was thrown.
```

RESTORE -- snapshot restore (exact pre-injection bytes; no git checkout): True

RE-GREEN -- same command, log evidence/mut-m2-green.log, exit=0:
```
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.318 s -- in com.exam.auth.service.InviteCodeServiceTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS

```
## Mutant m3 -- reset-code rate limit removed

Intent: Remove the entry rate-limit call: scripted replay of the reset-code endpoint must be allowed, so the 429 integration tests must go red.

Judge subset: ResetCodeRateLimitIntegrationTest

Injection (src/main/java/com/exam/auth/service/AuthService.java):
```diff
-        loginGuard.checkCodeRateLimit(email, ip);
+
```

KILL (red) -- .\mvnw.cmd -B test "-Dtest=ResetCodeRateLimitIntegrationTest", log evidence/mut-m3-red.log, exit=1:
```
[ERROR] Tests run: 2, Failures: 2, Errors: 0, Skipped: 0, Time elapsed: 28.13 s <<< FAILURE! -- in com.exam.auth.ResetCodeRateLimitIntegrationTest
[ERROR] Tests run: 2, Failures: 2, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
ResetCodeRateLimitIntegrationTest.resetCodeEndpointRateLimitedPerEmailAfterPerMinuteThreshold:80 Response status expected:<429> but was:<200>
ResetCodeRateLimitIntegrationTest.unregisteredEmailIsRateLimitedWithoutExposingExistence:95 Response status expected:<429> but was:<200>
```

RESTORE -- snapshot restore (exact pre-injection bytes; no git checkout): True

RE-GREEN -- same command, log evidence/mut-m3-green.log, exit=0:
```
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 27.98 s -- in com.exam.auth.ResetCodeRateLimitIntegrationTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS

```
## Mutant m4 -- counting reverts to two-step INCR/EXPIRE

Intent: Revert to the non-atomic INCR then EXPIRE shape: a crash between the two leaves a key that never expires (permanent rate limit / lock), so the Lua-atomicity unit tests must go red.

Judge subset: LoginGuardServiceTest

Injection (src/main/java/com/exam/auth/service/LoginGuardService.java):
```diff
-        Long count = redis.execute(incrWithTtlScript, List.of(key), String.valueOf(ttl.toSeconds()));
-        return count == null ? 0L : count;
+        Long count = redis.opsForValue().increment(key);
+        if (count != null && count == 1L) {
+            redis.expire(key, ttl);
+        }
+        return count == null ? 0L : count;
```

KILL (red) -- .\mvnw.cmd -B test "-Dtest=LoginGuardServiceTest", log evidence/mut-m4-red.log, exit=1:
```
[ERROR] Tests run: 8, Failures: 8, Errors: 0, Skipped: 0, Time elapsed: 3.832 s <<< FAILURE! -- in com.exam.auth.service.LoginGuardServiceTest
[ERROR] Tests run: 8, Failures: 8, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
LoginGuardServiceTest.checkCodeRateLimitKeysByEmailAndIpWithOneMinuteWindow:143
LoginGuardServiceTest.checkCodeRateLimitThrowsBeyondPerMinute:153 Expected com.exam.common.BusinessException to be thrown, but nothing was thrown.
LoginGuardServiceTest.checkRateLimitIncrementsViaAtomicLuaScriptWithOneMinuteTtl:76
LoginGuardServiceTest.checkRateLimitKeysAreIsolatedByIpDimension:102 Expected com.exam.common.BusinessException to be thrown, but nothing was thrown.
LoginGuardServiceTest.checkRateLimitThrowsTooManyRequestsBeyondPerMinute:89 Expected com.exam.common.BusinessException to be thrown, but nothing was thrown.
LoginGuardServiceTest.recordFailureBelowThresholdDoesNotLockNorAudit:121
LoginGuardServiceTest.recordFailureLocksAndClearsCounterAtThreshold:130
LoginGuardServiceTest.recordFailurePassesRealIpToLockAudit:112
```

RESTORE -- snapshot restore (exact pre-injection bytes; no git checkout): True

RE-GREEN -- same command, log evidence/mut-m4-green.log, exit=0:
```
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.390 s -- in com.exam.auth.service.LoginGuardServiceTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS

```
## Equivalent-mutant register (no observable behaviour change; registered, NOT force-killed)

| ID | Injection | Verdict | Note |
|----|-----------|---------|------|
| eq1 | count > perMinute -> count >= perMinute + 1 | equivalent | Threshold semantics are arithmetically identical (integer counts); the boundary pair at exactly perMinute never throws under either form, so no assertion can distinguish them. |
| eq2 | String.valueOf(ttl.toSeconds()) -> String.valueOf(ttl.getSeconds()) | equivalent | Both yield the same "60" for a one-minute window; the executed Redis command is byte-identical, so no observable difference exists. |
| eq3 | Lua local c = redis.call('incr', KEYS[1]) -> edis.call('incr', KEYS[1]) local c = redis.call('get', KEYS[1]) | equivalent on the tested path | For a live key both read back the same value; only a concurrent writer between INCR and GET could differ, which is outside the atomicity contract this change asserts (and the judge tests never race). Registered, not force-killed. |

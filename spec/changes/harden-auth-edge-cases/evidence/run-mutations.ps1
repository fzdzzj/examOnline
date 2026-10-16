# Mutation kill/restore/re-green evidence collector (change harden-auth-edge-cases, phase 2)
# ASCII-only on purpose: Windows PowerShell 5.1 reads .ps1 as ANSI, which corrupts CJK strings.
# Restore is snapshot-based (NOT git checkout): the implementation lives in the uncommitted
# working tree, so each mutant restores the exact pre-injection file content.
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File evidence/run-mutations.ps1
$ErrorActionPreference = 'Stop'
$ev = (Resolve-Path $PSScriptRoot).Path
$repo = (Resolve-Path "$ev/../../../..").Path
Set-Location $repo

$ctrl = 'src/main/java/com/exam/auth/controller/AuthController.java'
$authSvc = 'src/main/java/com/exam/auth/service/AuthService.java'
$invite = 'src/main/java/com/exam/auth/service/InviteCodeService.java'
$guard = 'src/main/java/com/exam/auth/service/LoginGuardService.java'

function Strip-Cr([string]$text) { return $text.Replace("`r", '') }

# 14 single-line string params joined by LF; empty args are dropped.
function L([string]$a = '', [string]$b = '', [string]$c = '', [string]$d = '', [string]$e = '',
    [string]$f = '', [string]$g = '', [string]$h = '', [string]$i = '', [string]$j = '',
    [string]$k = '', [string]$l = '', [string]$m = '', [string]$n = '') {
    return (($a, $b, $c, $d, $e, $f, $g, $h, $i, $j, $k, $l, $m, $n |
        Where-Object { $_ -ne '' }) -join "`n")
}

# Minimal block diff: '-' the removed block lines, '+' the added block lines.
function Diff-Lines([string]$old, [string]$new) {
    $lo = @(($old -split "`n") | ForEach-Object { $_.TrimEnd("`r") })
    $ln = @(($new -split "`n") | ForEach-Object { $_.TrimEnd("`r") })
    $out = @()
    foreach ($line in $lo) { $out += "-$line" }
    foreach ($line in $ln) { $out += "+$line" }
    return ($out -join "`n")
}

function Run-Focused([string]$id, [string]$tests) {
    $log = "$ev/mut-$id.log"
    # Maven writes Mockito's agent warning to stderr; permissive EAP so the native stderr
    # record does not abort the script (the exit code below is the real signal).
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & .\mvnw.cmd -B test "-Dtest=$tests" -DfailIfNoSpecifiedTests=false > $log 2>&1
    $exit = $LASTEXITCODE
    $ErrorActionPreference = $prevEap
    $summary = (Select-String -Path $log -Pattern 'Tests run:.*-- in ' |
            ForEach-Object { $_.Line.Trim() }) -join "`n"
    $totals = (Select-String -Path $log -Pattern 'Tests run:.*Failures:.*' |
            Where-Object { $_.Line -notmatch '-- in ' } |
            ForEach-Object { $_.Line.Trim() } | Select-Object -Last 1)
    $build = (Select-String -Path $log -Pattern 'BUILD (SUCCESS|FAILURE)' |
            ForEach-Object { $_.Line.Trim() } | Select-Object -Last 1)
    $fails = (Select-String -Path $log -Pattern '^\[ERROR\]   \w+Test\.' |
            ForEach-Object { ($_.Line -replace '^\[ERROR\]\s+', '').Trim() } | Select-Object -First 14) -join "`n"
    return [pscustomobject]@{
        Exit = $exit
        Summary = (@($summary, $totals, $build) | Where-Object { $_ }) -join "`n"
        Failures = $fails
    }
}

function Invoke-Mutation([string]$id, [string]$title, [string]$file, [string]$old, [string]$new,
    [string]$tests, [string]$why) {
    $p = (Resolve-Path $file).Path
    $raw = [System.IO.File]::ReadAllText($p)
    $orig = Strip-Cr $raw
    $old = Strip-Cr $old
    $new = Strip-Cr $new
    if (-not $orig.Contains($old)) { throw "mutant $id anchor miss: $file" }
    $mutated = $orig.Replace($old, $new)
    if ($raw.Contains("`r`n")) { $mutated = $mutated.Replace("`n", "`r`n") }
    [System.IO.File]::WriteAllText($p, $mutated, (New-Object System.Text.UTF8Encoding($false)))
    $diff = Diff-Lines $old $new
    $red = Run-Focused "$id-red" $tests
    # Snapshot restore: exact pre-injection bytes (implementation changes stay in the tree).
    [System.IO.File]::WriteAllText($p, $raw, (New-Object System.Text.UTF8Encoding($false)))
    $restored = ([System.IO.File]::ReadAllText($p) -ceq $raw)
    $green = Run-Focused "$id-green" $tests
    $script:report += @"

## Mutant $id -- $title

Intent: $why

Judge subset: $tests

Injection ($file):
``````diff
$diff
``````

KILL (red) -- `.\mvnw.cmd -B test "-Dtest=$tests"`, log evidence/mut-$id-red.log, exit=$($red.Exit):
``````
$($red.Summary)
$($red.Failures)
``````

RESTORE -- snapshot restore (exact pre-injection bytes; no git checkout): $restored

RE-GREEN -- same command, log evidence/mut-$id-green.log, exit=$($green.Exit):
``````
$($green.Summary)
$($green.Failures)
``````
"@
}

$script:report = @"
# Mutation kill evidence (change harden-auth-edge-cases, phase 2)

Each mutant follows inject -> KILL (red) -> snapshot restore -> RE-GREEN. Raw Maven logs: mut-<id>-<red|green>.log.
Equivalent mutants are registered at the end and are NOT force-killed.

"@

# m1: login falls back to getRemoteAddr (no XFF resolution) -- audit loses the real client IP.
$o1 = L '        String ip = ClientIpResolver.resolve(http);' '        return ApiResponse.success(authService.login(request.getUsername(), request.getPassword(), ip));'
$n1 = L '        String ip = http.getRemoteAddr();' '        return ApiResponse.success(authService.login(request.getUsername(), request.getPassword(), ip));'
Invoke-Mutation 'm1' 'login audit falls back to remoteAddr' $ctrl $o1 $n1 'LoginAuditRealIpIntegrationTest' 'Revert the login endpoint to raw getRemoteAddr(): behind a reverse proxy the audit would record the proxy address, so the XFF real-IP integration test must go red.'

# m2: markUsed reverts to whole-entity read-modify-write -- lost-update / revive-after-invalidate paths return.
$o2 = L '        int rows = inviteCodeMapper.update(null, Wrappers.<InviteCode>lambdaUpdate()' '                .setSql("used_count = used_count + 1")' '                .eq(InviteCode::getId, ic.getId())' '                .eq(InviteCode::getStatus, 0));' '        if (rows == 0) {' '            throw new BusinessException(ResponseCode.INVITE_CODE_INVALID);' '        }'
$n2 = L '        ic.setUsedCount((ic.getUsedCount() == null ? 0 : ic.getUsedCount()) + 1);' '        inviteCodeMapper.updateById(ic);'
Invoke-Mutation 'm2' 'markUsed reverts to whole-entity updateById' $invite $o2 $n2 'InviteCodeServiceTest' 'Revert to read-modify-write updateById: concurrent teachers lose increments and an invalidated code can be revived, so the targeted-update unit tests must go red.'

# m3: reset-code rate limit call removed from the sendResetCode entry.
$o3 = L '        loginGuard.checkCodeRateLimit(email, ip);'
$n3 = L ''
Invoke-Mutation 'm3' 'reset-code rate limit removed' $authSvc $o3 $n3 'ResetCodeRateLimitIntegrationTest' 'Remove the entry rate-limit call: scripted replay of the reset-code endpoint must be allowed, so the 429 integration tests must go red.'

# m4: counting reverts to two-step INCR + EXPIRE (non-atomic).
$o4 = L '        Long count = redis.execute(incrWithTtlScript, List.of(key), String.valueOf(ttl.toSeconds()));' '        return count == null ? 0L : count;'
$n4 = L '        Long count = redis.opsForValue().increment(key);' '        if (count != null && count == 1L) {' '            redis.expire(key, ttl);' '        }' '        return count == null ? 0L : count;'
Invoke-Mutation 'm4' 'counting reverts to two-step INCR/EXPIRE' $guard $o4 $n4 'LoginGuardServiceTest' 'Revert to the non-atomic INCR then EXPIRE shape: a crash between the two leaves a key that never expires (permanent rate limit / lock), so the Lua-atomicity unit tests must go red.'

$script:report += @"

## Equivalent-mutant register (no observable behaviour change; registered, NOT force-killed)

| ID | Injection | Verdict | Note |
|----|-----------|---------|------|
| eq1 | `count > perMinute` -> `count >= perMinute + 1` | equivalent | Threshold semantics are arithmetically identical (integer counts); the boundary pair at exactly `perMinute` never throws under either form, so no assertion can distinguish them. |
| eq2 | `String.valueOf(ttl.toSeconds())` -> `String.valueOf(ttl.getSeconds())` | equivalent | Both yield the same `"60"` for a one-minute window; the executed Redis command is byte-identical, so no observable difference exists. |
| eq3 | Lua `local c = redis.call('incr', KEYS[1])` -> `redis.call('incr', KEYS[1]) local c = redis.call('get', KEYS[1])` | equivalent on the tested path | For a live key both read back the same value; only a concurrent writer between INCR and GET could differ, which is outside the atomicity contract this change asserts (and the judge tests never race). Registered, not force-killed. |

"@

[System.IO.File]::WriteAllText("$ev/mutants.md", $script:report, (New-Object System.Text.UTF8Encoding($true)))
Write-Host "report written"

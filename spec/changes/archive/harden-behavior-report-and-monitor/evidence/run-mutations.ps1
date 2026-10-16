# Mutation kill/restore/re-green evidence collector (change harden-behavior-report-and-monitor, phase 2)
# ASCII-only on purpose: Windows PowerShell 5.1 reads .ps1 as ANSI, which corrupts CJK strings.
# Restore is snapshot-based (NOT git checkout): the implementation lives in the uncommitted
# working tree, so each mutant restores the exact pre-injection file content.
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File evidence/run-mutations.ps1
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path "$PSScriptRoot/..").Path
Set-Location $repo

$targets = 'BehaviorEventCollectServiceTest,BehaviorLogQueryServiceTest,BusinessMetricsTest,ExamTakingControllerRateLimitGuardTest,BehaviorEventCollectorTest'
$ctrl = 'src/main/java/com/exam/taking/controller/ExamTakingController.java'
$collect = 'src/main/java/com/exam/anticheat/service/BehaviorEventCollectService.java'
$query = 'src/main/java/com/exam/anticheat/service/BehaviorLogQueryService.java'

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

function Run-Focused([string]$id) {
    $log = "evidence/mut-$id.log"
    # Maven writes Mockito's agent warning to stderr; permissive EAP so the native stderr
    # record does not abort the script (the exit code below is the real signal).
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & .\mvnw.cmd -B test "-Dtest=$targets" -DfailIfNoSpecifiedTests=false > $log 2>&1
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

function Invoke-Mutation([string]$id, [string]$title, [string]$file, [string]$old, [string]$new, [string]$why) {
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
    $red = Run-Focused "$id-red"
    # Snapshot restore: exact pre-injection bytes (implementation changes stay in the tree).
    [System.IO.File]::WriteAllText($p, $raw, (New-Object System.Text.UTF8Encoding($false)))
    $restored = ([System.IO.File]::ReadAllText($p) -ceq $raw)
    $green = Run-Focused "$id-green"
    $script:report += @"

## Mutant $id -- $title

Intent: $why

Injection ($file):
``````diff
$diff
``````

KILL (red) -- `.\mvnw.cmd -B test "-Dtest=$targets"`, log evidence/mut-$id-red.log, exit=$($red.Exit):
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
# Mutation kill evidence (change harden-behavior-report-and-monitor, phase 2)

Judge test subset: $targets (covers all five implementation points).
Each mutant follows inject -> KILL (red) -> snapshot restore -> RE-GREEN. Raw Maven logs: mut-<id>-<red|green>.log.
Equivalent mutants are registered at the end and are NOT force-killed.

"@

# m1: drop the rate-limit annotation line from the report endpoint.
$o1 = L '    @RateLimit(qps = 200, capacity = 1000, key = "behavior-report")'
Invoke-Mutation 'm1' 'report endpoint loses rate limit' $ctrl $o1 '' 'Remove @RateLimit from the behavior-report endpoint; the reflection guard test must go red.'

# m2: drop the size gate call at the collect() entry.
$o2 = L '        requireEventDataWithinLimit(eventData);'
Invoke-Mutation 'm2' 'eventData size gate disabled' $collect $o2 '' 'Remove the size gate at the collect() entry: oversized payloads would be persisted and counted, so the oversize cases must go red.'

# m3: neutralise the skew computation so the anchoring branch can never fire.
$o3 = L '        Duration skew = Duration.between(now, occurredTime).abs();' '        if (skew.compareTo(MAX_CLOCK_SKEW) > 0) {'
$n3 = L '        Duration skew = Duration.ZERO;' '        if (skew.compareTo(MAX_CLOCK_SKEW) > 0) {'
Invoke-Mutation 'm3' 'occurredTime anchoring disabled (skew branch neutralised)' $collect $o3 $n3 'Neutralise the skew computation: clients could poison timeline ordering with arbitrary past/future times, so the anchoring cases must go red while the within-tolerance cases stay green.'

# m4: drop the narrow-projection statistics query (statistics then only come from the detail row set).
$o4 = L '        List<Integer> severities = behaviorLogMapper.selectSeverities(examId, studentId);' '        BehaviorTimelineResponse response = new BehaviorTimelineResponse();' '        response.setExamId(examId);' '        response.setStudentId(studentId);' '        response.setTotal(severities.size());' '        for (Integer severity : severities) {' '            switch (SeverityLevel.of(severity)) {' '                case LOW -> response.setLowCount(response.getLowCount() + 1);' '                case MEDIUM -> response.setMediumCount(response.getMediumCount() + 1);' '                case HIGH -> response.setHighCount(response.getHighCount() + 1);' '            }' '        }'
$n4 = L '        BehaviorTimelineResponse response = new BehaviorTimelineResponse();' '        response.setExamId(examId);' '        response.setStudentId(studentId);' '        response.setTotal(0);'
Invoke-Mutation 'm4' 'statistics narrow projection removed' $query $o4 $n4 'Drop the narrow-projection statistics query, so the projection guard (query must run exactly once, severity-only, no LIMIT) and the statistics-faithfulness assertions must go red.'

# m5: recompute statistics from the truncated item list.
$o5 = L '        response.setItems(logs.stream().map(this::toItem).toList());' '        return response;'
$n5 = L '        response.setItems(logs.stream().map(this::toItem).toList());' '        response.setTotal(logs.size());' '        response.setLowCount(0);' '        response.setMediumCount(0);' '        response.setHighCount(0);' '        for (ExamBehaviorLog row : logs) {' '            switch (SeverityLevel.of(row.getSeverity())) {' '                case LOW -> response.setLowCount(response.getLowCount() + 1);' '                case MEDIUM -> response.setMediumCount(response.getMediumCount() + 1);' '                case HIGH -> response.setHighCount(response.getHighCount() + 1);' '            }' '        }' '        return response;'
Invoke-Mutation 'm5' 'statistics recomputed from truncated items' $query $o5 $n5 'Recompute statistics from the truncated item list: total drops to 1000 and the distribution loses the 5 truncated rows, so the statistics-faithfulness assertions must go red.'

$script:report += @"

## Equivalent-mutant register (no observable behaviour change; registered, NOT force-killed)

| ID | Injection | Verdict | Note |
|----|-----------|---------|------|
| eq1 | In the anchoring branch, `return now;` -> `return LocalDateTime.now();` | equivalent | `now` is read by the method's first statement; the extra call differs only at nanosecond level, and the assertion contract is second-level (`assertKeptWithinOneSecond`). Equivalent by construction. |
| eq2 | Size gate `if (bytes > MAX)` -> `if (bytes > MAX + 1)` | killable, not needed | The boundary pair (exactly 4096 accepted / 4097 rejected) would catch it; that strength comes from m2's judge class, which holds both boundary assertions. Registered instead of adding a redundant mutant. |
| eq3 | Merge tag `"unknown"` -> `"other"` | killable, not needed | The merge assertions pin the tag value fixed by the spec delta; renaming it goes red. The tag name is a spec-fixed literal, so no separate mutant is spent on it. |
| eq4 | `MAX_CLOCK_SKEW` 10 min -> 10 min + 1 ns | equivalent in practice | The only observable difference needs a client timestamp within 1 ns of the threshold; the preserved/anchored test pair sits ~1 s apart, so no assertion can distinguish it. Registered as equivalent. |

"@

[System.IO.File]::WriteAllText("$repo/evidence/04-mutants.md", $script:report, (New-Object System.Text.UTF8Encoding($true)))
Write-Host "report written"

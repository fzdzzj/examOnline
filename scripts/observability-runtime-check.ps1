# 观测栈只读运行时检查（不改规则/阈值/面板）
# 用法：pwsh -File scripts/observability-runtime-check.ps1
param(
    [string]$Prometheus = 'http://127.0.0.1:9090',
    [string]$App = 'http://127.0.0.1:8080',
    [string]$Grafana = 'http://127.0.0.1:3000'
)

$ErrorActionPreference = 'Continue'
Write-Host "=== observability-runtime-check $(Get-Date -Format o) ==="

function Section($t) { Write-Host ""; Write-Host "--- $t ---" }

Section "App /actuator/prometheus"
try {
    $r = Invoke-WebRequest -Uri "$App/actuator/prometheus" -UseBasicParsing -TimeoutSec 5
    Write-Host "status=$($r.StatusCode) bytes=$($r.Content.Length)"
    $exam = ($r.Content -split "`n" | Where-Object { $_ -match '^exam_[a-z0-9_]+' } | Select-Object -First 12)
    $exam | ForEach-Object { Write-Host $_ }
} catch {
    Write-Host "FAIL: $_"
}

Section "Prometheus targets"
try {
    $t = Invoke-RestMethod -Uri "$Prometheus/api/v1/targets" -TimeoutSec 5
    foreach ($x in @($t.data.activeTargets)) {
        Write-Host ("job={0} health={1} scrapeUrl={2} lastError={3}" -f $x.labels.job, $x.health, $x.scrapeUrl, $x.lastError)
    }
} catch {
    Write-Host "FAIL: $_"
}

Section "Prometheus rules (exam-online-alerts)"
try {
    $rules = Invoke-RestMethod -Uri "$Prometheus/api/v1/rules" -TimeoutSec 5
    $n = 0
    foreach ($g in @($rules.data.groups)) {
        Write-Host "group=$($g.name)"
        foreach ($r in @($g.rules)) {
            $n++
            Write-Host ("  {0} state={1} health={2} type={3}" -f $r.name, $r.state, $r.health, $r.type)
        }
    }
    Write-Host "rules_count=$n"
} catch {
    Write-Host "FAIL: $_"
}

Section "Prometheus alerts (current)"
try {
    $a = Invoke-RestMethod -Uri "$Prometheus/api/v1/alerts" -TimeoutSec 5
    if (-not $a.data.alerts -or @($a.data.alerts).Count -eq 0) {
        Write-Host "(no active alerts)"
    } else {
        foreach ($al in @($a.data.alerts)) {
            Write-Host ("{0} state={1} severity={2} activeAt={3}" -f $al.labels.alertname, $al.state, $al.labels.severity, $al.activeAt)
        }
    }
} catch {
    Write-Host "FAIL: $_"
}

Section "Key PromQL snapshots"
$queries = @(
    'up{job="exam-online"}',
    'exam_mq_dlq_depth',
    'exam_mq_submit_queue_depth',
    'sum(increase(exam_anticheat_events_total[5m]))',
    'sum(increase(exam_ratelimit_degraded_total[5m]))',
    'increase(exam_mq_dlq_entered_total[10m])',
    'histogram_quantile(0.99, sum(rate(exam_submit_duration_seconds_bucket[5m])) by (le))'
)
foreach ($q in $queries) {
    try {
        $enc = [uri]::EscapeDataString($q)
        $res = Invoke-RestMethod -Uri "$Prometheus/api/v1/query?query=$enc" -TimeoutSec 5
        $json = ($res.data.result | ConvertTo-Json -Compress)
        if ([string]::IsNullOrWhiteSpace($json)) { $json = '(empty)' }
        Write-Host "Q: $q"
        Write-Host "  => $json"
    } catch {
        Write-Host "Q: $q"
        Write-Host "  FAIL: $_"
    }
}

Section "Grafana health"
try {
    $h = Invoke-RestMethod -Uri "$Grafana/api/health" -TimeoutSec 5
    Write-Host ($h | ConvertTo-Json -Compress)
} catch {
    Write-Host "FAIL: $_"
}

Write-Host ""
Write-Host "Done. This script is read-only and does not modify alert rules."
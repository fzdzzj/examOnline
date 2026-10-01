# 观测栈动态验证证据

日期：2026-09-16（Asia/Shanghai）  
基线 HEAD（实施前）：`580fe676d735b2cf3e84daf715fee149ba67f635`（`docs(spec): 归档 add-data-retention...`）  
证据性质：L3 运行证据（抓取 UP / 规则 loaded / 真实 firing / 面板出图），**未改**告警 `expr`/`for`/阈值、面板查询、Java 代码。

## 环境

| 项 | 值 |
|---|---|
| 主栈 | `exam-mysql-master` / `exam-mysql-slave` / `exam-redis` / `exam-rabbitmq`（compose 项目 `examonline`） |
| 观测栈 | `docker compose -f docker/observability/docker-compose.observability.yml up -d` → `exam-prometheus` `:9090`、`exam-grafana` `:3000` |
| 应用 | 本机 `8080`，`SPRING_PROFILES_ACTIVE=dev`，`GET /actuator/prometheus` → **200**，含 `exam_*` 指标 |
| Prometheus job | `exam-online` → `host.docker.internal:8080/actuator/prometheus` |
| Grafana | `admin/admin`，数据源 uid=`prometheus`，面板 uid=`exam-online-overview` |

**本机特例（不改仓库配置，仅运维绕过）：**

- 本机 Windows 服务 `MySQL80` 占用 `3306` 且无管理员权限停服 → Docker 主库临时映射 **`13306:3306`**，应用 `DB_URL`/`DB_PASSWORD=root123` 指向之；从库仍 `3307`。
- `schema.sql` 在空 MySQL 上因 `AUTO_INCREMENT` 缺 `PRIMARY KEY` 无法直接 init（H2 测试可过）→ 一次性手工 bootstrap 表结构后 `SQL_INIT_MODE=never` 启动（**未改** `schema.sql`）。
- 观测镜像 tag 本机缺失时，用已有相近版本 retag 为 compose 声明 tag 后启动（未改 compose 文件）。

## 阶段 1：Targets UP + 9 条规则 loaded

### Targets（摘录）

```
GET http://localhost:9090/api/v1/targets
job=exam-online health=up
scrapeUrl=http://host.docker.internal:8080/actuator/prometheus
lastError=
```

应用侧：

```
GET http://127.0.0.1:8080/actuator/prometheus  → 200
# 可见 exam_mq_dlq_depth / exam_submit_* / exam_anticheat_events_total / exam_ratelimit_degraded_total 等
```

### Rules loaded（9/9）

```
GET http://localhost:9090/api/v1/rules
GROUP exam-online-alerts
  ExamOnlineDown
  SubmitFailureRatioHigh
  SubmitLatencyP99High
  MqSubmitQueueBacklog
  RateLimitDegraded
  Http5xxRatioHigh
  AntiCheatEventSpike
  MqDlqBacklog
  MqSubmitRetryExhausted
# 每条 health=ok type=alerting；rules_loaded=9
```

## 9 条规则（逐条）

### 1. ExamOnlineDown — **firing**

- **动作**：停止本机 `exam-online.jar` 进程，使 `up{job="exam-online"}==0` 持续 ≥1m（规则 `for: 1m`）。
- **证据**：`2026-09-16T14:11:26.074Z`  
  `ExamOnlineDown=firing | RateLimitDegraded=firing | MqSubmitRetryExhausted=firing`  
  同期 PromQL：`up{job="exam-online"}` → `0`。
- **恢复**：重新 `java -jar target/exam-online.jar`，健康检查 200；target 回到 `up`。

### 2. RateLimitDegraded — **firing**

- **动作**：在鉴权仍可用的前提下，将本机 Redis `maxmemory` 压到极低 + `noeviction`，使令牌桶 Lua/写失败；用已登录 admin token 连续打 `@RateLimit` 接口 `POST /api/exam-taking/exams/{id}/enter`（`key=pull-paper`）。
- **指标**：`exam_ratelimit_degraded_total{endpoint="pull-paper"}` 从 0 → 65（多轮）。
- **证据**：`2026-09-16T14:09:09.731Z` 四条同时 firing 快照含 `RateLimitDegraded=firing`（`for: 1m` 满足后）。
- **说明**：直接 `docker stop exam-redis` **不足以**断流——本机另有 Windows `Redis` 服务占 `6379`；完整停服需管理员权限。采用 maxmemory 逼写失败是等价的「Redis 故障导致限流判定异常」路径，仍走真实 `RateLimitInterceptor` fail-open 计数，**未改代码/阈值**。

### 3. MqSubmitRetryExhausted — **firing**

- **动作**：经 RabbitMQ Management API 向 `exam.submit.exchange` / routing key `exam.submit` 发布 **非法 JSON** 体，并带 `x-retry-count=3`（已达 `exam.taking.mq.retry-max`），消费者反序列化失败 → `countDlqEntered()` + nack 进死信。
- **指标**：`exam_mq_dlq_entered_total` 升至 12；`increase(...[10m]) > 0`，`for: 0m`。
- **证据**：多轮 alerts 中 `MqSubmitRetryExhausted=firing`（如四条快照 `2026-09-16T14:09:09.731Z`）。
- **边界**：**仅证明告警会响**；**不声称** DLQ 真重投端到端（遗留 #6）已验收。

### 4. MqDlqBacklog — **firing**

- **动作**：同上，死信队列保持非空（`exam.submit.dead.queue` messages≥5→12），应用仍在，`exam_mq_dlq_depth` Gauge ≥5；等待 `for: 5m`。
- **证据**：自 `2026-09-16T13:39:15Z` pending 起，约 5 分钟后持续 `MqDlqBacklog=firing`；四条快照仍含该告警。
- **PromQL**：`exam_mq_dlq_depth` → `12`（恢复应用后仍可读）。

### 5. AntiCheatEventSpike — **firing**

- **动作**：注册学生 `stuobs1`，发布进行中考试并 enter；在 5 分钟窗口内持续 `POST /api/exam-taking/exams/1/behavior`（`SWITCH_SCREEN` 等），使 `sum(increase(exam_anticheat_events_total[5m])) > 50`，并维持窗口直至 `for: 5m`。
- **指标**：`exam_anticheat_events_total{type="SWITCH_SCREEN"}` 最终 ≥317。
- **证据**：`AntiCheatEventSpike=firing` 写入 `alerts-ac-firing.txt`（`2026-09-16T14:07:08.123Z`），并出现在四条同时 firing 快照。

### 6. Http5xxRatioHigh — **未点着（诚实）**

- **尝试**：限流/鉴权路径未稳定制造 ≥5% 的 5xx 占比；业务失败多为 4xx。
- **反证 PromQL**（验证当时）：  
  `sum(increase(http_server_requests_seconds_count{status=~"5.."}[5m])) / clamp_min(sum(increase(http_server_requests_seconds_count[5m])), 1)` → **`0`**  
  不满足 `> 0.05`，故 inactive。**禁止改阈值凑绿。**

### 7. SubmitFailureRatioHigh — **未点着（诚实）**

- **原因**：本轮未走真实交卷 HTTP 失败路径（MQ confirm 失败等）；`exam_submit_failure_total` / `exam_submit_success_total` 保持 0。
- **反证 PromQL**：  
  `sum(increase(exam_submit_failure_total[5m])) / clamp_min(...+ success..., 1)` → **`0`**  
  不满足 `> 0.1`。

### 8. MqSubmitQueueBacklog — **未点着（诚实）**

- **原因**：消费者与应用同进程，非法消息快速进入死信；正常队列深度无法在本地堆到 `> 1000` 且维持 `for: 2m`。
- **反证 PromQL**：`exam_mq_submit_queue_depth` → **`0`**（且 `>=0`，非 -1 哨兵）。  
  规则 `exam_mq_submit_queue_depth > 1000` 为 false。**禁止改阈值。**

### 9. SubmitLatencyP99High — **未点着（诚实）**

- **原因**：无交卷 histogram 样本；本地即便有交卷 P99 通常 ≪ 2s。
- **反证 PromQL**：  
  `histogram_quantile(0.99, sum(rate(exam_submit_duration_seconds_bucket[5m])) by (le))` → **空结果 / 无 series**（无 bucket 流量）。  
  **禁止**在 Java 中 sleep 或下调阈值。

## 面板

- Grafana health：`database=ok`（版本以本机镜像为准）。
- 数据源：`Prometheus` **uid=`prometheus`**。
- 仪表盘：`Exam Online 总览` **uid=`exam-online-overview`**。
- 通过 Grafana `POST /api/ds/query`（datasource uid=`prometheus`）验证查询成功（非数据源错误）：

| ref | 表达式 | 结果（摘要） |
|---|---|---|
| A | `up{job="exam-online"}` | **1**（有序列） |
| B | `sum(jvm_memory_used_bytes)` | **~1.9e8**（有序列） |
| C | `sum(rate(http_server_requests_seconds_count[5m]))` | **~0.13**（有序列） |
| D | `exam_mq_dlq_depth` | **12**（有序列；交卷相关也可为 0 而非 No data） |

面板格语义：

- **有真实序列**：up / JVM 内存 / JVM GC（依赖 Boot 指标）/ HTTP 相关 / 限流降级 / 防作弊事件 / 死信深度。
- **可为 0 而非 No data**：交卷 QPS、成功率、耗时 P50/P95/P99、主队列积压（本轮无成功交卷流量时为 0）。

结论：**总览不是整页 No data**；数据源 uid 正确且 query 成功。

## 明确没有声称的事

- **未改** `exam-online-alerts.yml` 的 `expr` / `for` / 阈值。
- **未改** Grafana 面板 JSON 查询、**未改** Java / `pom.xml` / `schema.sql`。
- **未验收** DLQ 真 broker 往返重投端到端（遗留 #6）；`MqSubmitRetryExhausted` 仅作告警 firing 证据。
- **未验收** 压测级交卷 P99；`SubmitLatencyP99High` 未 firing 且未用 sleep/改阈值凑绿。
- **未声称** `Http5xxRatioHigh` / `SubmitFailureRatioHigh` / `MqSubmitQueueBacklog` 已在本机 firing。

## 可复查命令（只读）

```powershell
# 或使用仓库脚本
./scripts/observability-runtime-check.ps1

Invoke-RestMethod http://localhost:9090/api/v1/targets
Invoke-RestMethod http://localhost:9090/api/v1/rules
Invoke-RestMethod http://localhost:9090/api/v1/alerts
Invoke-WebRequest http://localhost:8080/actuator/prometheus
```

# 可观测栈（Prometheus + Grafana）

独立于主 `docker-compose.yml` 的可选观测编排：只**消费** examOnline 应用已暴露的指标
（BusinessMetrics 导出的 `exam_*` + actuator 标准指标 + Boot JVM 指标），不修改业务侧代码。

动态验证证据见：[`docs/observability-runtime-evidence.md`](../../docs/observability-runtime-evidence.md)  
只读检查脚本：[`scripts/observability-runtime-check.ps1`](../../scripts/observability-runtime-check.ps1)

## 启动顺序

1. **主栈**（MySQL 主从 + Redis + RabbitMQ）  
   ```bash
   docker compose up -d
   ```
2. **应用**（宿主 8080，dev profile；确认 `GET /actuator/prometheus` 返回 200 且含 `exam_` 前缀）  
   ```bash
   mvn spring-boot:run
   # 或 java -jar target/exam-online.jar
   ```
3. **观测栈**  
   ```bash
   docker compose -f docker/observability/docker-compose.observability.yml up -d
   ```

- Prometheus：http://localhost:9090  
- Grafana：http://localhost:3000（管理员密码由 `GRAFANA_ADMIN_PASSWORD` 指定，默认 `admin`）

容器内经 `host.docker.internal:8080` 抓取宿主应用（Linux 需 compose 中 `extra_hosts`）。

## 告警规则（9 条）

文件：`prometheus/rules/exam-online-alerts.yml`，组名 `exam-online-alerts`。

| 告警 | for | 含义（摘要） |
|---|---|---|
| ExamOnlineDown | 1m | `up{job="exam-online"}==0` |
| SubmitFailureRatioHigh | 5m | 交卷失败率 >10% |
| SubmitLatencyP99High | 5m | 交卷 P99 >2s |
| MqSubmitQueueBacklog | 2m | 交卷队列深度 >1000（排除 Gauge=-1 哨兵） |
| RateLimitDegraded | 1m | 近 5m 存在限流 fail-open 降级计数 |
| Http5xxRatioHigh | 5m | HTTP 5xx 占比 >5% |
| AntiCheatEventSpike | 5m | 近 5m 防作弊事件 >50 |
| MqDlqBacklog | 5m | 死信队列深度 >0（排除 -1） |
| MqSubmitRetryExhausted | 0m | 近 10m 有消息因重试耗尽进入死信 |

静态正确性由 `src/test/java/com/exam/observability/AlertAssetsTest.java` 守住（可解析、9 名、指标名与 `BusinessMetrics` 对齐、面板 uid=`prometheus`）。

## 人工 / 动态验收

1. **抓取 UP**：Prometheus → Status → Targets，`exam-online` 为 `UP`。  
2. **规则 loaded**：Status → Rules，`exam-online-alerts` **9** 条且 health OK。  
3. **告警 firing（能点着的）**：对运维可触发的规则，在 `GET /api/v1/alerts` 中见到 `state=firing`（须等满对应 `for`）。  
4. **面板出图**：Grafana → `Exam Online 总览`；up/JVM/HTTP 等应有序列；无交卷流量时交卷相关格可为 **0**，但不应是数据源错误整页 No data。

只读复查：

```powershell
pwsh -File scripts/observability-runtime-check.ps1
```

## 动态已验证 / 诚实边界（2026-09-16）

详见证据文档。摘要：

| 规则 | 动态结果 |
|---|---|
| ExamOnlineDown | 已见 **firing**（停应用 ≥1m） |
| RateLimitDegraded | 已见 **firing**（Redis 写失败触发 fail-open 计数） |
| MqSubmitRetryExhausted | 已见 **firing**（非法消息重试耗尽进 DLQ；**非** DLQ 重投 E2E） |
| MqDlqBacklog | 已见 **firing**（死信非空 ≥5m） |
| AntiCheatEventSpike | 已见 **firing**（5m 内 >50 行为事件并维持 for） |
| Http5xxRatioHigh | 未点着；PromQL 比值约 0 |
| SubmitFailureRatioHigh | 未点着；无交卷失败样本 |
| MqSubmitQueueBacklog | 未点着；队列深度 0，难堆到 >1000 |
| SubmitLatencyP99High | 未点着；无/极低 latency 样本，**未** sleep、**未**改阈值 |

**禁止**为凑 firing 修改告警阈值或面板查询，或在产品代码中注入假耗时。

## 指标范围

- BusinessMetrics 导出的 Prometheus 名（`exam_*`，含交卷、限流降级、防作弊、MQ 队列/死信、重试等）  
- actuator：`up`、`http_server_requests_seconds_*`  
- Boot JVM：`jvm_memory_used_bytes`、`jvm_gc_pause_seconds_*`  
- 刻意**不写** `hikaricp_connections_*`（dynamic-datasource 下绑定未作为告警依赖）

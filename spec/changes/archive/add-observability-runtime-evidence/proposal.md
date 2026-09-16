# 提案：观测栈动态验证（阶段 16）

## Why

阶段 11/14 已经把 Prometheus 规则与 Grafana 面板做成**可静态证明**的资产：`AlertAssetsTest` 守住文件可解析、9 条规则名、指标名能从 `BusinessMetrics` 常量推导、面板 uid=`prometheus`。

这只证明「配得对」，**不证明「跑得起来、会响、能出图」**。交接文档遗留 #4 写得很清楚：Docker 未运行，不得声称告警可用。面试里「我配了告警」和「我见过它 firing」是两个层级。

**已核实（2026-09-16）**：

- 编排：`docker/observability/docker-compose.observability.yml`（Prometheus 9090 / Grafana 3000），独立于主 `docker-compose.yml`。
- 抓取：`host.docker.internal:8080/actuator/prometheus`，规则文件 9 条（P3 的 7 条 + 阶段 14 的 `MqDlqBacklog` / `MqSubmitRetryExhausted`）。
- README 仍写「7 条规则」「BusinessMetrics 6 个指标」，与现状不符（阶段 13/14 已加指标）。
- **没有**任何 runtime 证据文件，也没有任何测试会连 9090/3000。

**期望状态**：观测栈真跑起来；Targets `UP`；9 条规则 loaded；总览面板在真实抓取下出图；对**能用运维动作/真实流量点着的规则**，Prometheus `/api/v1/alerts` 里至少出现过一次 `firing`；点不着的规则**禁止改 YAML 降阈值凑绿**，改为留下 PromQL 查询证明「规则在、当前健康态下 expr 为 false」。证据进仓库，遗留 #4 按实际结果收口或降级。

**本提案性质是「给已有观测栈补 L3 证据」，不是重新设计告警。**

## What Changes

1. **运行手册**：更新 `docker/observability/README.md`（9 条规则、启动顺序：主栈 → 应用 8080 → 观测栈、逐条尝试 firing 的动作与等待 `for` 时间）。
2. **运行证据**：新增 `docs/observability-runtime-evidence.md`，由实施时填写（时间、命令、`/api/v1/alerts` 摘要、哪些 firing / 哪些诚实未点着及原因、Grafana 面板是否出图）。
3. **可选脚本**：`scripts/observability-runtime-check.ps1`（curl Prometheus/Grafana 健康与 alerts，不改规则）。
4. **不改**：告警 `expr` / `for` / 阈值、面板查询、`BusinessMetrics`、应用代码、主 compose。

### 点亮策略（已取舍，实施不得推翻）

| 规则 | `for` | 预期能否真实 firing | 做法 |
|---|---|---|---|
| ExamOnlineDown | 1m | 能 | 停应用 ≥1m，再看 alerts |
| RateLimitDegraded | 1m | 能 | 停 Redis，打带 `@RateLimit` 的接口 |
| MqSubmitRetryExhausted | 0m | 力争 | 主栈 RabbitMQ 真在；让消费重试耗尽（这会部分碰到遗留 #6，只作为告警证据，**不得声称 DLQ 端到端已验收**） |
| MqDlqBacklog | 5m | 力争 | 死信非空且应用仍在（Gauge 才能被抓），等 5m |
| AntiCheatEventSpike | 5m | 力争 | 5 分钟内打出 >50 条防作弊事件 |
| Http5xxRatioHigh | 5m | 视环境 | 真实打出 5xx；禁止改阈值 |
| SubmitFailureRatioHigh | 5m | 视环境 | 真实交卷失败；禁止 mock 指标 |
| MqSubmitQueueBacklog | 2m | 难 | 要应用 **UP** 且队列 >1000；消费者同进程会抽干。点不着就留查询证据 |
| SubmitLatencyP99High | 5m | 难 | 本地 P99 通常 <<2s。**禁止**在代码里 sleep、**禁止**把阈值改成 0.001s |

## Impact

### 受影响的规范
- `spec/specs/observability/spec.md` — ADDED：观测栈动态可验证性。

### 受影响的文件
- `docker/observability/README.md`
- `docs/observability-runtime-evidence.md`（新增，实施时填写）
- `scripts/observability-runtime-check.ps1`（可选）
- `spec/README.md` 遗留 #4（实施结束时按证据更新；本提案落地前不要先改）

### 用户影响
- 无业务 API 变化。多一个可展示的观测证据。

### 需要迁移
- [ ] 数据库
- [x] 文档（README + 证据）

## 时间线评估

小到中：约半天（含 `for: 5m` 等待）。依赖本机 Docker。

## 风险

- **Docker / 8080 起不来**：停，如实回报，不要改规则假装验证。
- **为凑 firing 改阈值**：直接判未完成。那是把「会响」做成假证据。
- **MqSubmitRetryExhausted 需要真 broker**：与遗留 #6 相关。本变更只收「告警能否响」；仍不得写「坏消息真进 DLQ 再真重投已端到端验收」。
- **Grafana 出图依赖已有流量**：至少要有 `up`、JVM、一次 HTTP；交卷相关面板在无交卷时可以是 0 而不是 No data。

# 提案：告警与观测面板（阶段 11）

## Why

L3 目标是「可观测、可恢复、可证明」，但当前只做到**一半**：指标齐备，却无人消费、无人值守。

**已核实的事实**：
- `BusinessMetrics` 是**全仓库唯一**的自定义指标注册点（grep `MeterRegistry|Counter.builder|Timer.builder|Gauge.builder` 仅命中它），暴露 6 个业务指标；
- `micrometer-registry-prometheus` 已引入，`/actuator/prometheus` 已在 `management.endpoints.web.exposure.include` 中（`application.yml:156-160`）；
- **但全仓库没有任何抓取配置、告警规则或面板**：`docker/` 下只有 `mysql/`（`find` 已确认），不存在 prometheus/grafana 任何文件（`spec/README.md` 遗留 #4 登记的就是这条）。

**为什么这不只是「缺个配置」**：指标躺在端点里，故障时没人知道——
- 交卷失败率飙升，只有人主动去翻 `/actuator/prometheus` 才发现；
- MQ 队列积压（5000 并发交卷的典型征兆）无任何提示；
- P2 刚埋下的 `exam.ratelimit.degraded`（Redis 故障期间 fail-open 放行）**埋完就没人看**，等于没做——这正是 P2 提案自己写下的风险「降级不可观测 = 等于没做」的下一步。

**期望状态**：仓库自带一套可直接启动的观测栈（Prometheus 抓取 + 告警规则、Grafana 数据源 + 面板），且这套配置资产的**正确性由测试守住**（指标名与代码对齐、规则结构完整、面板引用一致），不靠人工肉眼核对。

## What Changes

### 1. `docker/observability/`（新建目录，独立于 `docker/mysql/`）
```
docker/observability/
├── docker-compose.observability.yml      # 独立编排片段（不碰主 docker-compose.yml）
├── README.md                             # 启动方式 + 人工验收步骤 + 已知未验证项
├── prometheus/
│   ├── prometheus.yml                    # 抓取配置（job=exam-online）
│   └── rules/exam-online-alerts.yml      # 告警规则
└── grafana/
    ├── provisioning/
    │   ├── datasources/prometheus.yml    # 数据源（固定 uid）
    │   └── dashboards/dashboards.yml     # 面板 provider
    └── dashboards/exam-online-overview.json
```

**为什么独立目录而不改主 `docker-compose.yml`**：观测栈是**可选**的开发/演示依赖，MySQL/Redis/RabbitMQ 是**必需**运行依赖；混在一个文件里会让「只想跑业务」的人被迫拉起监控。且职责清楚，避免一个文件被多路改动。

### 2. 告警规则（只写能从代码确定性推导的指标名）

`BusinessMetrics` 的 6 个指标 → Prometheus 命名是**确定性**的（Micrometer：点转下划线、Timer 加 `_seconds`、Counter 加 `_total`）：

| 代码常量 | Prometheus 指标名 |
|---|---|
| `exam.submit.duration`（Timer） | `exam_submit_duration_seconds_bucket/_count/_sum` |
| `exam.submit.success`（Counter） | `exam_submit_success_total` |
| `exam.submit.failure`（Counter） | `exam_submit_failure_total` |
| `exam.mq.submit.queue.depth`（Gauge） | `exam_mq_submit_queue_depth` |
| `exam.anticheat.events`（Counter，tag `type`） | `exam_anticheat_events_total` |
| `exam.ratelimit.degraded`（Counter，tag `endpoint`） | `exam_ratelimit_degraded_total` |

规则集（7 条，均带 `severity` 与 `summary`/`description`）：
1. `ExamOnlineDown` —— `up{job="exam-online"} == 0`（critical）；
2. `SubmitFailureRatioHigh` —— 失败/(成功+失败) 比率超阈值（用 `clamp_min` 防除零）；
3. `SubmitLatencyP99High` —— `histogram_quantile(0.99, ...) > 2`，**对齐交卷 P99 < 2s 的既有目标**；
4. `MqSubmitQueueBacklog` —— 队列积压超阈值。**必须带 `>= 0 and` 前置条件**：该 Gauge 在无 RabbitAdmin 时返回 `-1`（`BusinessMetrics.submitQueueDepthProvider`），不排除会误报；
5. `RateLimitDegraded` —— `exam_ratelimit_degraded_total` 有增量即告警（Redis 故障期 fail-open 放行必须被人知道）；
6. `Http5xxRatioHigh` —— `http_server_requests_seconds_count{status=~"5.."}` 占比（actuator 标准指标）；
7. `AntiCheatEventSpike` —— `exam_anticheat_events_total` 突增（业务信号，`severity: info`；考试场景下值得看一眼）。

**不写入 `hikaricp_connections_*`**：本项目用 dynamic-datasource，其 Hikari 指标绑定能否被 Boot 自动配置**未经实测**。凭印象写指标名，轻则该规则永久静默（`no data` 不等于健康，反而制造「有告警体系」的假象），重则上线才发现是错的。故明确排除，并记入「未做与原因」。

### 3. Grafana 数据源与面板
- 数据源固定 `uid`（例如 `prometheus`），**面板 JSON 里引用的 uid 必须与之完全一致**（否则面板打开即报 datasource not found —— 这是 Grafana provisioning 最常见的坑）。
- 一个总览面板，含 `job` 模板变量；分组：交卷（QPS / 成功率 / P50-P95-P99）、MQ 积压、限流降级（按 `endpoint`）、HTTP 5xx、JVM 内存与 GC、防作弊事件（按 `type`）。

### 4. 本地编排片段
- `prometheus` + `grafana` 两个服务，端口 9090 / 3000；
- 抓取目标用 `host.docker.internal:8080`（容器内访问宿主应用），并配 `extra_hosts: ["host.docker.internal:host-gateway"]`（Linux 上不配则解析不到）；
- **不硬编码任何密码**：Grafana 管理员密码用 `${GRAFANA_ADMIN_PASSWORD:-admin}`，README 里说明。镜像 tag 用**具体版本**而非 `latest`。

### 5. 静态校验（进入 `mvn test`，不依赖 Docker）
新增 `src/test/java/com/exam/observability/AlertAssetsTest.java`。**无需新增任何依赖**：`org.yaml.snakeyaml.Yaml`（Spring Boot 自带）解析 YAML、Jackson 解析 JSON。

断言项：
1. 各资产文件存在且可解析（YAML / JSON 语法合法）；
2. 每条告警规则含 `alert` / `expr` / `for` / `labels.severity` / `annotations.summary`；
3. **规则与面板里出现的每个指标名都命中「已知指标集合」**——`exam_*` 的交集必须能对应到 `BusinessMetrics` 的常量（防拼写漂移）；非 `exam_*` 的（actuator 标准指标）必须在测试内显式白名单里，白名单外的名字一律失败；
   - （**实施时按提案优先原则扩充白名单**：面板需覆盖 JVM 内存与 GC，故白名单除 `up`、`http_server_requests_seconds_count` 外补入 Boot 自动配置的 `jvm_memory_used_bytes`、`jvm_gc_pause_seconds_sum|count`。这三者属 actuator/Boot 自动配置必暴露项，风险与「自造指标名」不同。**`hikaricp_*` 仍未写入任何规则或面板**，原排除理由不变。）
4. Grafana 数据源 `uid` 与面板 JSON 中引用的 uid 一致；
5. 每个 panel 都有非空 `targets` 且 `expr` 非空。

## Impact

### 受影响的规范
- `spec/specs/observability/spec.md` — 新增（`ADDED`）：指标驱动的告警、观测面板；并删除该文件与 `spec/README.md` 中「有指标无告警」的遗留描述。

### 受影响的文件（写入边界）
- 新增 `docker/observability/**`
- 新增 `src/test/java/com/exam/observability/AlertAssetsTest.java`

**不要触碰**：`application.yml`（单应用下抓取配置的 `job` 标签已足够，再加 common tag 冗余，且该文件是子 agent 冲突高发区）、`docker/mysql/**`、主 `docker-compose.yml`、`BusinessMetrics.java`（本变更只消费指标，不新增指标）、任何 `spec/`（规范合入由收尾环节做）。

### 需要迁移
- [ ] 数据库迁移（无）
- [ ] 配置变更（不涉及应用配置；观测栈为可选依赖）
- [ ] API 版本提升
- [x] 文档更新（本提案 + 规范 + `docker/observability/README.md` + README 遗留清单）

## 时间线评估

小到中：约 1 天（W13）。

## 实施结果（收尾时补记）

- 交付 8 个新文件：`docker/observability/`（7 个配置/文档）+ `AlertAssetsTest.java`（7 个用例）。全量测试 **182/182 通过**（基线 175 + 新增 7），无既有文件被修改（写入边界守住）。
- **偏差**：白名单补入 3 个 Boot 自动配置的 JVM 指标（见上文第 5 节第 3 条），其余按提案字面执行；`application.yml` 未被触碰。
- **额外证据（收尾方独立补做，非子 agent 自证）**：对静态校验做了**变异验证**——分别把面板 JSON 与规则 YAML 中各改错一个指标名（`exam_submit_success_total`→`...succes...`、`exam_ratelimit_degraded_total`→`...degrade...`），`AlertAssetsTest` **两次均如期失败**；恢复后工作区无残留。这证明断言不是空转，且 YAML（规则）与 JSON（面板）两路扫描都真实生效——直接回应「`no data` 不等于健康」的风险。
- **未验证（诚实边界）**：动态行为全部未验证——真抓取、告警真 `firing`、面板真出图。Docker 未运行，已在 `docker/observability/README.md` 与 `spec/README.md` 遗留清单第 4 条显式声明。**不得声称「已验证告警可用」。**

## 风险

- **「配置写了」不等于「告警会响」** —— Docker 未运行，**无法真跑验证告警触发**。本变更的诚实边界是：静态正确性由测试守住，**动态行为（抓取成功、告警真的 firing、面板真的出图）如实标为未验证**，并写进 `docker/observability/README.md` 与 `spec/README.md` 遗留清单。不得声称「已验证告警可用」。
- **`no data` 不等于健康** —— 若指标名写错，Prometheus 不会报错，规则会永久静默。这正是第 5 项静态校验存在的唯一理由：把「名字对不对」变成会失败的测试。
- **Gauge 的 `-1` 哨兵值** —— `exam_mq_submit_queue_depth` 在无 RabbitAdmin 时返回 `-1`，规则必须排除负值，否则要么误报、要么被 `> 1000` 静默漏掉真实积压。
- **面板与数据源 uid 不一致** —— provisioning 下最常见故障，且症状是「面板能打开但全是 No data」，极易被误判为指标没上报。故列为断言项。
- **镜像 tag 漂移** —— 用 `latest` 会让「上次还能跑」的栈某天启动即坏；用具体版本，且版本真实性在 Docker 可用时复核。

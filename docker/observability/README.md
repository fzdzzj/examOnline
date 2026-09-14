# 可观测栈（Prometheus + Grafana）

独立于 `docker/mysql/` 的可选观测编排片段：只**消费** examOnline 应用已暴露的指标
（BusinessMetrics 6 个指标 + actuator 标准指标），不修改业务侧任何代码。

## 启动

```bash
docker compose -f docker/observability/docker-compose.observability.yml up -d
```

- Prometheus：http://localhost:9090
- Grafana：http://localhost:3000 （管理员密码由 `GRAFANA_ADMIN_PASSWORD` 指定，默认 `admin`）

前提：宿主 8080 上已启动 examOnline 应用（`/actuator/prometheus` 已暴露）。

## 人工验收步骤

1. **抓取是否 up**：Prometheus → Status → Targets，`exam-online` 应为 `UP`
   （容器内经 `host.docker.internal:8080` 抓取宿主应用）。
2. **告警规则是否 loaded**：Prometheus → Status → Rules，应有 `exam-online-alerts`
   组的 7 条规则且状态 `OK`。
3. **面板是否出图**：Grafana → Dashboards → `Exam Online 总览`，各 panel 应有数据；
   若面板能打开但全 `No data`，优先核对数据源 `uid` 是否为 `prometheus`。

## 指标范围与已知未验证

**动态行为未在本机验证（Docker 未运行）**。本目录配置资产的静态正确性由
`src/test/java/com/exam/observability/AlertAssetsTest.java` 守住：
文件可解析、规则结构完整、指标名与 `BusinessMetrics` 常量对齐、面板 uid 与数据源一致。

- 指标名只使用：BusinessMetrics 6 个常量导出的 Prometheus 名（`exam_*`）+ actuator 标准指标
  （`up`、`http_server_requests_seconds_count`）+ 面板所需的 Boot 自动配置 JVM 指标
  （`jvm_memory_used_bytes`、`jvm_gc_pause_seconds_*`）。
- 刻意**不写 `hikaricp_connections_*`**：本项目用 dynamic-datasource，其 Hikari 指标能否被
  Boot 自动绑定未经实测；写错的规则会因 `no data` 永久静默，制造「有告警体系」的假象。

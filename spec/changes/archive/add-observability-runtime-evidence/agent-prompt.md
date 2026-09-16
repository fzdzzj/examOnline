# 子 agent 提示词 —— `add-observability-runtime-evidence`（阶段 16）

> 用法：整份复制发给子 agent。自包含。
> 先读：`spec/changes/add-observability-runtime-evidence/proposal.md` 与 `tasks.json`。

---

## 现状

- 分支 `feature/add-performance-deepening-readwrite`。阶段 15 代码已在 `f42adba`（归档可能并行，**不要改 retention 代码**）。
- 告警 YAML **已有 9 条**，不要再加规则。
- README 仍写 7 条 / 6 个指标，那是过时句子，本轮改 README。
- 最多修复尝试 **1 次**（指文档/脚本；产品代码本轮不改）。
- **你必须自己 commit**。提交后 `git rev-parse HEAD`；HEAD 变 unborn 则补 ref，不要 `git update-ref`。
- **不要改 `spec/**` 里的 tasks 勾选与 README 遗留**（归档另一轮做）。证据写在 `docs/`。

---

## 硬约定

1. **禁止改** `exam-online-alerts.yml` 的 `expr` / `for` / 阈值。
2. **禁止改** Grafana 面板 JSON 的查询（pretty-print 也不要再来一遍）。
3. **禁止改 Java**（不要 sleep 凑 P99，不要注入假指标）。
4. 点不着的规则：PromQL 查询 + 原因写进证据。**这比假 firing 更正确。**
5. 即使点着了 `MqSubmitRetryExhausted`，也 **不得声称** 遗留 #6（真 broker 往返端到端）已验收。
6. 不新增依赖、不改 `pom.xml` / `schema.sql`。

## 写入边界

允许新增：
- `docs/observability-runtime-evidence.md`
- `scripts/observability-runtime-check.ps1`

允许修改：
- `docker/observability/README.md`

禁止：其它一切（含 `src/**`、告警 YAML、面板 JSON、`spec/**`）。

---

## 环境

主栈：`docker compose up -d`（MySQL 主从 + Redis + RabbitMQ）  
观测栈：`docker compose -f docker/observability/docker-compose.observability.yml up -d`  
应用：本机 8080，`/actuator/prometheus` 必须 200。

Prometheus http://localhost:9090  Grafana http://localhost:3000（admin / admin，除非改了环境变量）

Docker 起不来 → 停，回报原因，不要改规则。

---

## 实施

**阶段 1** 按 tasks 把 UP + 9 条 loaded 做成可粘贴的 curl 输出。

**阶段 2** 按 proposal 表格逐条尝试。`for` 窗口必须等（Down 1m、若干 5m）。  
用 `GET http://localhost:9090/api/v1/alerts` 证明 `state=firing`。  
恢复 Redis/应用，避免把开发机留在坏状态。

**阶段 3** 面板：至少不是整页 No data。

**阶段 4** 填写 `docs/observability-runtime-evidence.md`，结构必须含：

```
# 观测栈动态验证证据
日期 / HEAD
环境（compose 项目名、8080 是否 UP）
## 9 条规则
每条：动作、是否 firing、摘录或未点着原因
## 面板
哪些格有序列 / 哪些为 0
## 明确没有声称的事
- 未改阈值
- 未验收 DLQ 真重投端到端
- 未验收压测 P99
```

更新观测 README：9 条、启动顺序、动态验证边界。

Commit 建议：
```
docs(observability): 记录观测栈动态验证证据（抓取 UP / 规则 firing / 面板出图）
```

---

## 回报

1. 主栈/观测栈/应用是否起来（命令与退出码）
2. Targets UP 的摘录
3. 9 条规则：firing 或未点着（不要含糊）
4. 面板结论
5. 改动文件
6. `git rev-parse HEAD`
7. 意外发现

禁止：改 YAML 凑 firing、改 Java、勾 spec tasks。

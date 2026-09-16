# 子 agent 提示词（归档收口）—— `add-dlq-observability-and-replay`（阶段 14）

> 用法：整份复制发给子 agent。自包含。
> 本轮：**纯文档收口 + 你必须自己 commit**。代码已在 `c09eced`，不要再改 Java / SQL / 测试 / docker 资产。

---

## 写入边界

允许：

- `spec/specs/reliability/spec.md`
- `spec/specs/observability/spec.md`
- `spec/README.md`
- `spec/changes/add-dlq-observability-and-replay/tasks.json`
- `spec/changes/add-dlq-observability-and-replay/` → `spec/changes/archive/add-dlq-observability-and-replay/`（`git mv` 整目录，含 `agent-prompt*.md`）

禁止：`src/**`、`docker/**`、`pom.xml`、`docs/**`、阶段 15 提案、已有 archive。

- 最多 1 次。不跑 Maven。
- 只有 1 个 `docs(spec)` commit。你自己提交。之后 `git rev-parse HEAD`；HEAD 变 unborn 则补 `.git/refs/heads/feature/add-performance-deepening-readwrite`，不要 `git update-ref`。

---

## 现状（指导 agent 已核 diff，不要回退代码）

- HEAD：`c09eceddc53cd0411b000de5c6adffcb8ffcb67f`
  `feat(reliability): 为交卷死信队列增加可观测性与有界重投`
- 工作区干净。`tasks.json` 仍全 false。
- 已核实：DLQ Gauge `-1` 哨兵；`handleOne` 埋点且未改重试判定 / 未动 filled==0；`receive` 后立刻落档；ADMIN 重投；告警 2 条 + 面板 1 格；`AlertAssetsTest` 已扩；无新依赖。
- **真 broker 往返未验证**（遗留 #6 必须保留）。
- Grafana JSON 被 pretty-print：归档不要去「还原格式」。
- schema.sql 的 `exam_dlq_messages` 无显式 `PRIMARY KEY`、迁移脚本有——与本仓库多数表在 schema.sql 不写 PK 的既有风格同类。**本轮不要改 schema。**

回勾前自己 grep，回报写位置。

---

## 要做的事

### 1. 回勾 tasks.json

属实才 true。看不清留 false。

- 阶段 4「在 README 登记真 broker 未验证」：遗留 #6 **实施前已在**，本轮未改 README 正文。勾 true，step 可注明「已存在，实施期未改」。
- 不要另开阶段 5。

### 2. 合入规范

**reliability**：追加 delta 三条 ADDED（可见性 / 有界重投 / 不设 TTL 与容量上限）。保留阶段 13 及限流原需求。文首来源追加 `add-dlq-observability-and-replay`。注记：重投是加速手段，正确性仍靠消费端幂等 + 补发对账；`receive` autoAck 窗口可接受；禁止常驻 DLQ 消费者。

**observability**：追加「死信队列的指标与告警覆盖」。文首来源追加本变更。注记：规则名 `MqDlqBacklog` / `MqSubmitRetryExhausted`；指标 `exam.mq.dlq.depth` / `exam.mq.retry` / `exam.mq.dlq.entered`；静态由 `AlertAssetsTest` 守住，动态仍属遗留 #4/#6。

保持 EARS。不要改其他域 spec。

### 3. 更新 README（只动 14 收口相关句）

1. 进行中只留阶段 15。删掉「14、15 均待实施」这种把 14 混进去的说法。
2. 已合入：`reliability` 来源追加本变更（阶段含 14）；`observability` 同样追加。
3. 已归档表 +1 行，计数 16→17。摘要：DLQ 深度/进死信/重试计数 + 2 条告警 + 面板 + 有界留档重投。写明**未做真 broker 端到端**。
4. 阶段映射：14 → 已归档（真 broker 往返仍遗留）。
5. 能力地图可补「死信可见性与有界重投」，不新开域。
6. **遗留 #6 必须保留**（可加一句「阶段 14 已补指标/告警/mock 重投，真 broker 往返仍未验证」）。不要动 #1/#4/#5/#7/#8。

### 4. git add 提示词后 git mv

把本文件 `agent-prompt-archive.md` 一并 add。然后：

```
git mv spec/changes/add-dlq-observability-and-replay spec/changes/archive/add-dlq-observability-and-replay
```

进行中只剩 `add-data-retention` + `archive/`。

### 5. commit（你来做）

```
docs(spec): 归档 add-dlq-observability-and-replay 并合入 reliability/observability 规范
```

正文：合入哪些 Requirement、遗留 #6 仍保留、代码在 `c09eced`。

---

## 验收对照（回报 yes/no）

- [ ] 两份 spec 均含 ADDED；限流/阶段 13 原文仍在
- [ ] tasks 每个 true 有代码位置
- [ ] README：进行中仅 15、归档 17、阶段 14 已归档、遗留 #6 仍在
- [ ] 进行中无本变更目录；archive 含全部 agent-prompt*.md
- [ ] 未改 src / docker / 阶段 15
- [ ] 1 个 docs(spec) commit；`git rev-parse HEAD` 有值

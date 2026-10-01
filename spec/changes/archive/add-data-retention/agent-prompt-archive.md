# 子 agent 提示词（归档收口）—— `add-data-retention`（阶段 15）

> 用法：整份复制发给子 agent。自包含。
> 本轮：**纯文档收口 + 你必须自己 commit**。代码已在 `f42adba`，不要再改 Java / yml 业务键 / SQL。

---

## 写入边界

允许：

- `spec/specs/data-access/spec.md`
- `spec/README.md`
- `spec/changes/add-data-retention/tasks.json`
- `spec/changes/add-data-retention/` → `spec/changes/archive/add-data-retention/`（`git mv` 整目录，含全部 `agent-prompt*.md`）

禁止：`src/**`、`docker/**`、`pom.xml`、`application.yml`、`docs/**`、新观测提案目录（若已存在不要动）、已有 archive。

- 最多 1 次。不跑 Maven。
- 1 个 `docs(spec)` commit。你自己提交。之后 `git rev-parse HEAD`；HEAD 变 unborn 则补 `.git/refs/heads/feature/add-performance-deepening-readwrite`，不要 `git update-ref`。

---

## 现状（指导 agent 已核）

- HEAD：`f42adbadf87f290e86b381e8f307104a59b80a3f`
  `feat(retention): 按考试生命周期有界清理三张辅助表`
- 工作区可能还有指导侧改过的 `agent-prompt.md`：归档时一并 add。
- 默认 `enabled=false` + `dry-run=true`；删除只带 `exam_id`；`exam_dlq_messages` **明确不纳入**。
- 真表 3 用例；候选 SQL 有 `ORDER BY end_time ASC, id ASC` 与 `max-exams-per-run`（提案未写死，实施期加的有界，记下即可）。
- README 遗留 **#7 磁盘 / #8 ended_time 已在**，本轮不要删。#7 那条「登记磁盘回收不在范围」视为已存在，勾 true 并注明「实施前已在」。

回勾前自己 grep。

---

## 要做的事

### 1. 回勾 tasks.json

属实才 true。看不清留 false。

- 阶段 3「条件纳入 exam_dlq_messages」：改 step 原文为实施期事实（表存在、无 exam_id、明确不纳入），再勾 true。不要假装已纳入。
- 阶段 2 或 4 追加一条已完成 step（不要开阶段 5）：`max-exams-per-run`（默认 100）+ 候选 `ORDER BY end_time,id`，使单次运行有界、优先最老考试。

### 2. 合入 `spec/specs/data-access/spec.md`

追加 delta 两条 ADDED。保留读写分离 / 读己之写。

文首来源追加 `add-data-retention`。注记：三张表、按 `exam_id`、零 DDL、默认双关、不纳入 DLQ 表、不声称磁盘释放、用 `end_time` 不用 `updated_time`。

### 3. 更新 README

1. 进行中表：若当时还没有阶段 16 目录，进行中可空或只留观测新变更（**不要删别人新建的 `add-observability-runtime-evidence`**）。
2. `data-access` 来源追加本变更，阶段含 15。
3. 归档表 +1，计数 17→18（若观测变更尚未进 archive）。摘要：默认关闭+dry-run、三张辅助表按考试生命周期、零 DDL、不纳入 DLQ。
4. 阶段映射 15 → 已归档。
5. 能力地图 data-access 可补「数据保留与有界清理」。
6. **#7/#8 必须保留**。不要动 #1/#4/#5/#6。

### 4. git mv 后 commit

```
docs(spec): 归档 add-data-retention 并合入 data-access 规范
```

正文：合入哪两条 Requirement、DLQ 不纳入、#7/#8 仍保留、代码在 `f42adba`。

---

## 验收对照

- [ ] data-access 含保留策略两条 ADDED；读写分离仍在
- [ ] DLQ 不纳入已写进 tasks step
- [ ] README：15 已归档、#7/#8 仍在
- [ ] archive 含全部 agent-prompt*.md
- [ ] 未改 src；1 个 docs(spec)；HEAD 有值

# 子 agent 提示词（归档收口）—— `add-observability-runtime-evidence`（阶段 16）

> 用法：整份复制发给子 agent。自包含。
> 本轮：**纯文档收口 + 你必须自己 commit**。不要改 Java / 告警 YAML / 面板 JSON / 证据正文事实。

---

## 写入边界

允许：
- `spec/specs/observability/spec.md`
- `spec/README.md`
- `spec/changes/add-observability-runtime-evidence/tasks.json`
- 整个 `spec/changes/add-observability-runtime-evidence/`（目前 **未跟踪**）→ `git add` 后 `git mv` 到 `spec/changes/archive/add-observability-runtime-evidence/`

禁止：`src/**`、`docker/**`（README 已在 feat 里改过）、`docs/observability-runtime-evidence.md` 的结论、`pom.xml`、阶段 17 新提案（若已存在不要删）。

不要碰工作区 `%SystemDrive%/`。

- 最多 1 次。不跑 Maven、不起 Docker。
- 1 个 `docs(spec)` commit。你自己提交。之后 `git rev-parse HEAD`；HEAD 变 unborn 则补 ref。

---

## 现状（指导 agent 已核）

- HEAD：`ff7e98145f46f2808037412f6ffb069f6a2c9b65`
  `docs(observability): 记录观测栈动态验证证据…`
- 提案目录 **还在 untracked**，必须先 `git add` 再 mv，否则 archive 里会缺 proposal/tasks/delta/prompt。
- 证据：`docs/observability-runtime-evidence.md`
  - firing：ExamOnlineDown / RateLimitDegraded / MqSubmitRetryExhausted / MqDlqBacklog / AntiCheatEventSpike
  - 未点着（PromQL 反证）：Http5xxRatioHigh / SubmitFailureRatioHigh / MqSubmitQueueBacklog / SubmitLatencyP99High
- **未改阈值、未声称 #6 端到端。**

回勾靠读证据文件，不要凭印象把 9 条都勾成 firing。

---

## 要做的事

### 1. 回勾 tasks.json

属实才 true。阶段 2「对其余 7 条」：5 条里另外 3 条 firing + 4 条未点着已记录 → 勾 true，step 注明未点着的四条名字。不要假装 9/9 firing。

### 2. 合入 observability spec

追加 ADDED「观测栈动态可验证性」。保留既有指标/告警/面板需求。

文首来源追加 `add-observability-runtime-evidence`。注记：证据在 `docs/observability-runtime-evidence.md`；5 firing / 4 未点着；禁止改阈值凑绿；不得声称 DLQ 端到端。把「动态仍属遗留 #4」那句改成指向证据文件。

### 3. 更新 README

1. 进行中：删阶段 16。若已有 `fix-schema-mysql-pk`（或同名）目录，进行中只留它，**不要删。**
2. observability 来源追加本变更，阶段含 16。
3. 归档表 +1，计数 18→19（若主键提案尚未归档）。摘要写清 5 firing / 4 未点着 / 证据路径。
4. 阶段映射 16 → 已归档。
5. **遗留 #4**：从「Docker 未运行」迁入 **已收口**，写明：Targets UP、9 条 loaded、5 条真实 firing、面板出图；四条流量/性能阈值未在本机点着且未改规则。详见证据文件。
6. **不要动 #1 压测、#5 补考接线、#6 DLQ 端到端、#7 磁盘、#8 ended_time。** #6 仍未完成（firing ≠ 重投闭环）。

### 4. git add 整个变更目录后 git mv，然后 commit

```
docs(spec): 归档 add-observability-runtime-evidence 并合入 observability 规范
```

正文：合入动态可验证性、#4 收口口径、5/4 分裂、代码/证据在 `ff7e981`。

---

## 验收对照

- [ ] spec 含 ADDED；未把 4 条未点着写成已 firing
- [ ] README：16 已归档、#4 在已收口且表述诚实、#6 仍在
- [ ] archive 含 proposal/tasks/delta/agent-prompt*.md
- [ ] 未改 src / 告警 YAML；1 个 docs(spec)；HEAD 有值

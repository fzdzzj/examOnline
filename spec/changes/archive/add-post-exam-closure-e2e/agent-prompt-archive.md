# 子 agent 提示词（归档收口）—— `add-post-exam-closure-e2e`（阶段 12）

> 用法：整份复制发给子 agent。自包含，不依赖任何先前对话。
> 本轮性质：**纯文档收口**。代码已在先前 3 个 commit 里交付并合入工作区，**不要再改 Java / SQL / 测试**。

---

## 角色与硬约束

你是实施子 agent，只做阶段 12 的 OpenSpec 收尾五步中的文档动作。

### 写入边界（只允许这些路径）

允许写：

- `spec/specs/absence-makeup/spec.md`
- `spec/README.md`
- `spec/changes/add-post-exam-closure-e2e/tasks.json`（回勾；目录随后整体搬走）
- `spec/changes/add-post-exam-closure-e2e/` → `spec/changes/archive/add-post-exam-closure-e2e/`（`git mv` 整目录）

绝对不要触碰：

- `src/**`
- `pom.xml`、`maven-settings.xml`、`.mvn/**`
- `src/main/resources/schema.sql`、`application*.yml`
- `docker/**`
- `docs/**`（含交接文档，指导 agent 自己维护）
- `spec/specs/` 下除 `absence-makeup/spec.md` 以外的任何文件
- `spec/changes/add-multi-instance-sweep-safety/`、`add-dlq-observability-and-replay/`、`add-data-retention/`
- `spec/changes/archive/` 里已有的 14 个变更

### 次数 / 回报 / 细节

- 最多尝试 **1 次**。对不上就停，把对不上的文件与原因写进回报，不要猜着勾满。
- 回报 ≤300 字以外的细节写进 commit message。回报必须结构化：基线 / 改动清单 / 结果 / 逐条对照 / 意外发现。
- **本轮不要跑 Maven / 不要跑测试**。回勾依据是 `grep`/`read`，不是再测一遍。
- **本轮不要空造 `feat` commit**。代码已提交，只做一个 `docs(spec)` commit。

---

## 现状（已核实，不要重复劳动、不要回退）

工作分支：`feature/add-performance-deepening-readwrite`
HEAD（指导 agent 本轮核实）：`284dddcfb066650809b53e62870fadb566083c58`
工作区干净。阶段 12 目录仍在 `spec/changes/add-post-exam-closure-e2e/`，**尚未**进入 `archive/`。

相关代码 commit（已在历史上，不要重做）：

- `e616c10 feat(exam): 教师提前结束同步标记缺考，补齐 force-end 结束路径`
- `fad82b2 fix(score): 修复复核接口两处使其第一次真正可用`
- `d66ebf7 test(closure): 新增考后闭环端到端验收 9 条用例，并删除 @Sql 自建表`

指导 agent 本轮已 grep 核实、**你回勾前仍须自己再看一眼**（回报里写你看到的位置，不要抄本段当证据）：

| 任务 | 已核实现象 |
|---|---|
| 阶段 1 | `ExamService.forceEnd` 在 `casTransition(IN_PROGRESS, ENDED)` 之后调用 `absenceService.markAbsence(id)`，注释写明先迁状态再标记 |
| 阶段 2/3 | `src/test/java/com/exam/closure/PostExamClosureIntegrationTest.java` 现有 **9** 个 `@Test`（含教师复核清单） |
| 阶段 4 | `src/test` 下 `@Sql` 已空；`ClassManagementIntegrationTest` 已无自建表 |
| 阶段 5 | `schema.sql` 的 `score_review` 已有 `created_time`；`docker/mysql/migrations/2026-W15-add-score-review-created-time.sql` 存在 |
| 额外修复（不在原 tasks 里） | `ScoreReviewController.listByExam` 已是 `@PathVariable Long examId`，路径 `/api/exams/{examId}/score-reviews` |

`tasks.json` **全部 `completed: false` / `passes: false`**——这是收口漏勾，不是代码没做。

覆盖率数字（MakeupService 81.74% / AbsenceService 94.24% / ScoreReviewController 100%、全量 191/0/0）来自交接文档，**不是你本轮实测**。README 不要新写这些百分比；阶段 4 覆盖率那一步若 `target/site/jacoco/jacoco.csv` 不存在，仍可按「端到端用例已落地 + 不为数字写假测试」勾完成，但不要编造数字。

---

## 你要完成的事（按顺序）

### 1. 按代码实际完成度回勾 `tasks.json`

打开 `spec/changes/add-post-exam-closure-e2e/tasks.json`，对每个 step **自己 grep/read 一次**，属实才改 `completed: true`；五个 task 的 `passes` 仅在其全部 step 属实时改 `true`。

硬规则：

- **不得凭本提示词或交接文档印象勾满。**
- 某 step 看不清 → 留 `false`，回报里写原因。
- 阶段 3 最后一条「实施期实测记录」（4 个红 → 修完转绿）属于过程记录：对应测试方法仍在、缺陷已修，可勾。
- **额外修复**：在阶段 4 或阶段 5 的 `steps` **追加一条已完成 step**（与阶段 3 最后一条同风格），记录：`ScoreReviewController.listByExam` 路径模板 `{examId}` 曾误用 `@RequestParam`，已改为 `@PathVariable`，教师复核清单用例覆盖。**不要另开阶段 6。**

### 2. 把 `spec-delta.md` 合入 `spec/specs/absence-makeup/spec.md`

源：`spec/changes/add-post-exam-closure-e2e/specs/absence-makeup/spec-delta.md`
目标：`spec/specs/absence-makeup/spec.md`

合入规则：

- **替换**原文 `Requirement: 缺考标记` 整段为 delta 的 MODIFIED 版（含「教师提前结束同样标记缺考」「答了但未交卷不算缺考」「重复触发不重复标记」等 scenario，以及「为什么要把结束写清」那段说明）。
- **原样保留** `Requirement: 补考独立记录` 与 `Requirement: 补考成绩规则`。后者代码仍未接线（遗留第 5 条），**禁止删、禁止改成已接线。**
- **追加** delta 的 ADDED：`考后闭环跨环节一致性`、`闭环链路的整体可执行性`。
- 更新文首：
  - 来源追加 `spec/changes/archive/add-post-exam-closure-e2e`（阶段 12：两条结束路径对称、闭环端到端、建表与实体双向一致）。
  - 实施注记写清：缺考锚定「进行中→已结束」，**自然到点与 force-end 两条路径都必须 `markAbsence`**；闭环由 `PostExamClosureIntegrationTest` 整链执行；集成测试不得 `@Sql` 自建表。
- 保持 EARS：`### Requirement:` + `系统 SHALL` + `#### Scenario:` + `GIVEN/WHEN/THEN`。
- **不要**改 `spec/specs/score-review/spec.md`（本变更没有该域 delta）。

### 3. 更新 `spec/README.md`

只改与阶段 12 收口直接相关的句子，不要重写全文。

必须改：

1. **当前状态 / 进行中变更**：阶段 12 从进行中表删除。进行中只留 13/14/15。删掉「阶段 12、13、14、15，均待实施」这种把 12 与未实施混为一谈的说法。
2. **已合入规范表**：`absence-makeup` 的来源变更追加 `add-post-exam-closure-e2e`，阶段写 `9、12`。
3. **已归档变更表**：增加一行阶段 12 `add-post-exam-closure-e2e`；归档计数 14→15。摘要须包含：闭环端到端 + force-end 漏标缺考修复 + `score_review.created_time` 补列 + 删除 `@Sql` + `listByExam` 的 `@PathVariable` 修正。
4. **遗留事项 #2、#3**：从「进行中」挪到文内 **已收口** 列表，写明由阶段 12 收口。保留它们曾经是真问题的事实（force-end 漏标、`score_review` 缺列、`@Sql` 掩盖缺表），避免读起来像从来没出过事。
5. **遗留 #5（补考成绩规则未接线）必须保留**，不要当阶段 12 已完成。
6. **阶段映射**：
   - 阶段 9：`已归档（端到端验收遗留）` → `已归档（端到端已由阶段 12 收口）`
   - 阶段 12：`进行中` → `已归档`
7. 能力地图 `absence-makeup` 范围可补「两条结束路径的缺考标记」，不要扩成新能力域。

不要动遗留 #1 压测、#4 观测栈动态验证、#6 DLQ 真 broker、#7 磁盘回收、#8 `ended_time`。

### 4. `git mv` 整目录进 archive

```
git mv spec/changes/add-post-exam-closure-e2e spec/changes/archive/add-post-exam-closure-e2e
```

先改 `tasks.json` / 再 mv，避免改一份丢一份。mv 后确认：

- `spec/changes/` 下只剩 13/14/15 + `archive/`
- `spec/changes/archive/add-post-exam-closure-e2e/` 含 `proposal.md`、`tasks.json`、`specs/absence-makeup/spec-delta.md`、全部 `agent-prompt*.md`

### 5. 一次 commit

```
docs(spec): 归档 add-post-exam-closure-e2e 并合入 absence-makeup 规范
```

正文写清：合入了哪些 Requirement、遗留 #2/#3 收口、#5 仍保留、代码已在先前 feat/fix/test commit。中文、Conventional Commit。

提交后立刻 `git rev-parse HEAD`。若 HEAD 变 unborn（本仓库斜杠分支的已知坑）：提交没丢，sha 在 `.git/logs/HEAD`；**不要** `git update-ref`；用

```
mkdir -p .git/refs/heads/feature
printf '%s\n' <sha> > .git/refs/heads/feature/add-performance-deepening-readwrite
git rev-parse HEAD
```

恢复后再核对 sha。

---

## 验收对照（回报里逐条 yes/no）

- [ ] `absence-makeup/spec.md` 含 MODIFIED 缺考标记（两条结束路径）+ 两条 ADDED；补考成绩规则仍在
- [ ] `tasks.json` 每个 `true` 都能指出对应代码位置；看不清的仍是 `false`
- [ ] `listByExam` 额外修复已记入某条 step，无阶段 6
- [ ] README：进行中无 12、归档 15 个、遗留 #2/#3 已收口、#5 仍在、阶段 9/12 状态已改
- [ ] `spec/changes/add-post-exam-closure-e2e/` 已不存在；archive 下有完整目录
- [ ] 未改 `src/**` / `schema.sql` / 其他能力域 spec / 阶段 13–15 提案
- [ ] 只有 1 个 `docs(spec)` commit；`git rev-parse HEAD` 有值

## 不要做

- 不跑测试、不改覆盖率门禁、不重写提案、不把 13/14/15 提前归档
- 不把「补考成绩规则」标成已接线
- 不更新 `docs/指导Agent交接文档.md`
- 不为了勾满去改产品代码

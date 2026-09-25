# 子 agent 提示词 —— `update-agent-gate-single-source` **B-1 段**（工程性变更 E2-B1）

> 用法：整份复制发给子 agent。自包含。
> 先读 `spec/changes/update-agent-gate-single-source/proposal.md`（只做到 B-1 为止）、`tasks.json` 的任务 1–3、`specs/agent-harness/spec-delta.md`。
> **B-2 段（wrapper、`.mvn`/settings 去绝对路径、`frontend/package.json`）不在本次派发范围内，也不得顺手开始**——那部分需等阶段 23 收尾并单独授权。

---

## 本变更要解决的问题（一句话）

仓库里"该跑哪条命令验证"有四种互斥说法、"多少用例算绿"有六个版本，导致按文档执行的 agent 面对一个**不可能满足**的判据。B-1 只做一件事：**把常量换成判据**，并消解同一份文档内部的自相矛盾。

## 本轮实测基线（由指导 agent 在派发时填写；子 agent 不得从任何文档抄数字代替它）

```
命令：<指导 agent 实际执行的门禁命令>
输出：Tests run: <n>, Failures: <n>, Errors: <n>, Skipped: <n>
BUILD：<结果>
revision：<短 sha>
执行时间：<ISO 时间>
```

> 这一节是唯一允许你引用具体数值的地方。它随派发注入，**不要把它写进 `AGENTS.md` / `README.md` / 提示词正文**——判据要引用"跑命令"这个动作，数值落在本阶段 `tasks.json` 的证据字段里。

## 硬边界（违反即返工）

1. 只允许改这些路径：`spec/changes/add-frontend-*/agent-prompt*.md`（5 份进行中）、`spec/README.md`、`docs/指导Agent交接文档.md`、`spec/changes/update-agent-gate-single-source/tasks.json`（**仅**回写任务 1 的证据字段）。
2. **零改动**：`src/**`、`pom.xml`、`.mvn/**`、`maven-settings.xml`、`frontend/**`、`docker-compose.yml`、`schema.sql`、`openapi.yaml`、`docker/**`、`README.md`、`AGENTS.md`（后者属 E1，避免撞车）。
3. **不得新增第二种门禁命令说法**。B-2 未落地前，仓库自有的唯一命令尚不存在；你在文档里只能写"命令待 `update-agent-gate-single-source` B-2 回填"，或引用 `docs/指导Agent交接文档.md` §6.1 并**明确标注它是历史绕行办法、不是推荐命令**。
4. **不得为了让数字吻合而增删改任何测试用例或注解**。这条变更的存在理由就是防止有人去"凑数"。
5. 归档件（`spec/changes/archive/**`）按本项目惯例**不改写**；只在正文里说明它与现行做法不一致即可。
6. 不要 commit，不要勾 `tasks.json` 的 `passes`。

## 任务（对应 `tasks.json` 任务 1–3，一次一个 step）

1. **任务 1**：把上面「本轮实测基线」写进 `spec/changes/update-agent-gate-single-source/tasks.json` 任务 1 的证据字段——含命令、该次输出、短 revision。这是本步唯一交付物，不要复述到别处。
2. **任务 2**：5 份进行中提示词判据化。
   - 删掉每份顶部的「⚠️ 基线更正（以此为准，覆盖下文旧内容）……下文所有「210」一律读作 218」式更正段；
   - 把正文里的「后端全量基线 210 全绿 / 必须仍是 210 / 基线 210，记录数字」统一改写为可执行判据：**跑唯一门禁命令后 `Failures=0` 且 `Errors=0`、`Skipped` 保持 1、用例总数不得少于本阶段开工时实测并记录在案的数值**；
   - 顺带修正同一句里其它被端口/口令污染的表述——但**只能改成"去读哪个文件"，不得改成另一组字面值**（例：某份提示词的更正段写的宿主端口与 `docker-compose.yml` 实际映射不一致时，以"见 compose / application-dev.yml"取代，不要就地改数字）。
3. **任务 3**：消解 `docs/指导Agent交接文档.md` §6.1 与 §6.2 的互斥（同一文档一处说 PATH 的 mvn 不能用、另一处说直接 `mvn` 即可）；把 §6.1 降级为"本机历史坑与当时的绕行办法"，并给 §6.2 加一条指向待定回填位的注记。同时把该文档首屏的基线常量改为判据表述、把旧数值降级为"描述 `<某 sha>` 那次执行"的历史记录。

## 交付回报格式

- 逐文件列出改了哪些行、判据新表述是什么（给路径与要点，不要整篇粘贴）。
- 自查 `grep -rnE '必须仍是 2[0-9]{2}|基线 210|一律读作' spec/` 的**实际输出**（应为空）。
- 自查 `grep -rn "PATH 里的" spec/changes --include=*.md | grep -v archive` 的实际输出（进行中提示词应已无此类命令指引）。
- `git status --short` 与 `git diff --numstat` 原文；一句明确声明未触碰硬边界外路径。
- 每处你认为 proposal/tasks 的描述与仓库实际不符的地方，逐条写出并停下等待判断。

## 停止条件（遇到就回报，不要自作主张）

- 实测基线里出现 `Failures>0` 或 `Errors>0` → **立即停下回报**。判据是"绿才算过"，不是"把红写成设计使然"。
- 实测基线的 `Skipped` 不等于 1，或 `Skipped` 的成因不再是契约导出开关 → 停下回报。
- 用例总数明显低于上阶段记录值（说明有测试被删） → 停下回报，不要继续改写判据。
- 某份进行中提示词正在被执行（该阶段子 agent 在跑）→ 停下回报，等阶段之间再改；在执行中途改验收边界会让"门禁跑在工作区而非 HEAD"这类错误重演。

# 子 agent 提示词 —— `tune-submit-capacity`（交卷链路容量调整与压测复验，P2）

> 用法：整份复制发给子 agent。自包含。
> 先读 `spec/changes/tune-submit-capacity/proposal.md`、`tasks.json`、`specs/performance/spec-delta.md`，再读仓库根 `AGENTS.md`（四条硬约定）与 `docs/submit-loadtest-report.md` §8（G1–G6 判据原文，你的授权边界就是其中 G1/G2/G4/G5 四条——G3 观测补齐是另一份提案，G6 audit_log 又是另一份，都不许顺手做）。
> 本变更属**后端独立立项**：改的是并发运行参数与压测资产，**预期零业务代码改动**；它不在前端阶段 19–23 串行链内，不受「阶段 19–23 一律不改后端」约束，但 `src/main`、`src/test`、`pom.xml` 仍然预期零改动（见写入边界）。

---

## 现状

- 仓库主工作树 `D:\code\examOnline`；**你在独立工作树干活**（指导 agent 建好 `feature/tune-submit-capacity` 分支后通知你路径；开工先 `git rev-parse HEAD` + `git status --short` 自检）。
- 事实基线（以现场复算为准，别信任何文档旧数字）：
  - 既有报告 `docs/submit-loadtest-report.md`：提交 P99 四轮 2088–3700ms **未达标**（目标 < 2s），0 丢单与批量落库 < 30s 达标；根因 = Tomcat 线程上限 200（全仓零配置），容量 ≈455 req/s < 需求 ≈490 req/s。
  - `loadtest/` 资产可复现：`prepare-data.sh` / `run-loadtest.sh` / `analyze-results.py` / `compare-runs.py` / `db/02-metrics.sql` / `04-reset.sql` 复跑复位（复现步骤见报告 §9）。
  - dev 栈（`exam-mysql-master` 13316 / `exam-mysql-slave` 13317 / `exam-rabbitmq`）为共享资源：压测前确认无其它真实环境任务在跑（端口与容器名冲突，见 spec/README 工程惯例）。

## 验收判据（不是常量）

1. **开工基线**：`mvn -o clean test`（必须带 `clean`）跑一次，记录 `Tests run / Failures / Errors / Skipped` + 实际命令 + 短 revision。基线不绿就停下回报（`Skipped: 1` 属设计使然，不要试图消除）。
2. **收尾门禁**：同命令复跑，`Failures=0`、`Errors=0`、`Skipped=1`、总数不少于开工值——本变更不应动任何测试。
3. **达标判定按原指标定义**：P99 < 2s / 0 丢单 / 批量落库 < 30s。不达标不是失败交付——如实登记新瓶颈位与数字同样是合格收口；**改指标定义凑数是违规**。
4. 三要素纪律：报告里每个结论都配「命令 + 该次原始输出 + 当时短 revision」。

## 环境纪律（违反即返工）

1. 压测要打真 dev 栈：先确认容器健康（`docker ps` + 报告 §9 的 mysql 探活方式）；起不来就停下回报，不许拿历史数据当本轮结果。
2. **复跑错峰**：宿主 20 逻辑核是共享资源；开工前向指导 agent 确认没有并行的真实环境任务。
3. 压测数据用 `loadtest/db/04-reset.sql` / `03-cleanup.sql` 复位，不留脏数据污染 dev 库。

## 实施（对应 tasks.json 三个任务）

### 任务 1：方案裁决与注入（G1/G2）

1. G1 裁决：用报告 §4.2 的容量口径（`线程数/服务时长`）量化两条路线（接受均值语义 vs 提高线程上限），把对比数字写进报告，选定一条。**没有数字的裁决按违规处理。**
2. G2 注入：`SERVER_TOMCAT_THREADS_MAX=<选定值>` 经 relaxed binding 注入（运行时环境变量即可；若要在 `docker-compose.yml` 或 `loadtest/` 脚本里固化，改对应注入点，不动 `src/main`）。注入后用 `/actuator/metrics` 或等价手段**现场证明生效**（打印当前线程配置），不凭「应该生效」。
3. 给出改后容量估算与下一个预期瓶颈位（MySQL 写入 / 宿主 CPU）。

### 任务 2：方法学与采样修订（G4/G5）

1. G4：改 `loadtest/` 脚本——每臂 ≥3 轮、每轮前预热、轮间等 TIME_WAIT 排空；结果用 `compare-runs.py` 汇总，取中位数。
2. G5：压测期间持续采样 Hikari `pending/active`（`/actuator/metrics/hikaricp.connections.pending` 等端点轮询，采样间隔写进报告），取峰值而不只是突发后快照。

### 任务 3：复验与登记

1. 用修订后方法学对注入后配置复跑：三项硬指标按原定义判定；结论（达标或不达标+新瓶颈位）写进报告（追加轮次到既有报告或新开 `docs/**` 报告均可，标注清楚）。
2. 性能基线的 spec-delta 合入由指导 agent 验收后做——你不改 `spec/**`。

## 写入边界

允许新增 / 修改：

- `loadtest/**`
- `docker-compose.yml`（仅线程环境变量注入点；不动其它内容）
- `docs/**`（压测报告）

禁止其它路径。**特别禁止**：`src/main/**`、`src/test/**`、`pom.xml`、`application*.yml`（relaxed binding 不需要它们；若发现必须改代码才行的瓶颈，停下回报另立项）、`spec/**`、`frontend/**`。

## Commit

- 分支 `feature/tune-submit-capacity`，**你必须自己 commit**：方法学修订一笔、注入与复验报告一笔（或按逻辑合并为一笔也行），中文描述、前缀 `perf(performance)` / `docs(performance)` / `chore(performance)`。
- 每次提交后立即 `git rev-parse HEAD` + `git status --short`；HEAD 若变 unborn，从 `.git/logs/HEAD` 取 sha 按仓库约定补 ref，**不要用 `git update-ref`**。
- **禁止 `stash` / `reset` / `checkout -- .` / `clean`**。

## 回报格式（按此六段，不要写散文）

1. **开工基线**：门禁命令 + 原始输出四数字 + 短 revision；环境确认（容器健康、错峰确认）
2. **G1 裁决**：两条路线的量化对比数字、选定的路线与理由
3. **G2 注入证明**：注入方式 + 生效证据（现场输出）+ 容量估算与新瓶颈位
4. **G4/G5 修订**：脚本改动点、轮次安排、Hikari 持续采样方式与峰值
5. **复验结果**：每轮原始数据、中位数、三项硬指标判定（按原定义）、新瓶颈位（若不达标）
6. **意外发现**：与本提案假设不符的任何事实

## 禁止

- 禁止改指标定义或压测场景来「达标」；
- 禁止顺手做 G3（观测补齐）或 G6（audit_log）——那是另两份提案的授权；
- 禁止引用文档旧数字当结果——一律现场跑、现场记；
- 禁止勾 `tasks.json` 或动 `spec/**`；
- 禁止压测脏数据残留（跑完复位）。

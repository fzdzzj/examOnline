# 子 agent 提示词 —— `add-submit-observability`（交卷链路观测补齐，G3，P2）

> 用法：整份复制发给子 agent。自包含。
> 先读 `spec/changes/add-submit-observability/proposal.md`、`tasks.json`、`specs/observability/spec-delta.md`，再读仓库根 `AGENTS.md`（四条硬约定）与 `spec/specs/observability/spec.md`（既有基线——你的新增必须与「指标导出」「指标基数有界」对齐，不得重复定义已有 Requirement）。
> 本变更是 `docs/submit-loadtest-report.md` §8.3 判据 **G3** 的执行立项，也是 `tune-submit-capacity` 的前置：G3 落地前容量调整无法被证明有效。
> **属后端独立立项**：改 `application*.yml` 与指标注册代码是本提案明示授权（前端阶段 19–23 的「不改后端」纪律不约束本提案），但写入边界之外仍然禁改。

---

## 现状

- 仓库主工作树 `D:\code\examOnline`；你在独立工作树 `feature/add-submit-observability` 干活（指导 agent 建好后通知路径；开工 `git rev-parse HEAD` + `git status --short` 自检）。
- 事实基线（开工现场复核，别信记忆）：
  - `exam_submit_duration_seconds` 是既有自定义业务指标（`spec/specs/observability/spec.md`「自定义业务指标」Requirement 有其口径）——**沿用该命名与口径，不另起名**。用 `git grep -n "exam_submit_duration" -- src/main` 现场定位注册处。
  - Tomcat 线程指标当前**未暴露**：Spring Boot 需开启 Tomcat MBean registry（或等价 binder）才有 `tomcat.threads.busy` / `tomcat.threads.config.max`。具体配置项以本仓库 Spring Boot 版本的官方文档为准，**不凭记忆写配置**——写完后必须用 `/actuator/metrics` 现场证明。
- dev 栈可起可不起：指标存在性可以本地起应用验证；若需真 dev 栈，遵守共享资源纪律（13316/13317/6379/RabbitMQ 不动，错峰）。

## 验收判据（不是常量）

1. **开工基线**：`mvn -o clean test`（带 `clean`）跑一次，记录四数字 + 命令 + 短 revision；不绿就停（`Skipped: 1` 属设计使然）。
2. **收尾门禁**：同命令，`Failures=0`、`Errors=0`、`Skipped=1`、用例总数**只增不减**（新增测试计入）。
3. **现场证明**：两项指标（线程 busy/max、时延直方图桶）都要有指标端点的原始输出为证——「配置写了」不等于「指标出现了」。
4. **变异验证**：把桶配置/MBean registry 拿掉，指标存在性测试必须变红；恢复后变绿。红绿都要留原始输出。
5. **基数有界**：新增指标标签不得含业务键（考试/用户/题目 ID），与既有「指标基数有界」Requirement 同判。

## 实施（对应 tasks.json 三个任务）

### 任务 1：Tomcat 线程水位

1. 开启 MBean registry（或等价 binder），起应用后 `/actuator/metrics/tomcat.threads.busy` 与 `/actuator/metrics/tomcat.threads.config.max` 现场读取留证。
2. 检查标签维度：无业务键。

### 任务 2：提交时延直方图

1. 为 `exam_submit_duration_seconds` 配直方图（percentiles-histogram 或 SLO 桶，边界覆盖 0.5s/1s/2s/5s 量级），现场读取桶计数留证。
2. 配置落在 `application*.yml` 或代码注册——**先看既有指标是怎么注册的，保持同一风格**。

### 任务 3：测试与面板

1. 新增指标存在性/配置测试（放 `src/test/**`，风格对齐既有 observability 测试）；变异验证见判据 4。
2. Grafana 面板：`git grep -ri grafana -- . --exclude-dir=node_modules` 先查面板资产是否在仓库内；在则补线程水位 + 提交时延最小图，不在则登记「面板资产不在仓库」不阻塞。
3. 观测基线 spec-delta 合入由指导 agent 验收后做——你不改 `spec/**`。

## 写入边界

允许新增 / 修改：

- `src/main/resources/application*.yml`（仅指标相关配置）
- `src/main/**`（仅指标注册/配置代码）
- `src/test/**`（新增测试；不改既有断言）
- Grafana 面板 JSON（若在仓库内）

禁止其它路径。**特别禁止**：`pom.xml`（不加依赖——用 Spring Boot 自带能力；若发现必须加依赖，停下回报）、`schema.sql`、`frontend/**`、`spec/**`、`docker-compose.yml`。

## Commit

- 分支 `feature/add-submit-observability`，**自己 commit**（指标改动 + 测试可一笔，中文描述，前缀 `feat(observability)` / `test(observability)`）。
- 每次提交后 `git rev-parse HEAD` + `git status --short`；HEAD 若变 unborn，从 `.git/logs/HEAD` 取 sha 补 ref，**不要用 `git update-ref`**。
- **禁止 `stash` / `reset` / `checkout -- .` / `clean`**。

## 回报格式（按此六段，不要写散文）

1. **开工基线**：门禁四数字 + 命令 + 短 revision
2. **线程指标证据**：配置方式 + `/actuator/metrics` 原始输出
3. **直方图证据**：桶配置 + 桶计数原始输出
4. **变异验证**：撤配置→测试红的原始输出；恢复→绿
5. **收尾门禁**：四数字（总数较开工只增不减）
6. **意外发现**：版本差异、配置不生效的原因等

## 禁止

- 禁止只写配置不留端点原始输出（「写了」≠「生效了」）；
- 禁止给指标加业务键标签；
- 禁止改 `exam_submit_duration_seconds` 既有口径或另起新指标名；
- 禁止加 Maven 依赖；
- 禁止勾 `tasks.json` 或动 `spec/**`。

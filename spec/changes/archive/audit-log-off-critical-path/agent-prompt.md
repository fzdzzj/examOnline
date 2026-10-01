# 子 agent 提示词 —— `audit-log-off-critical-path`（审计日志移出请求关键路径，G6，P2）

> 用法：整份复制发给子 agent。自包含。
> 先读 `spec/changes/audit-log-off-critical-path/proposal.md`、`tasks.json`、`specs/performance/spec-delta.md`，再读仓库根 `AGENTS.md`（四条硬约定——本变更若涉及新列/索引，建表唯一入口是 `src/main/resources/schema.sql`，存量库走 `docker/mysql/migrations/` 且**没有自动执行者**）与 `spec/specs/data-access/spec.md`（保留策略查重对象）。
> 本变更是 `docs/submit-loadtest-report.md` §8.3 判据 **G6** 的执行立项。**门控提案**：任务 1 的评估结论若是「不移出」，如实关闭并记录就是合格交付——硬改反而违规。
> **属后端独立立项**：改 `src/main` 是明示授权（不受前端阶段纪律约束），但仅限审计路径。

---

## 现状

- 仓库主工作树 `D:\code\examOnline`；你在独立工作树 `feature/audit-log-off-critical-path` 干活（指导 agent 建好后通知路径；开工 `git rev-parse HEAD` + `git status --short` 自检）。
- 你要先现场盘点的事实（开工第一步，产出进回报）：
  - `git grep -n "audit" -- src/main` 定位审计写入的类、调用点、表实体；
  - 审计写的**失败语义**：当前审计写失败时业务是回滚、还是 catch 吞掉、还是同事务回滚——这决定异步化的补偿设计；
  - `spec/specs/data-access/spec.md` 的「数据保留策略」等 Requirement 是否已覆盖 audit_log。
- 判据出处：报告 §7.1（audit_log 同步写在关键路径的疑点）与 §8.3 G6。

## 验收判据（不是常量）

1. **开工基线**：`mvn -o clean test`（带 `clean`），四数字 + 命令 + 短 revision；不绿就停（`Skipped: 1` 属设计使然）。
2. **收尾门禁**：同命令，`Failures=0`、`Errors=0`、`Skipped=1`、总数只增不减。
3. **评估必须有数字**：移出收益以实测对比（含/不含审计写、或移出前后）支撑，三要素纪律。
4. **变异验证**：补偿分支的测试撤掉补偿实现时必须变红（证明测试有强度）。

## 实施（对应 tasks.json 三个任务）

### 任务 1：评估（门控步）

1. 盘点调用点与失败语义（见「现状」），写入回报第 2 段。
2. 测同步审计写的时延代价（微基准或复用 `loadtest/` 资产，dev 栈错峰；数据跑完复位）。
3. 裁决：收益数字 + 失败语义 + 保留策略查重结果 → 移出 / 不移出。**不移出**：把数字与结论写成回报，提案由指导 agent 关闭，你不动 `src/main`。

### 任务 2：实施（仅裁决=移出）

1. 按仓库既有异步风格（先看 RabbitMQ/事件/`@Async` 哪种在本仓有先例——`git grep -n "@Async\|ApplicationEvent" -- src/main` 现场看）移出业务事务的同步等待。
2. 失败补偿：不静默丢（重试/暂存/死信；与 `spec/specs/reliability/spec.md` 既有异步判据对齐，适用则引用）。
3. 集成测试：异步化后审计仍落库 + 补偿分支覆盖（变异验证见判据 4）。
4. 延迟窗口如实声明（代码注释与文档都说「最终一致」，不说「即时」）。

### 任务 3：保留策略与性能证据

1. 保留策略：data-access 基线已覆盖则引用不补；未覆盖且确需清理才另拟 delta（交指导 agent 复核，**你不改 `spec/**`**）；若需新列/索引：改 `schema.sql`（唯一建表入口，H2/MySQL 双兼容）+ `docker/mysql/migrations/**` 迁移脚本（头注写明预期报错处理，**没有自动执行者**——存量库须人工按序执行，回报里不得声称「迁移已生效」）。
2. 移出前后关键路径时延对比证据（三要素）。

## 写入边界

允许新增 / 修改：

- `src/main/**`（仅审计写入路径与其异步化）
- `src/test/**`（新增测试；不改既有断言）
- `src/main/resources/schema.sql` + `docker/mysql/migrations/**`（仅保留策略裁决需要时）
- `docs/**`（评估/对比报告）

禁止其它路径。**特别禁止**：`pom.xml`（不加依赖）、`frontend/**`、`spec/**`、`docker-compose.yml`、非审计路径的业务逻辑方法体。

## Commit

- 分支 `feature/audit-log-off-critical-path`，**自己 commit**：评估报告一笔；实施（若做）代码+测试一笔、迁移（若有）一笔。中文描述，前缀 `perf(performance)` / `feat(performance)` / `test(performance)` / `docs(performance)`。
- 每次提交后 `git rev-parse HEAD` + `git status --short`；HEAD 若变 unborn，从 `.git/logs/HEAD` 取 sha 补 ref，**不要用 `git update-ref`**。
- **禁止 `stash` / `reset` / `checkout -- .` / `clean`**。

## 回报格式（按此六段，不要写散文）

1. **开工基线**：门禁四数字 + 命令 + 短 revision
2. **盘点结果**：审计调用点清单、失败语义、data-access 查重结论
3. **评估数字**：同步审计写的时延代价（怎么测的 + 原始输出）+ 裁决及依据
4. **实施证据**（若做）：异步化方式、补偿分支、测试（含变异验证红绿输出）
5. **收尾门禁**：四数字（总数只增不减）
6. **意外发现**：失败语义与预期不符、既有补偿已存在等

## 禁止

- 禁止跳过评估直接改（门控提案，评估先行）；
- 禁止静默丢审计——补偿分支缺失的异步化按返工处理；
- 禁止在 data-access 已覆盖处重复定义保留策略；
- 禁止把「迁移脚本已提交」当「迁移已生效」回报（AGENTS.md 约定 4）；
- 禁止勾 `tasks.json` 或动 `spec/**`。

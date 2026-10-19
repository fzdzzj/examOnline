# 证据索引：add-score-chain-reconciliation（阶段 2/3 实施与验证）

> **变更**：`add-score-chain-reconciliation`（成绩链全链路对账：两维度对账 + 差异票闭环 + 确定性缺失幂等补齐 + 定时兜底）
> **分支**：`feature/add-score-chain-reconciliation`（自 `main@5733d88` 创建，立案笔 `ff72bd6`）
> **实施笔**：`da8544f`（数据层）→ `ede9a99`（引擎与四端点）→ `86cfebb`（契约/SDK/前端）→ `9b6bfd3`（测试）→ 本证据与基线笔
> **能力域**：`reliability`（既有域追加 Requirement，diff 见 `spec/changes/add-score-chain-reconciliation/specs/reliability/spec-delta.md`）

## 1. 留证清单（20 项 + 索引 + 清单）

| 文件 | 内容 |
|---|---|
| `tdd-red-phase.txt` | 先红：实现包移出源码树（等价「未接线」）后执行新增后端用例 → 编译级红（`程序包 com.exam.reliability...不存在`，BUILD FAILURE） |
| `tdd-green-phase.txt` | 回绿：新增后端集成测试 **8/8** 全绿，`BUILD SUCCESS`，并列出 8 例清单 |
| `tdd-green-phase-frontend.txt` | 回绿：新增前端 spec **15/15** 全绿；全量 128 文件 / 980 例 |
| `mutation-m1-report.txt` / `-red.txt` / `-revert-green.txt` | m1 抽除维度一「未判分答卷检测」（`gradingMissing` 条件恒假）：击红 1 例（`ungradedSubmissionProducesTicket` expected 1 but was 0）；字节级复原后 8/8 回绿 |
| `mutation-m2-report.txt` / `-red.txt` / `-revert-green.txt` | m2 抽除确定性补齐幂等护栏（`WHERE total_score IS NULL`）：击红 4 例（重复补齐 autoFixed 变 2、涉分总分被改写致 mismatch 漏报、关闭用例 NPE）；复原后 8/8 回绿 |
| `mutation-m3-report.txt` / `-red.txt` / `-revert-green.txt` | m3 抽除差异票分类计数/汇总（runs 三类差异数恒 0）：击红 3 例（维度一/维度二计数断言）；复原后 8/8 回绿 |
| `gate-backend.txt` | 仓库根 `./mvnw.cmd clean test` 全量实测：`Tests run: 853, Failures: 0, Errors: 0, Skipped: 1` + `BUILD SUCCESS`（基线 845 → 853，+8） |
| `gate-frontend-test.txt` | `pnpm test` 全量实测：`Test Files 128 passed`、`Tests 980 passed (980)`（基线 965 → 980，+15） |
| `gate-frontend-lint.txt` / `gate-frontend-typecheck.txt` | `pnpm lint:check` 无输出退出 0；`pnpm type-check:check` 双 banner（vue-tsc + tsc）无错误 |
| `ci-gate-assert.txt` | 真实 `.github/ci/*.sh` 三机检等价执行（Git Bash）：后端/前端/契约三断言均 OK，输入为上调后的 gate-baseline |
| `gate-baseline.txt` | `gate-baseline.json` 同笔上调 845→853、965→980、110→114（只增不减），附 diff |
| `contract-paths.txt` | `openapi.yaml` paths **110 → 114**，四条新路径与 operationId、8 个新 schema、SDK 重生口径 |
| `redline-selfcheck.txt` | AGENTS 四条硬约定 + 本案红线自查：flyway 0、`db/` 不存在、`@Sql` 注解 0、实体表差集空、migrations 无自动执行者、reconciliation 包零 Redis API、判分口径同源入口、两表双文件同形、迁移台账 19/19 |
| `evidence-sha256.txt` | 以上留证 + 本索引的 SHA256 清单（`sha256sum -c` 格式，LF 行尾） |

## 2. 变异（断言级击红 → 字节级复原 → 回绿零残留）

三个变异都落在**生产代码的单一行为**上，不是测试侧特判：

- **m1**：`ReconciliationService` 维度一判分缺失判定改为恒假（漏报）→ 未判分答卷不再出票；
- **m2**：`GradingSubmissionMapper.casBackfillTotalScore` 去掉 `AND total_score IS NULL` 幂等护栏
  → 每次对账都重写总分（version 递增、审计重复、涉分总分被"自动修正"）；
- **m3**：`ReconciliationService.finish(...)` 的三类差异数在内存返回与 DB 回填处同时恒 0
  → runs 表三类差异数失真。

每次均记录 `git diff` 注入内容（`mutation-mN-report.txt`）、击红日志（`mutation-mN-red.txt`）、
复原后 SHA256/cmp 一致与回绿日志（`mutation-mN-revert-green.txt`）。复原用**原始字节副本**回写
（`scripts/mutation-check.sh` 的 cp + cmp），不用 `git checkout`，避免 `core.autocrlf` 行尾差异。

> 注：`scripts/mutation-check.sh` 的留证目录与变异输入属 `target/` 下临时产物（`mvn clean` 会清除），
> 证据本体即本目录文件；report 里记录的 `target/mutation-check/<label>/…` 只是工具的落盘路径。

## 3. 门禁数字链（只增不减，本笔同笔上调）

| 项 | 基线 | 本笔实测 | 说明 |
|---|---|---|---|
| `backendTests` | 845 | **853** | +8：`ScoreChainReconciliationIntegrationTest` |
| `frontendTests` | 965 | **980** | +15：`reconciliationAdmin.spec.ts` |
| `openapiPaths` | 110 | **114** | run / runs / diffs / close 四端点 |

`.github/ci/gate-baseline.json` 三项同笔上调；CI 断言语义为 `>=`（只增不减），
三机检脚本用新基线跑通（`ci-gate-assert.txt`）。

## 4. 契约与 SDK

- `openapi.yaml` 由 `OpenApiContractTest#exportOpenApiContract`（`-DexportContract=true`）真实导出后，
  **只摘取本案新增的 4 个 path 块与 8 个 schema 块**插入入库文件（避免 springdoc 无关顺序漂移，
  diff 为 +267 行 / 0 删除）；
- SDK 由 `pnpm gen:api` 从该契约重生（`sdk.gen.ts` / `types.gen.ts` / `index.ts`），前端零手写契约类；
- operationId 取方法名：`runReconciliation` / `reconciliationRuns` / `reconciliationDiffs` /
  `closeReconciliationDiff`，避开 `run` 这类与既有 operation 撞名的泛化名。

## 5. 数据与迁移状态（如实登记）

- `schema.sql` 新增 `reconciliation_runs`（15 列 + `uk_recon_run_batch` + `idx_recon_run_exam`）与
  `reconciliation_diffs`（15 列 + `uk_recon_diff_fingerprint` + `idx_recon_diff_run` +
  `idx_recon_diff_status`），AUTO_INCREMENT 块内均显式 `PRIMARY KEY (id)`，H2/MySQL 双兼容；
- 迁移脚本 `docker/mysql/migrations/2026-W44-add-score-chain-reconciliation.sql` 与 `schema.sql` 同形；
- `APPLIED.md` 登记 **未应用（待人工应用）**：入库时 `docker ps` 无容器在跑，未对 dev 库执行；
  按目录纪律「脚本入库 ≠ 迁移已生效」，应用后须按 A3/A1/A2/A4 核验并回填台账；
- 集成测试全程零 `@Sql`、零自建表（H2 由 `schema.sql` 建表），新表在真实链路上被写入/读取
  （运行记录、差异票、补齐后的总分与审计）。

## 6. 红线自查（详见 `redline-selfcheck.txt`）

- `pom.xml` 无 flyway；`src/main/resources/db` 不存在；建表唯一入口 `schema.sql`；
- `src/test` 内 `@Sql` 注解命中 0（注释复述不算）；
- 实体 `@TableName` 集合 − `schema.sql` 建表集合 = 空；
- `docker-compose.yml` 未挂载 `migrations/`（无自动执行者）；
- reconciliation 包零 Redis API 调用（对账状态全落 DB，零新 Redis 键族）；
- 总分期望值只有 `ScoreService.computeSummary` 一个公式（对账经 `expectedSummaries` 复用）；
  补齐写入复用同 SET 形状的 CAS，仅多幂等护栏。

## 7. 复核入口

```bash
# 后端门禁（仓库根；Windows 用 ./mvnw.cmd clean test）
./mvnw clean test

# 前端三件套
cd frontend && pnpm lint:check && pnpm type-check:check && pnpm test

# 证据自校验（Git Bash）
cd spec/changes/add-score-chain-reconciliation/evidence && sha256sum -c evidence-sha256.txt
```

## 8. 已知边界

- 维度一「主观题未批完」按 §7.5「未批按 0 分计入总分」的既有口径只出票不自动补批（人工处置），
  这是**有意的**：补批属改分动作，落在负范围「不自动改分」；
- 定时批次以时间窗取整实现「同窗单飞」，窗口外的重复扫描由差异票唯一指纹与补齐条件更新兜底
  （与 reliability 域「正确性由幂等保证、锁仅省资源」同口径，不引入 Redis 键族）；
- 对账端点对教师与管理员开放（`exam:manage`），前端页面挂在管理端导航；权限边界在后端注解，
  前端菜单过滤只是体验层。

# 证据索引：add-irt-adaptive-testing（阶段 2/3 实施与验证）

> **变更**：`add-irt-adaptive-testing`（IRT 三参数标定 + CAT 自适应测验）
> **分支**：`feature/add-irt-adaptive-testing`（自 `main@c75fe5e` 创建，立案笔 `8250b71`）
> **实施笔**：`85365ae`（数据层）→ `1168808`（引擎与三端点）→ `b017744`（后端用例）→ `2f6f6b7`（契约/SDK/前端）→ 本证据与基线笔（见 `git log`）

## 1. 留证清单（12+）

| 文件 | 内容 |
|---|---|
| `tdd-red-phase.txt` | 先红：实现类移出源码树（等价“未接线”）后执行新增后端用例 → 编译级红（`程序包 com.exam.adaptive.service 不存在`） |
| `tdd-green-phase.txt` | 回绿：4 个新增后端测试类 **29/29** 全绿（单测 24 + 集成 5），`BUILD SUCCESS` |
| `tdd-green-phase-frontend.txt` | 回绿：新增前端 2 个 spec **5/5** 全绿 |
| `mutation-m1-report.txt` / `mutation-m1-red.txt` / `mutation-m1-revert-green.txt` | m1 抽除 θ 更新（`updateTheta` 恒返回先验）：击红 2 例（`correctAnswerRaisesThetaAndPersistsAttempt`、`wrongAnswerLowersTheta`）、字节级复原（SHA256 `237BF656…971E9`）后 12/12 回绿 |
| `mutation-m2-report.txt` / `mutation-m2-red.txt` / `mutation-m2-revert-green.txt` | m2 抽除最大信息量选题（退化为题池顺序取题）：击红 `nextItemMaximizesInformationAndExcludesAnswered`（`expected: <102> but was: <103>`）、字节级复原后 12/12 回绿 |
| `mutation-m3-report.txt` / `mutation-m3-red.txt` / `mutation-m3-revert-green.txt` | m3 抽除终止规则（`terminationReasonOf` 恒 null）：击红 `terminatesAtMaxItems` 与 `terminatesWhenSeConverges`、字节级复原后 12/12 回绿 |
| `gate-backend.txt` | 仓库根 `./mvnw.cmd clean test` 全量实测：`Tests run: 845, Failures: 0, Errors: 0, Skipped: 1` + `BUILD SUCCESS`（基线 816 → 845，+29） |
| `gate-frontend-test.txt` | `pnpm test` 全量实测：`Test Files 127 passed`、`Tests 965 passed (965)`（基线 960 → 965，+5） |
| `gate-frontend-lint.txt` / `gate-frontend-typecheck.txt` | `pnpm lint:check` 退出 0；`pnpm type-check:check` 双 banner（vue-tsc + tsc）退出 0 |
| `ci-gate-assert.txt` | CI 三门禁机检的等价复核（本机无 bash/WSL，按 `.github/ci/*.sh` 断言逐条以 PowerShell 等价执行，全 True） |
| `contract-paths.txt` | `openapi.yaml` paths **107 → 110**，三条新路径与 operationId（`startAdaptiveQuiz` / `submitAdaptiveQuizAnswer` / `getAdaptiveQuizReport`）与 10 个 schema |
| `redline-selfcheck.txt` | AGENTS 四条硬约定 + 本案红线自查：Flyway 0 命中、`src/main/resources/db` 不存在、`@Sql` 注解 0 命中、实体表集合差为空、migrations 无自动执行者、adaptive 包零 Redis API（仅注释提及）、判分同源入口 |
| `evidence-sha256.txt` | 以上留证的 SHA256 清单（`sha256sum -c` 格式） |

## 2. 变异（断言级击红 → 字节级复原 → 回绿零残留）

三个变异点都是**生产代码中的单一方法**，不是测试侧特判：

- **m1**：`IrtCatService.updateTheta(...)` 改为恒返回先验 `Estimate(0, 1)`；
- **m2**：`IrtCatService.selectNextItem(...)` 前置“取题池第一个未作答题”的短路，跳过 Fisher 信息量比较；
- **m3**：`IrtCatService.terminationReasonOf(...)` 前置恒 `return null`。

每次变异均记录 `git diff`（`mutation-mN-report.txt`）、击红日志（`mutation-mN-red.txt`）、复原后 SHA256 与回绿日志（`mutation-mN-revert-green.txt`）。复原用**原始字节副本**回写（非 `git checkout`，避免 core.autocrlf 行尾差异），SHA256 与变异前逐字节一致。

## 3. 门禁数字链（只增不减，本笔同笔上调）

| 项 | 基线 | 本案实测 | 说明 |
|---|---|---|---|
| `backendTests` | 816 | **845** | +29：`IrtEstimatorTest` 4 + `IrtParamServiceTest` 8 + `IrtCatServiceTest` 12 + `AdaptiveQuizIntegrationTest` 5 |
| `frontendTests` | 960 | **965** | +5：`AdaptiveQuizPanel.spec.ts` 4 + `adaptiveQuizEntry.spec.ts` 1 |
| `openapiPaths` | 107 | **110** | start / answer / report 三端点 |

`.github/ci/gate-baseline.json` 三项同笔上调；CI 断言为 `>=` 语义（只增不减），等价复核见 `ci-gate-assert.txt`。

**关于一次作废的前端红**：首次全量前端测试与 `mvnw clean test` 并发执行，12 个**与本案无关**的既有用例因 CPU 争用超时（单例 5~9s 超 5s 默认超时）。后端门禁独占结束后独占重跑，`127/127` 文件、`965/965` 例全绿，作废的并发日志未入库；本目录 `gate-frontend-test.txt` 为独占复跑的权威产物。

## 4. 契约与 SDK

- `openapi.yaml` 由 `OpenApiContractTest#exportOpenApiContract`（`-DexportContract=true`）真实导出后，**只摘取本案新增的 3 个 path 块与 10 个 schema 块**插入既有文件，避免把 springdoc 的无关路径/顺序漂移混入本笔（diff 仅 +236 行、0 删除）；
- SDK 由 `pnpm gen:api` 从该契约重生（`src/api/axios/{sdk,types}.gen.ts` + `index.ts`），本笔无手写契约类型；
- `operationId` 取方法名（`startAdaptiveQuiz` / `submitAdaptiveQuizAnswer` / `getAdaptiveQuizReport`），避开 `start`/`answer`/`report` 这类会与既有 operation 撞名的泛化名。

## 5. 数据与迁移状态（如实登记）

- `schema.sql` 新增 `question_irt_params`（11 列 + `uk_irt_question` + `PRIMARY KEY (id)`）与 `cat_quiz_sessions`（15 列 + `idx_cat_session_student` + `PRIMARY KEY (id)`），AUTO_INCREMENT 块内显式主键，H2/MySQL 双兼容；
- 迁移脚本 `docker/mysql/migrations/2026-W43-add-irt-adaptive-testing.sql` 与 `schema.sql` 逐字同形；
- `APPLIED.md` 登记 **未应用（待人工应用）**：入库时 `docker ps` 无容器在跑，未对 dev 库执行；按目录纪律「脚本入库 ≠ 迁移已生效」，应用后需按 A3/A1/A2/A4 核验并回填台账；
- 集成测试全程不使用 `@Sql` 自建表（H2 由 `schema.sql` 建表），新表在真实链路上被写入/读取（开局落会话、作答落 `practice_attempt`、报告回读轨迹）。

## 6. 红线自查（详见 `redline-selfcheck.txt`）

- `pom.xml` 无 flyway；`src/main/resources/db` 不存在；建表唯一入口为 `schema.sql`；
- `src/test` 内 `@Sql` 注解命中 0；
- 实体 `@TableName` 集合 − `schema.sql` 建表集合 = 空；
- `docker-compose.yml` 无 `migrations` 挂载（无自动执行者）；
- `com.exam.adaptive` 包内 Redis 命中仅为注释（“零新 Redis 键族”口径说明），无任何 Redis API 调用；
- CAT 作答判分只走 `GradingStrategyRegistry.dispatch(...).grade(...)`，逐题落既有 `practice_attempt`。

## 7. 复核入口

```bash
# 后端门禁（仓库根）
./mvnw.cmd clean test          # Windows；bash 下 ./mvnw clean test
# 前端三件套
cd frontend && pnpm lint:check && pnpm type-check:check && pnpm test
# 证据自校验
cd spec/changes/add-irt-adaptive-testing/evidence && sha256sum -c evidence-sha256.txt
```

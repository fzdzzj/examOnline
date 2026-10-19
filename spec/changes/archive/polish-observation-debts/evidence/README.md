# 证据索引：polish-observation-debts（阶段 2 实施 / 阶段 3 TDD·变异·门禁）

> **变更**：`polish-observation-debts`（T 级打磨：观察项①对账补例显性化 + vitest 超时预算固化 + 归档速览瘦身）
> **分支**：`feature/polish-observation-debts`（自 `main@0fc7563` 创建，立案笔 `0c5b86e`）
> **实施笔**：补例 `3cfcd42` → vitest 配置 `8a3a7ed` → 归档速览瘦身 `0e515d7` → 本证据/基线与 `tasks.json` 回填笔（本提交；实测 revision 记在各 evidence 文件头部）
> **能力域**：`reliability`（对账补齐分支补例）、`agent-harness`（前端单测超时预算）

## 1. 留证清单（20 项留证 + 本索引 + sha256 清单）

| 文件 | 内容 |
|---|---|
| `tdd-red-phase.txt` | TDD 先红：新增 2 例在目标行为缺席（m1 注入＝未接线）时断言级失败 2 例（`partialGradedBackfillsObjectiveOnlyTotal:190`、`partialGradedBackfillIsIdempotent:235`），其余 8 例不受影响 |
| `tdd-green-phase.txt` | 字节级复原后整类 **10/10** 全绿（新增 2 + 既有 8） |
| `mutation-m1-report.txt` / `-red.txt` / `-revert-green.txt` | m1 抽除「主观未批完但客观已判」分部总分写入（补齐调用被 `partialGraded()` 三元短路）：击红 **2 例**（两例新例的 `autoFixedCount`/总分断言）；复原后 10/10 回绿 |
| `mutation-m2-report.txt` / `-red.txt` / `-revert-green.txt` | m2 抽除 `SUBJECTIVE_NOT_FULLY_GRADED` 出票（出票分支恒假、维度一计数保留）：击红 **3 例**（两新例 + 既有 `detailTotalMismatchOnlyTickets`「partial 票为 null」）；复原后 10/10 回绿 |
| `mutation-m3-report.txt` / `-red.txt` / `-revert-green.txt` | m3 抽除补齐 CAS 的 `total_score IS NULL` 幂等护栏：击红 **4 例 + 1 ERROR**（含新例 `partialGradedBackfillIsIdempotent`「总分已回填，不得二次补齐 expected 0 but was 1」、既有 `deterministicMissing...`、定时单飞审计计数、涉分总分被自动改写、关闭用例 NPE）；复原后 10/10 回绿 |
| `gate-backend.txt` | 仓库根 `./mvnw.cmd clean test` 全量实测：`Tests run: 855, Failures: 0, Errors: 0, Skipped: 1` + `BUILD SUCCESS`（基线 853 → 855，+2），含原始日志 sha256 与退出码 |
| `gate-frontend-test.txt` | `pnpm test` 全量实测：`Test Files 128 passed (128)`、`Tests 980 passed (980)`（基线 980 持平，配置改动零增删用例） |
| `gate-frontend-lint.txt` / `gate-frontend-typecheck.txt` | `pnpm lint:check` 退出 0（无输出）；`pnpm type-check:check` 双真实 banner（vue-tsc + tsc）退出 0 |
| `ci-gate-assert.txt` | 真实 `.github/ci/*.sh` 三机检等价执行：后端/前端/契约三断言均 OK（输入为上调后的 gate-baseline） |
| `gate-baseline.txt` | `gate-baseline.json` 同笔**只调 backendTests**（853→855），frontendTests 980 / openapiPaths 114 逐字未动，附 diff |
| `contract-paths.txt` | 零契约核验：`paths=114`（基线 114 持平），`openapi.yaml`/`src/main`/前端 SDK 零改动 |
| `archive-digest-coverage.md` | 归档速览 56 条**逐条覆盖校验结论**（KEEP 10 / MOVED 41 / ALREADY-IN-ARCHIVE 5，含原行与新行号） |
| `archive-digest-verify.txt` | 瘦身复核：56 条去重 → 速览 10 + 冷存储新增 41，lost=0；41 条搬移行与 HEAD 速览原件**逐字节一致**（74627 字节）；速览区 103537 → 15917 字节（-84.63%） |
| `redline-selfcheck.txt` | AGENTS 四条硬约定 + 派工卡三红线自查：flyway 0、`db/` 不存在、`@Sql` 注解 0、实体表差集空、migrations 无自动执行者、schema/openapi/pom/migrations/frontend-src 零改动、`src/main` 变异复原零残留、证据全 LF |
| `evidence-sha256.txt` | 以上留证 + 本索引的 SHA256 清单（`sha256sum -c` 格式，LF 行尾） |

## 2. 用例与规范锚点

新增 2 例（`ScoreChainReconciliationIntegrationTest`，Mapper 直插夹具、零 `@Sql`）：

1. `partialGradedBackfillsObjectiveOnlyTotal` —— 客观已判（5 分）+ 简答未批（`score=null`）+ `partial_graded=1` + `total_score` 未回填，对账后断言：
   `autoFixedCount=1`、`mismatchCount=0`、OPEN 票 `SUBJECTIVE_NOT_FULLY_GRADED`、AUTO_FIXED 票 `TOTAL_SCORE_NOT_FILLED`、
   `total_score=5`（与 `computeSummary` 同源：客观 5 + 未批按 0）、`subjective_score=0`、`partial_graded=1` 保留、
   `version=1`、审计 1 条、主观批改行 `score` 仍为 `null`（**零主观分写入**）。
2. `partialGradedBackfillIsIdempotent` —— 重复对账：`autoFixedCount=0`、`version=1`、审计 1 条、
   AUTO_FIXED/OPEN 票各 1 张（唯一指纹抑制重复出票），且 `partial_graded` 不被洗掉。

规范锚点：`spec/changes/polish-observation-debts/specs/reliability/spec-delta.md`「主观未批完但客观已判的分部总分补齐」。

## 3. 变异（断言级击红 → 字节级复原 → 回绿零残留）

三变异均由 `scripts/mutation-check.sh`（真实 Git Bash 驱动，五步原子流程）实测，**未用 `git checkout`**：
复原靠「原始字节副本 cp + cmp」与「注入标志不在场」双判据，`mutation-mN-report.txt` 为工具原样 report。

| 变异 | 抽除点 | 注入表达式 | 击红 |
|---|---|---|---|
| m1 | 分部总分写入 | `s\|boolean written = scoreService\|boolean written = summary.partialGraded() ? false : scoreService\|` | 2 例（F=2） |
| m2 | `SUBJECTIVE_NOT_FULLY_GRADED` 出票 | `s\|if (summary.partialGraded()) {\|if (summary.partialGraded()) { totals.ungraded++; } if (false) {\|` | 3 例（F=3） |
| m3 | CAS `total_score IS NULL` 护栏 | `s\|AND total_score IS NULL\|AND 1 = 1 /*m3-guard-removed*/\|` | 4 例 + 1 ERROR |

**过程偏差如实登记（不掩盖）**：m1/m2 首轮注入写成 `!summary.partialGraded() && …` / `… && false`，
`mutation-check.sh` 以「替换侧即注入标志」做在场核验，`sed` 侧 `\&\&` 转义使标志串带反斜杠 →
工具判「注入未生效」并**自动复原**（fail 路径生效：`git status --porcelain -- src/main` 为 0，工作树零残留）。
改用不含 `&&` 的等价注入（三元短路 / `if (false)` 包住出票）后五步全 PASS，未放宽任何判据。
这也说明「先红」不是伪造：红来自目标行为真的缺席，绿来自字节级复原。

## 4. 门禁数字链（只增不减，本笔同笔上调）

| 项 | 基线 | 本笔实测 | 说明 |
|---|---|---|---|
| `backendTests` | 853 | **855** | +2：`ScoreChainReconciliationIntegrationTest` 8 → 10 |
| `frontendTests` | 980 | **980** | 持平：只加 `testTimeout: 30000`，不增删用例 |
| `openapiPaths` | 114 | **114** | 持平：零契约变更 |

`.github/ci/gate-baseline.json` 本笔**只把 backendTests 853→855**；CI 断言语义为 `>=`（只增不减），
三机检脚本用新基线跑通（`ci-gate-assert.txt`）。

## 5. 归档速览瘦身（防丢档前置，结论留证）

- 校验先行：速览 56 条去重条目逐条对照 `spec/CHANGES_ARCHIVE.md` → **5 条**有等价条目、**41 条零命中**；
- 冷存储缺档根因：该文件最后更新是 `f1c42d8`（2026-10-14 冷热分离立案笔），10-15 起归档的变更从未回填；
- 处置（用户当轮裁决）：41 条**原样移入**冷存储归档表末尾（同表头四列，按归档日期升序），再仅保留最近 10 条；
- 零丢档：41 行逐字节一致、`lost entries = 0`、冷存储无重复 change id（`archive-digest-verify.txt`）；
- 顺带清理：2 个历史快照块表头、4 行旧表格形态的能力域残留行（其内容在「已合入规范」表完整在场）、
  表头补「完整台账以 CHANGES_ARCHIVE.md 为准」指针，并修正两处 `find` 被吞首字母的笔误。

## 6. 复核入口

```bash
# 后端门禁（仓库根；Windows 用 ./mvnw.cmd clean test）
./mvnw clean test

# 前端三件套
cd frontend && pnpm lint:check && pnpm type-check:check && pnpm test

# 证据自校验（Git Bash）
cd spec/changes/polish-observation-debts/evidence && sha256sum -c evidence-sha256.txt

# 归档速览瘦身复核（HEAD 速览 vs 现行速览 + 冷存储）
git show HEAD:spec/README.md | sed -n '56,134p' | grep -cE '^\| `[a-z0-9-]+` \|'   # 58 行 / 56 条去重
grep -cE '^\| `[a-z0-9-]+` \|' spec/CHANGES_ARCHIVE.md                              # 152 = 111 + 41
```

## 7. 已知边界

- 本笔是「补例 + 显性化」，不改对账口径：补齐仍只写客观汇总、仍不出自动补批（补批属改分，落负范围）；
- `testTimeout` 只治宿主 CPU 争用下的抖动，不掩盖慢用例——真正超 30s 的用例仍会失败；
- 归档速览瘦身属文档整理，无行为变更，故 reliability/agent-harness 的 spec-delta 不含它的 Scenario；
- 冷存储回填以「速览原行为唯一事实源」，未重写任何历史条目内容（仅移动），避免制造第四份副本。

# 提案：CI 门禁流水线（GitHub Actions 机检化双端门禁，生产化 P1，CI-only）

> 状态：已立项待执行；阶段 0 查证基于 main `e71a6fa`（2026-10-11）现场重核。台账 `docs/production-hardening-candidates.md` 候选 P1 定性核对属实：仓库零 CI 痈件、纪律靠 agent/人工自觉。范围按用户裁决收窄为 **CI-only**——CD 显式排除，见 Impact 非目标。

## Why

- **现状（`e71a6fa` 实测）**：remote origin = `github.com:fzdzzj/examOnline`；仓库无任何 CI 痈件（`.github/`、`.gitlab-ci*`、`Jenkinsfile`、`.circleci/`、`azure-pipelines*` 均不存在），git log 全历史无 CI 尝试。唯一门禁是本机实跑（后端 `mvnw.cmd clean test` + 前端 `npm.cmd` 三门禁），「surefire 用例数只增不减、输出非空核验、OpenAPI 契约门槛」全靠 agent/人工自觉；本机是 Windows，「门禁在任何环境可复跑」只能靠新克隆手跑复核，无自动防线。
- **CI 可行性前置已逐一核证（`e71a6fa` 现场实测）**：
  1. 构建配置机器中立：`.mvn/maven.config` → `${maven.multiModuleProjectDirectory}/maven-settings.xml` → localRepository 指仓库内 `.m2-repo`（缺失时 Maven 联网重建）——CI runner 直接可跑，与 agent-harness spec「构建配置的机器绑定不得进入版本库」天然兼容；
  2. 测试外部依赖（`src/test/resources/application-test.yml`）：MySQL→H2 内存（主从同库）、RabbitMQ→监听 `auto-startup: false` + 健康检查禁用、OTel→none、mail→空，**唯一硬依赖是 Redis**（`127.0.0.1:6379` db15；`IntegrationTestBase` 等 14 个测试文件用 RedisTemplate）→ CI 须起 redis service container，版本对齐 compose 口径 `redis:7`；
  3. 前端：锁文件 `pnpm-lock.yaml`（lockfileVersion 9.0）→ CI 用 pnpm 9；本地 node v22.19.0（Vite 7 要求 ≥20.19/22.12）→ CI 用 setup-node 22；`package.json` 无 `packageManager` 字段，实施时补一行固定精确版本；
  4. `OpenApiContractTest` 的 `Skipped: 1` 属 exportContract 开关设计使然，CI 同口径出现——解析断言对 Failures/Errors 硬断言为 0，对 Skipped 只记录不做硬零断言。
- **机检化对象（台账 P1 三条纪律）**：用例计数对比基线、契约路径数下限、双 banner 输出存在性断言——载体为入库基线文件 + CI 内解析断言脚本，任一检查失败须非零退出（对齐 agent-harness spec「声明的门禁必须能够失败」）。

## What Changes

1. **`.github/workflows/ci.yml`**（GitHub Actions，ubuntu-latest，双 job）：
   - 触发：`push`（branches: `main`、`feature/**`）+ `pull_request`；`concurrency` 取消同 ref 旧跑；
   - **backend job**：checkout → setup-java（temurin 17）→ redis:7 service container（6379）→ cache `.m2-repo` → 仓库根 `./mvnw clean test`（日志 tee 留档）→ 解析 surefire 汇总：断言 `Failures=0` 且 `Errors=0` 且 `Tests run ≥ 基线`，断言 BUILD SUCCESS banner 存在；附步骤解析仓库根 `openapi.yaml` paths 键数 ≥ 基线（计数实现用 runner 自带 bash/python3 工具域，形态执行时现场定）；
   - **frontend job**：checkout → setup-node 22 + corepack pnpm（版本经 `packageManager` 字段固定）→ `pnpm install --frozen-lockfile` → `lint:check` / `type-check:check` / `test` 三门禁串行（各自 tee 日志）→ 解析断言：vitest 汇总 passed ≥ 基线、三份日志非空、双 banner 存在（vue-tsc 与 tsc 各自真实 banner）。
2. **`.github/ci/gate-baseline.json`（入库基线文件，机检载体）**：后端用例数下限、前端用例数下限、契约 paths 下限——三数值以实施笔开工基线（任务 1 K3/K4/K5）实测写入，证据绑定 revision；**此后每个变更的收口笔须同步上调、不得下调**（「只增不减」纪律的机检载体，登记进 spec/README.md 工作流）。
3. **`.github/ci/` 解析断言脚本**（bash/python3，runner 自带工具域）：surefire 汇总解析、vitest 汇总解析、paths 计数、banner/非空断言——任一检查失败以非零退出码结束。
4. **`frontend/package.json`**：补 `packageManager` 字段固定 pnpm 精确版本（工程配置一行，非业务代码；版本以 lockfileVersion 9.0 兼容口径现场定版）。
5. **文档登记**：`README.md`「要查什么去哪儿查」表加 CI 一行（不写用例数等易变事实），顺手校正「快速开始」第 2 条陈旧文案（「门禁命令当前**未定稿**……在 E2 落地前」——E2 已落地，与表格末行矛盾）；`AGENTS.md` 加 CI 指针一行（只加指针不加正文，E1 收尾第 7 步惯例）；`spec/README.md` 工作流补「收口笔同步上调 gate-baseline.json」纪律条目 + 三处常规登记（收口句 / agent-harness 能力域来源列 / 归档表行）。
6. **spec 基线**：`spec/specs/agent-harness/spec.md` 末尾 ADDED「CI 门禁机检化」Requirement（六 Scenario：push 自动双端门禁 / 用例数低于基线即红 / 契约路径数低于基线即红 / banner 与非空断言防假绿 / CI 配置零机器绑定 / CI 验收绑定真实运行）。

## Impact

- **规范**：agent-harness 能力域 ADDED 1 个 Requirement（六 Scenario）；既有 Requirement 零改动。
- **代码**：零业务代码改动（`src/`、`pom.xml`、`schema.sql`、`openapi.yaml` 均零触碰）；新增 `.github/workflows/ci.yml`、`.github/ci/`（脚本 + 基线文件）；`frontend/package.json` +1 行 `packageManager`；README / AGENTS / spec-README 登记若干行。
- **用户/API**：无任何可见变化。
- **数据与部署**：无（零 schema、零应用配置改动；CI 的 Redis 为 runner 上临时容器，不触碰任何 dev/共享资源）。
- **流程影响（新增义务，显式声明）**：此后每个变更的收口笔须同步上调 `gate-baseline.json`（双端用例数与契约 paths 只增不减），否则该笔 push 的 CI 红。
- **外发动作授权**：本案验收必然包含 push（feature 分支、probe 分支、main）——用户派发本卡即视为授权本案范围内的 push 动作；CI 验证之外的 push（如后续无关分支）不在此授权内。
- **非目标（显式排除）**：CD——部署流水线、镜像构建与发布、环境推进、制品库；自托管 runner；覆盖率报告上传；依赖漏洞扫描（P4 候选范围）；通知渠道接入。CI 不改变本地唯一门禁命令的地位——CI 是机检化复核与第二防线，验收结论的 canonical 判据仍在 agent-harness spec。

## 验收与停止条件

- **本地可验证部分**：workflow 语法静态检查（actionlint 或等效）通过；grep 自查 `.github/` 全部新增文件零机器绝对路径（`D:\`、`C:\` 类盘符模式）零私有凭证；解析断言脚本对构造样例的红绿自测（低于基线样例 → 非零退出、达标样例 → 零退出、无 banner / 空日志样例 → 非零退出）；实施笔前后本地双端门禁持平全绿（CI 引入不触碰业务测试）。
- **远端验证（三要素留证：run URL + 汇总数字 + 短 revision）**：① push `feature/add-ci-gate-pipeline` → CI 首跑两 job 全绿；② 从 feature 分支切 `feature/ci-gate-probe`，注入变异（`gate-baseline.json` 三数值抬高至超限）push → CI 断言层红（证明基线机检真在比对、非恒真）→ 留证后删除 probe 分支（不污染 feature 分支历史）；③ 本地 `--no-ff` 合并 main → push main → main 上 CI 绿。
- **停止条件**：① 若 GitHub Actions 在该仓库不可用（权限/额度/组织策略）→ 停止上报，不擅自换其他 CI 平台；② 若 CI 上后端测试出现环境性失败（Redis service 不可达、locale/时区类）两轮调整仍无法解决 → 停止上报，**不得修改 `src/test` 业务测试代码迎合 CI**；③ 若解析断言须引入 runner 自带工具域（bash/python3）之外的依赖 → 停止上报；④ workflow/脚本被要求引入机器绝对路径或私有凭证 → 违反 agent-harness spec「构建配置的机器绑定不得进入版本库」，停止上报；⑤ 若 `packageManager` 字段或 pnpm 9 口径导致前端三门禁在 CI 与本地出现行为分叉 → 停止上报裁决，不本地绕过。

## 验收边界（收口回填，2026-10-11）

- **裁决 GO**。本地可验证部分全部成立：actionlint 1.7.7 通过；`.github/` 零机器绝对路径零私有凭证（grep 自查双 CLEAN）；解析断言脚本样例红绿 12/12（低于基线 → 非零、达标 → 零、无 banner / 空日志 / 基线键缺失 fail-closed → 非零）；实施笔 `eaec0e7` 与合并后 main（`fc21d90`）双端门禁两次持平全绿（后端 398/0/0/1 + BUILD SUCCESS；前端 vitest 86 文件 610 例、三门禁退出码 0）。
- **远端验证三要素**：① feature 首跑绿 run `38104764997`（headSha `eaec0e7`，双 job success，CI 端 surefire 398/0/0/1、paths 74、vitest 610，三条解析断言全 OK）；② probe 注入红 run `38105268913`（headSha `f292025`，`gate-baseline.json` 抬至 399/611/75，测试本体仍绿而断言层红——`Tests run=398 < baseline 399`、`vitest passed=610 < baseline 611`，证明机检真在比对非恒真；注入分支用后即删，feature 分支零污染自证 `git diff eaec0e7 HEAD` 为空）；③ --no-ff 合并笔 `fc21d90` 后 main 绿 run `38105772968`（双 job success）。
- **实施机制一处偏离 proposal 字面，如实登记**：pnpm 定版用 `pnpm/action-setup@v4` 读 `packageManager` 字段（proposal 字面的「corepack」机制替换——规避 corepack 对新版 pnpm 签名密钥的已知校验风险），交付语义「packageManager 字段固定的 pnpm」不变；K5 口径同步落地为本地实测 pnpm 10.25.0（lockfileVersion 9.0 兼容、registry 核在），非 proposal 预估的「pnpm 9 系最新」。
- **五条停止条件均未触发**：GitHub Actions 可用；CI 后端零环境性失败（redis:7 service 首跑即可达）；解析断言只用 bash/grep/awk/sed（runner 自带工具域）；零机器绑定零凭证；前端三门禁 CI 与本地零行为分叉（双 banner 在 pnpm 输出下真实命中）。
- **非目标保持排除**：CD、自托管 runner、覆盖率上传、依赖漏洞扫描、通知渠道全部未做；CI 不改变本地唯一门禁命令地位。

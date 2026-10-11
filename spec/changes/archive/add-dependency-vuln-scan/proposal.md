# 提案：依赖漏洞扫描入 CI（Trivy 非阻断报告，生产化 P4 重定义）

> 状态：已立项待执行。阶段 0 查证基于 main `4d70035`（2026-10-11）现场重核；开工基线在任务 1 K1 以 `git rev-parse --short HEAD` 实测记录。K1 前置要求 P2 任务 5 返修笔先落位 main（未落即停止上报）——**该返修笔当前尚未提交，属开工前置而非既成事实**（2026-10-11 复核 r2 纠正本行此前失真表述）。台账候选 P4 三缺口经现场重核大幅收窄，范围按用户裁决定版为**仅依赖漏洞扫描入 CI**，形态为 **Trivy 非阻断报告**。

## Why

- **台账 P4 三缺口现场重核（防「台账滞后致范围失真」先例）**：
  1. **登录限流**——已在 HEAD 闭环：`LoginGuardService` 实现账号+IP 固定窗口限流（超阈值 429）与连续失败锁定（阈值/时长走 `AuthProperties` lock/rate-limit 配置段），`AuthService.login` 全链路接线，锁定联动审计日志——台账此缺口过期，无需立项；
  2. **审计日志留存策略**——已在 HEAD 闭环：`audit_log` 表含 `idx_audit_time` 年龄索引，`RetentionService` 以独立配置窗口按 `created_time` 超窗清理，与考试生命周期表分道——台账此缺口过期，无需立项；
  3. **登录验证码**——真缺口但经用户裁决**不纳入**（在台账登记「已评估不做」）：已有四层防护（限流 429 / 失败锁定 / 统一提示防枚举 / BCrypt-12+强密码策略），教育内网威胁模型下验证码增量价值仅覆盖「限流阈值内慢速撞库」，而代价是 LoginRequest 加字段 → openapi 契约变更 → 前端 SDK 重生成 → 登录页 UI → 后端生成/存储/校验链路的全栈契约面变更，性价比不成立；
  4. **依赖漏洞扫描入 CI**——真缺口：P1 建立的 CI 为双 job 门禁（backend/frontend + gate-baseline 机检断言），无任何依赖漏洞扫描步骤。即本变更范围。
- **依赖面现状（`4d70035` 实测）**：后端 Spring Boot 3.5.5 parent 托管族 + mybatis-plus 3.5.9 / dynamic-datasource 4.3.1 / jjwt 0.11.5 / jbcrypt 0.4 / mysql-connector-j 9.3.0 / poi 5.4.0 / openpdf 1.3.43 / OTel 2.1.0-alpha / springdoc 2.8.13 / h2(test)；前端 pnpm-lock（lockfileVersion 9.0）。前后端均无自动化漏洞可见性——「依赖是否有已知 CVE」目前完全不可知。
- **形态裁决（用户定版）**：Trivy 非阻断报告。理由——① 对齐 pom 既有质量三件套（jacoco/spotbugs/checkstyle）「只度量不拦截」渐进哲学，漏洞现状先可见、阈值门禁待数据积累后另行裁决；② Trivy 双栈覆盖（Maven 坐标 + pnpm-lock）、无秘钥依赖（对照 OWASP dependency-check 的 NVD API key 注册与 secret 负担）、CI 增时可控（DB 下载 + 扫描分钟级）；③ Dependabot 属 GitHub 平台服务而非 CI job 且自动开升级 PR 与本项目「提案驱动、人工裁决」治理风格有张力，不入本案。

## What Changes

1. **`.github/workflows/ci.yml` 新增第三 job `dependency-scan`**（既有 backend/frontend 两 job 定义**零改动**）：
   - 触发与既有 job 相同（push main/feature/** + pull_request），三 job 并行；共享既有 `concurrency` 分组语义；
   - 步骤：checkout → Trivy filesystem 扫描（`aquasecurity/trivy-action` **pinned 到实施时最新稳定 tag，禁 floating tag**；`scan-type: fs`；`scan-ref: .`；`skip-dirs` 至少含 `.git,target,node_modules,frontend/node_modules,.m2-repo`；`format: table`；输出 tee 至报告文件）→ 报告行数/CVE 计数 echo 进 `$GITHUB_STEP_SUMMARY` → `actions/upload-artifact`（pinned 稳定版本）上传报告留痕；
   - **非阻断语义（两分，写死）**：`exit-code: 0`——扫描成功且发现漏洞时 job 仍绿、报告如实列出（这是「非阻断」的全部含义）；扫描器执行失败（步骤非零）仍按 job 失败传导变红——扫描器坏了不得静默；
   - job 内零 `secrets` 引用、零私有凭证、零机器绝对路径。
2. **`.github/ci/gate-baseline.json` 零改动**：扫描不产生用例数与契约 paths 变化，基线不动（合法持平）。
3. **文档登记**：`spec/README.md` 三处常规登记（「当前无进行中变更」收口句 / agent-harness 能力域行来源变更列追加 / 已归档变更表新增行）；`README.md` / `AGENTS.md` 不动（远端第二防线既有指针已覆盖 CI 域，本案不新增指针义务）。
4. **spec 基线**：`spec/specs/agent-harness/spec.md` 末尾 ADDED「依赖漏洞扫描非阻断报告」Requirement（六 Scenario，见 spec-delta）。
5. **三件套随实施笔一并入库**（对齐 add-ci-gate-pipeline O-1 教训：使收口笔归档形态为 R100）。

## Impact

- **规范**：agent-harness 能力域 ADDED 1 个 Requirement（六 Scenario）；既有 Requirement（含「CI 门禁机检化」）零改动。
- **代码**：零业务代码改动（`src/`、`frontend/`、`pom.xml`、`schema.sql`、`openapi.yaml` 均零触碰）；`.github/workflows/ci.yml` 新增一个 job 段；spec/README 登记若干行。
- **用户/API**：无任何可见变化。
- **数据与部署**：无（零 schema、零应用配置；Trivy 在 runner 上临时运行，不触碰任何 dev/共享资源）。
- **流程影响**：CI 从双 job 变三 job——既有双门禁的判定语义零变化；新 job 永不因漏洞本身变红，未来若裁决升级为阈值门禁属新变更。
- **外发动作授权**：本案验收必然包含 push（feature 分支、probe 注入分支、main）——用户派发本卡即视为授权本案范围内的 push 动作。
- **非目标（显式排除）**：登录验证码（已评估不做，理由见 Why.3）；阻断式阈值门禁（CVSS 分级变红）；OWASP dependency-check 与 NVD API key；Dependabot（alerts 或 version-update PR）；CD；覆盖率报告上传；SARIF 上传 GitHub code scanning（需 security_events 权限配置，仅 artifact 留痕）。

## 验收与停止条件

- **远端验证三要素（run URL + 汇总数字 + 短 revision，对齐 P1 先例）**：
  ① push `feature/add-dependency-vuln-scan` → CI 三 job 全绿（backend/frontend 照旧 + dependency-scan 绿且产出 artifact，`gh run download` 下载报告确认非空）；
  ② 从 feature 分支切 probe 注入分支，向 dependency-scan job 注入必败步骤（如前置 `run: exit 1`）push → **该 job 变红而 backend/frontend 两 job 照常独立判定**（证明 scan job 真实执行非恒绿装饰、且其失败不改变门禁 job 语义）→ 留证后删除 probe 分支（不污染 feature 分支与 main 历史）；
  ③ `--no-ff` 合并 main → push main → main 上 CI 三 job 全绿。
- **本地可验证部分**：实施笔前后双端门禁持平全绿（零业务代码，预期持平基线）；grep 自查 ci.yml 新增段零 `secrets`/零盘符绝对路径；既有双 job 段 diff 自证零改动；`gate-baseline.json` 零改动自证。
- **停止条件**：① Trivy action 在该仓库 runner 上不可用或必须引入 API key/secret 才能工作 → 停止上报，不擅自换扫描平台；② 新增 job 导致既有 backend/frontend job 任何行为变化（排队挤压致超时、concurrency 语义被破坏）→ 停止上报；③ 实施时查证不到可 pinned 的稳定 action tag（仅 floating tag 可用）→ 停止上报；④ workflow 被要求引入机器绝对路径或私有凭证 → 停止上报；⑤ scan job 任何配置必须改动 `gate-baseline.json` 或既有断言脚本才能工作 → 停止上报。

## 验收边界（收口回填，2026-10-11）

- **裁决 GO**。五条停止条件均未触发：trivy-action v0.36.0（2026-04-22 release）与 upload-artifact v7.0.2（2026-10-07 release，非 prerelease）均可 pinned 稳定 tag，零 floating；trivy 在 ubuntu-latest runner 首跑即绿、零 secret 依赖（github-pat 未传）；既有 backend/frontend 两 job 断言照旧全绿（BACKEND GATE ASSERT OK 398/0/0/1、OPENAPI PATHS ASSERT OK 74、FRONTEND GATE ASSERT OK vitest 610），行为零变化；ci.yml 新增段零 secrets/零盘符绝对路径；`gate-baseline.json` 零改动（合法持平）。
- **远端验证三要素**：① feature 首跑绿 run `38123773442`（headSha `f143ec4`，三 job success；artifact `trivy-report.txt` 13,636 字节/76 行非空，双栈覆盖实证：`frontend/pnpm-lock.yaml (pnpm)` 5 项 + `pom.xml (pom)` 4 项，9 CVE 如实列出而 job 绿——非阻断语义生效）；② probe 注入红 run `38124945091`（headSha `8f00423`，dependency-scan job 前置必败步骤 `run: exit 1` → 该 job failure 而 backend/frontend 两门禁 job 照常 success——scan job 真实执行非恒绿装饰；注入分支远端本地均删，feature 分支 `git diff f143ec4 HEAD --stat` 空，零残留）；③ --no-ff 合并笔 `515b79d` 后 main 绿 run `38125602056`（三 job success）。
- **本地可验证部分**：实施笔 `f143ec4` 前后双端门禁两次持平全绿（后端 `mvnw.cmd clean test` → 398/0/0/1 + BUILD SUCCESS；前端 lint/type-check/test 全绿 86 文件 610 例）；四项自查（diff 纯新增 36 行 / gate-baseline 零触碰 / 零 secrets 零盘符 / js-yaml 解析三 job 结构）全绿，留证 `D:\code\examOnline-measure\impl-add-dependency-vuln-scan\`。
- **登记偏差一处（如实）**：任务 5 台账返修笔的 evidence 以**执行子 agent 自己的当次实测**（双端门禁实施笔前后复跑、三 run 三要素、零代码自证）回填，而非 P1/P2 先例的「复核子 agent VERDICT 逐字转录」——本卡派发为执行子 agent 一次性完成任务 1-5、复核轮尚未进行；独立复核子 agent 后续复跑时如发现出入，以复核实测为准修正本格。
- **非目标保持排除**：阻断式阈值门禁（CVSS 分级变红）、OWASP dependency-check 与 NVD API key、Dependabot、CD、覆盖率上传、SARIF 上传 code scanning 全部未做；发现漏洞 job 仍绿（首跑 9 项 CVE 实证），未来若裁决升级为阈值门禁属新变更。

# evidence/ —— harden-actuator-exposure 证据索引（证据三要素：命令 + 当次输出 + 短 revision）

定向命令均为仓库根 `./mvnw test -Dtest=...`（Git Bash；定向运行不带 clean）；01–05 为定向运行完整日志原样入库，06 为全量门禁判定段（全量原始日志留存仓库外 `D:\code\examOnline-measure\harden-actuator-exposure\`，变异五步留证同盘 mutation-check/）。

## 01 先红（行为红，护栏对旧形态）

- revision：立案笔 25ecda8 工作树（WebMvcConfig 仍 /actuator/** 整体豁免、prod yml 无 management 收敛块）
- 命令：`./mvnw test -Dtest=ActuatorExposureGuardTest`
- 当次输出（01-tdd-red-old-form.log）：`Tests run: 2, Failures: 2, Errors: 0`——两护栏断言对旧形态确定性失败（exposure 恰值断言：prod 无 management 段；白名单断言：无三模式且整体豁免在场）；BUILD FAILURE（EXIT=1）

## 02 转绿（实施后定向全绿，含观测链路回归）

- revision：实施笔 16e9001
- 命令：`./mvnw test -Dtest=ActuatorExposureGuardTest,SubmitObservabilityIntegrationTest,AlertAssetsTest`
- 当次输出（02-targeted-green-impl.log）：`Tests run: 2, Failures: 0`（护栏两断言）+ `Tests run: 2, Failures: 0`（SubmitObservabilityIntegrationTest：/actuator/metrics/{tomcat.threads.*} 200 + /actuator/prometheus SLO 桶——**观测链路保全的端到端证明**）+ `Tests run: 7, Failures: 0`（AlertAssetsTest）；合计 11/11 BUILD SUCCESS（EXIT=0）

## 03 变异击红（恢复 /actuator/** 整体豁免）

- 变异：`scripts/mutation-check.sh --inject-sed 's|"/actuator/prometheus",|"/actuator/**",|'`——豁免白名单中 prometheus 模式替换回整体豁免（注入标志 `"/actuator/**",` 在场由工具核验）
- revision：16e9001 + 工作树变异（工具内自动备份/复原）
- 命令：`./mvnw test -Dtest=ActuatorExposureGuardTest`
- 当次输出（03-red-mutation-broad-allow.log）：interceptorExclusionIsWhitelistNotBroadActuator 确定性失败（prometheus 模式缺失 + 整体豁免在场双命中），BUILD FAILURE（EXIT=1）
- 等价变异登记（如实，不强杀）：①豁免模式改为 Spring Ant 风格等价写法（如 /actuator/health*）——词法断言锚定双引号精确串，等价改写会击红而非漏杀（保守方向，非免杀形态）；②prod yml exposure 用 YAML 列表形态（- health 换行列表）替代逗号串——SnakeYAML 解析出 List 而非 String，assertEquals("health,prometheus") 击红（同属保守击红，登记其存在即可）。

## 04 复原回绿

- 还原：mutation-check 第④步 cmp 字节级复原 + 注入标志归零（工具自动）
- 命令：同 03（第⑤步重跑）
- 当次输出（04-green-restored.log）：BUILD SUCCESS（EXIT=0）

## 05 范围外零改动核对（收口 diff，对照 main）

- 命令：`git diff main --stat -- src/main/resources/application.yml src/main/resources/application-dev.yml src/main/resources/application-test.yml docker/observability/ docker-compose.yml docker-compose.prod.yml src/test/java/com/exam/observability/SubmitObservabilityIntegrationTest.java src/main/resources/schema.sql docker/mysql/ openapi.yaml pom.xml frontend/`
- 当次输出（05-out-of-scope-diff.txt）：空（0 字节，OUT_OF_SCOPE_DIFF_LINES=0）——base/dev/test 档、prometheus 抓取与告警资产、compose 编排、既有观测集成测试 + schema.sql、migrations、openapi.yaml、pom、frontend 全零触碰。
- 分支总 diff：恰 7 文件（三件套 3 + spec/README.md 在途表 1 + 实施 3），全在 proposal 白名单内。

## 06 全量门禁（首跑红——既有间歇性竞态如实登记；复跑绿）

- **首跑（06a-full-gate-first-run-flake.log）**：revision 16e9001，`./mvnw clean test` → `Tests run: 676, Failures: 1, Errors: 0, Skipped: 1`，GATE_EXIT=1——失败用例 ExamStateMachineTransactionTest.rolledBackRoundLeavesExamDetailCacheUntouched 前置断言「注入故障的一轮必须失败」落空。**归因（亲验）**：非本卡变更回归——该用例单类复跑 3/0/0/0 绿（`./mvnw test -Dtest=ExamStateMachineTransactionTest`，同工作树）；门禁日志中「考试 980400062 定时迁移 1 -> 2 + 已清除 examDetail 缓存」为**已提交**轮次日志，注入 markAbsence 故障的轮次必回滚不可能提交——该推进只能来自**其他 Spring 上下文**的默认 10s @Scheduled 状态机（共享命名 H2 mem:exam + Redis db15），抢先 casAdvance 了本用例造的「已到结束时间」考试，致注入轮候选为空、markAbsence 未被调用、轮次空成功。竞态窗口与多上下文调度 tick 对齐，属既有测试基建的间歇性竞态（首次观测，机制如上定位；修复=上下文级隔离或调度禁用策略，不在本卡白名单范围，留观察项）。
- **复跑（06-full-gate-summary.log）**：同 revision 16e9001，`./mvnw clean test` → `Tests run: 676, Failures: 0, Errors: 0, Skipped: 1`，BUILD SUCCESS，GATE_EXIT=0（676 = 674 基线 + 本案新增 2；Skipped 1 为既有 OpenApiContractTest 基线跳过）。

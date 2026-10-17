# evidence/ —— harden-scheduler-pool-config 证据索引（证据三要素：命令 + 当次输出 + 短 revision）

定向命令均为仓库根 `./mvnw test -Dtest=...`（Git Bash；定向运行不带 clean）；01–05 为定向运行完整日志原样入库，06 为全量门禁尾部判定段裁剪（全量原始日志留存仓库外 `D:\code\examOnline-measure\harden-scheduler-pool-config\`，变异五步留证同盘 mutation-check/）。

## 01 先红（行为红，护栏对旧配置形态）

- revision：立案笔 14a7817 工作树（application.yml 仍为旧形态——无 spring.task.scheduling 声明）
- 命令：`./mvnw test -Dtest=SchedulingPoolConfigGuardTest`
- 当次输出（01-tdd-red-old-form.log）：`Tests run: 2, Failures: 1, Errors: 0`——baseDeclaresSchedulingPoolAtLeastTwo 确定性失败（旧配置无声明，断言「必须声明」命中）；BUILD FAILURE（EXIT=1）
- 定性：先红=行为红（护栏断言对旧配置形态直接击红——本卡是配置卡，无新类型引用，无编译红形态）。

## 02 转绿（实施后定向全绿）

- revision：实施笔 695eddb
- 命令：`./mvnw test -Dtest=SchedulingPoolConfigGuardTest`
- 当次输出（02-targeted-green-impl.log）：`Tests run: 2, Failures: 0, Errors: 0`——baseDeclaresSchedulingPoolAtLeastTwo（声明在且 ${SCHEDULING_POOL_SIZE:2} 解析缺省=2 ≥2）+ profileOverridesStayAtLeastTwo（dev/prod 零覆盖 = 继承 base，一致性成立）；BUILD SUCCESS（EXIT=0）

## 03 变异击红（缺省值改回 1）

- 变异：`scripts/mutation-check.sh --inject-sed 's/size: ${SCHEDULING_POOL_SIZE:2}/size: ${SCHEDULING_POOL_SIZE:1}/'`——base 缺省值 2→1（注入标志在场由工具核验）
- revision：695eddb + 工作树变异（工具内自动备份/复原）
- 命令：`./mvnw test -Dtest=SchedulingPoolConfigGuardTest`
- 当次输出（03-red-mutation-pool-size-1.log）：baseDeclaresSchedulingPoolAtLeastTwo 确定性失败（1 < 2），BUILD FAILURE（EXIT=1）
- 等价变异登记（如实，不强杀）：删除 SchedulingPoolConfig javadoc 补记段落——注释零运行期语义，对测试面等价（注释回归由 diff 审查承载，不强杀）；dev/prod 显式声明同值 2——对护栏面与 base 单点声明等价通过（一致性判据只设下限不设「必须零覆盖」，登记不强杀：显式同值声明是被「单一事实源」纪律不建议、但护栏允许的形态）。

## 04 复原回绿

- 还原：mutation-check 第④步 cmp 字节级复原 + 注入标志归零（工具自动）
- 命令：同 03（第⑤步重跑）
- 当次输出（04-green-restored.log）：BUILD SUCCESS（EXIT=0）

## 05 范围外零改动核对（收口 diff，对照 main）

- 命令：`git diff main --stat -- src/main/java/com/exam/exam/service/ src/main/java/com/exam/message/service/ src/main/java/com/exam/monitoring/retention/ src/main/java/com/exam/taking/service/ExamSweepService.java src/main/resources/application-dev.yml src/main/resources/application-prod.yml src/main/resources/schema.sql docker/ openapi.yaml pom.xml frontend/`
- 当次输出（05-out-of-scope-diff.txt）：空（0 字节，OUT_OF_SCOPE_DIFF_LINES=0）——四个 @Scheduled 任务本体（状态机/提醒/保留/sweep）、dev/prod yml + schema.sql、docker、openapi.yaml、pom、frontend 全零触碰。
- 分支总 diff：恰 7 文件（三件套 3 + spec/README.md 在途表 1 + 实施 3），全在 proposal 白名单内。

## 06 全量门禁

- revision：695eddb（工作树=实施笔干净态）
- 命令：仓库根 `./mvnw clean test`（Git Bash；带 clean、未用 -o）
- 当次输出（06-full-gate-summary.log）：`Tests run: 667, Failures: 0, Errors: 0, Skipped: 1`，BUILD SUCCESS，GATE_EXIT=0（Skipped 1 为既有 OpenApiContractTest 基线跳过；667 = 665 基线 + 本案新增 2；池扩容 1→2 对全部既有定时任务相关用例零回归）

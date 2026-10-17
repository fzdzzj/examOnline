# evidence/ —— add-redis-dependency-runbook 证据索引（证据三要素：命令 + 当次输出 + 短 revision）

定向命令均为仓库根 `./mvnw test -Dtest=AlertAssetsTest`（Git Bash；定向运行不带 clean）；02–05 为定向运行完整日志原样入库，06 为全量门禁判定行裁剪（全量原始日志留存仓库外 `D:\code\examOnline-measure\add-redis-dependency-runbook\`）。

## 01 盘点留痕（源码直读，无运行证据）

- 方法：全仓 grep RedisTemplate/StringRedisTemplate 持有类（11 个）+ @Cacheable/@CacheEvict 全集 + CacheConfig 无 CacheErrorHandler 核实 + CacheMutexLoader 消费方核实（ExamSnapshotService/PaperSnapshotService）
- 结论：键族矩阵 13 行与告警映射表已落 docs/redis-dependency-runbook.md §二/§三；硬依赖面（草稿/令牌签发续期/Spring Cache/找回码）与残留观察面（TokenStoreService 零容错=故障期新登录 500、CacheConfig 无错误处理器=拉卷/详情/写失效 500）如实登记，未冒领为已降级。盘点属源码直读，无可运行输出，以 tasks.json 任务 2 与 runbook 正文为凭。

## 02 转绿（实施后定向全绿）

- revision：实施笔 47d148e 工作树态
- 命令：`./mvnw test -Dtest=AlertAssetsTest`
- 当次输出（02-targeted-green-impl.log）：`Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS（EXIT=0）——EXPECTED_ALERTS 含 SubmitLockDegraded 且与 yml 规则集合相等、结构断言（alert/expr/for/severity/summary/description）全过、known 指标名集合含 exam_submit_lock_degraded_total

## 03 变异击红（删除 SubmitLockDegraded 规则）

- 变异：exam-online-alerts.yml 删除 SubmitLockDegraded 规则块（[D1 计数器的告警缺口复活形态]，跑完即 `git checkout HEAD --` 还原）
- revision：47d148e + 工作树变异（未提交）
- 命令：`./mvnw test -Dtest=AlertAssetsTest`
- 当次输出（03-red-mutation-rule-deleted.log）：`Tests run: 7, Failures: 1, Errors: 0`——alertRulesAreComplete 断言 `告警规则集合与提案不一致`（expected 含 SubmitLockDegraded / was 缺失）确定性失败；BUILD FAILURE（EXIT=1）
- 定性登记：本卡为文档卡+告警配置，无业务行为可变异——守护红即「先红」的等价形态（AlertAssetsTest 是集合相等断言，规则缺失即红，先红后绿纪律以变异红补证，与 harden-auth-interceptor-redis-degradation「TDD 红由变异同形补证」登记口径一致）。等价变异：无（删除规则是集合可观测差异，断言直接列出缺失名）。

## 04 复原回绿

- 还原：`git checkout HEAD -- docker/observability/prometheus/rules/exam-online-alerts.yml`（还原到实施笔 47d148e）
- 命令：`./mvnw test -Dtest=AlertAssetsTest`
- 当次输出（04-green-restored.log）：`Tests run: 7, Failures: 0, Errors: 0, Skipped: 0` BUILD SUCCESS（EXIT=0）

## 05 范围外零改动核对（收口 diff，对照 main）

- 命令：`git diff main --stat -- src/main src/main/resources/schema.sql docker/mysql/ openapi.yaml pom.xml frontend/ src/main/resources/db`
- 当次输出（05-out-of-scope-diff.txt）：空（OUT_OF_SCOPE_DIFF_LINES=0）——src/main 全域（含硬依赖面 CacheConfig/TokenStoreService/ExamDraftService，未冒领为已消除）+ schema.sql、migrations、openapi.yaml、pom、frontend、db/migration 全零触碰。
- 分支总 diff：恰 9 文件（三件套 3 + spec/README.md 在途表 1 + runbook/告警规则/AlertAssetsTest/AGENTS.md 4），全在 proposal 白名单内。

## 06 全量门禁 + AGENTS 四自查

- revision：47d148e（工作树=实施笔干净态）
- 命令：仓库根 `mvnw.cmd clean test`（cmd 调用；带 clean、未用 -o）+ AGENTS.md 四自查 grep
- 当次输出（06-full-gate-b2-summary.log）：`Tests run: 651, Failures: 0, Errors: 0, Skipped: 1`，BUILD SUCCESS，GATE_EXIT=0（651 = D1 收口后基线持平，零新增用例；gate-baseline.json 651 核对不动）；AGENTS.md 四自查（端口口令计数/根入口连接事实/@Sql/裸行号）grep 全部无命中。

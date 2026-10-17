# evidence/ —— harden-submit-lock-redis-degradation 证据索引（证据三要素：命令 + 当次输出 + 短 revision）

定向命令均为仓库根 `./mvnw test -Dtest=...`（Git Bash；定向运行不带 clean——clean 只影响残留报告拼数，定向 -Dtest 已限定类集）；01–05 为定向运行完整日志原样入库，06 为全量门禁判定行裁剪（全量原始日志留存仓库外 `D:\code\examOnline-measure\harden-submit-lock-redis-degradation\`）。

## 01 先红（编译红，测试先行）

- revision：立案笔 41631cc 工作树（ExamSubmitService 仍为旧形态）
- 命令：`./mvnw test -Dtest=ExamSubmitServiceTest`
- 当次输出（01-tdd-red-old-form.log）：COMPILATION ERROR——`找不到符号` ×2（ExamSubmitServiceTest L284/L305：`countSubmitLockDegraded` 方法在旧形态不存在）；BUILD FAILURE（EXIT=1）
- 定性：先红=编译红（容错断言引用的指标方法在旧形态不存在；harden-auth-edge-cases 编译红先例）。行为红由 03 变异以同形补证（摘除容错 → 降级用例确定性失败），与 fix-mail-smtp-timeout-and-catch「先红 → 转绿 → 变异与先红同形」结构一致。

## 02 转绿（实施后定向全绿）

- revision：实施笔 da287c7 工作树态
- 命令：`./mvnw test -Dtest=ExamSubmitServiceTest,AlertAssetsTest`
- 当次输出（02-targeted-green-impl.log）：`Tests run: 9, Failures: 0, Errors: 0`（ExamSubmitServiceTest：既有 7 用例——首次交卷/按 token 解锁/重复幂等/锁竞争幂等返回/CAS 输家幂等/MQ 失败草稿兜底/归零映射——零改动零回归 + 新增 2——redisErrorDuringLockAcquisition_degradesPassThroughDedupAndCas〔SETNX 异常降级放行完成交卷 + ERROR 日志含「交卷锁降级」+ countSubmitLockDegraded 递增 + 负向断言不记锁竞争/锁等待、不执行 unlock；lockContentionFalse_doesNotCountDegraded〔锁被真占走既有竞争路径且零降级计数——降级口只认不可用不认竞争〕）+ `Tests run: 7, Failures: 0`（AlertAssetsTest：known 集合含 exam_submit_lock_degraded_total）；合计 16/16 BUILD SUCCESS（EXIT=0）

## 03 变异击红（还原「锁异常即失败」旧形态）

- 变异：ExamSubmitService SETNX try-catch 摘除、Redis 调用直接上抛（旧形态；行尾 [MUTATION] 注释标记，unlock 降级跳过一并还原，跑完即 `git checkout HEAD --` 还原）
- revision：da287c7 + 工作树变异（未提交）
- 命令：`./mvnw test -Dtest=ExamSubmitServiceTest`
- 当次输出（03-red-mutation-no-tolerance.log）：`Tests run: 9, Failures: 0, Errors: 1`——Errors=1 恰为 redisErrorDuringLockAcquisition_degradesPassThroughDedupAndCas（RuntimeException 从 doSubmit 裸穿而非降级放行，确定性失败）；锁竞争回归用例与既有 7 用例不受影响（竞争路径语义本就未动）；BUILD FAILURE（EXIT=1）
- 等价变异登记：无。摘除 try-catch 是行为可观测差异（交卷完成 vs 异常上抛）；候选形态各有独立断言可杀——降级改走竞争等待路径（degrade 用例等待超时 STATE_CONFLICT 击红）、去掉 unlock 降级跳过（never-unlock 断言击红）、降级路径补记锁等待/竞争指标（never 断言击红）——不存在行为等价的免杀形态。

## 04 复原回绿

- 还原：`git checkout HEAD -- src/main/java/com/exam/taking/service/ExamSubmitService.java`（还原到实施笔 da287c7；变异在实施笔入库后进行，无 checkout 丢修复二踩）
- 命令：`./mvnw test -Dtest=ExamSubmitServiceTest`
- 当次输出（04-green-restored.log）：`Tests run: 9, Failures: 0, Errors: 0` BUILD SUCCESS（EXIT=0）

## 05 范围外零改动核对（收口 diff，对照 main）

- 命令：`git diff main --stat -- src/main/java/com/exam/taking/service/ExamDraftService.java src/main/java/com/exam/config/CacheConfig.java src/main/java/com/exam/common/cache/ src/main/java/com/exam/submission/mq/ src/main/java/com/exam/auth/service/LoginGuardService.java src/main/java/com/exam/auth/security/AuthenticationInterceptor.java src/main/resources/schema.sql docker/mysql/ openapi.yaml pom.xml frontend/ src/main/resources/db`
- 当次输出（05-out-of-scope-diff.txt）：空（OUT_OF_SCOPE_DIFF_LINES=0）——草稿 Redis 存储（ExamDraftService，裁决为硬依赖面）、RedisCacheManager（CacheConfig）、解锁原语（RedisLockHelper/CacheMutexLoader）、MQ 发布路径（submission/mq）、A1 先例（LoginGuardService/AuthenticationInterceptor）+ schema.sql、migrations、openapi.yaml、pom、frontend、db/migration 全零触碰。
- 分支总 diff：恰 8 文件（三件套 3 + spec/README.md 在途表 1 + 实施 4），全在 proposal 白名单内。

## 06 全量门禁

- revision：da287c7（工作树=实施笔干净态）
- 命令：仓库根 `mvnw.cmd clean test`（cmd 调用；带 clean、未用 -o）
- 当次输出（06-full-gate-d1-summary.log）：`Tests run: 651, Failures: 0, Errors: 0, Skipped: 1`，BUILD SUCCESS，GATE_EXIT=0（Skipped 1 为既有 OpenApiContractTest 基线跳过；651 = A1 收口后基线 649 + 本案新增 2）

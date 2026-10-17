# evidence/ —— harden-spring-cache-resilience 证据索引（证据三要素：命令 + 当次输出 + 短 revision）

定向命令均为仓库根 `./mvnw test -Dtest=...`（Git Bash；定向运行不带 clean）；01–05 为定向运行完整日志原样入库，06 为全量门禁尾部判定段裁剪（全量原始日志留存仓库外 `D:\code\examOnline-measure\harden-spring-cache-resilience\`，变异五步留证同盘 mutation-check/）。

## 01 先红（编译红，测试先行）

- revision：立案笔 17ddaff 工作树（CacheConfig 旧 1 参构造 / CacheMutexLoader 旧 4 参构造 / RedisCacheDegradedHandler 不存在）
- 命令：`./mvnw test -Dtest=CacheResilienceTest`
- 当次输出（01-tdd-red-old-form.log）：COMPILATION ERROR——`找不到符号`（RedisCacheDegradedHandler 旧形态不存在，连带 2 参 CacheConfig / 5 参 CacheMutexLoader 构造失配）；BUILD FAILURE（EXIT=1）
- 定性：先红=编译红（降级断言引用的类型/构造在旧形态不存在；编译红先例同族）。行为红由 03 变异以同形补证。

## 02 转绿（实施后定向全绿）

- revision：实施笔 4eb513f
- 命令：`./mvnw test -Dtest=CacheResilienceTest,CacheThreeDefensesTest,AlertAssetsTest`
- 当次输出（02-targeted-green-impl.log）：`Tests run: 7, Failures: 0`（CacheResilienceTest 新增七用例：handlerSwallowsGetErrorAndCounts〔四动作 ERROR+计数不上抛〕/ cacheConfigWiresCustomErrorHandlerViaCachingConfigurer〔接线契约〕/ loadReturnsDbValueWhenRedisFullyDown〔Redis 全故障仍回源 DB + get/lock/put 计数〕/ notFoundBusinessExceptionStillPropagatesWhenRedisDown〔业务异常原样传播〕/ clearEmptyMarkerDegradesQuietlyWhenRedisDown〔evict 计数〕/ fastPathCacheHitUnaffectedWhenRedisHealthy〔正常路径零降级计数回归〕/ waitForRefillFallsBackToDbWhenRefillWritesFail〔持锁后故障轮询降级兜底回源〕）+ `Tests run: 5, Failures: 0`（CacheThreeDefensesTest 既有五用例零回归，三防正常路径语义不变）+ `Tests run: 7, Failures: 0`（AlertAssetsTest：EXPECTED_ALERTS 含 CacheDegraded、known 含 exam_cache_degraded_total）；合计 19/19 BUILD SUCCESS（EXIT=0）

## 03 变异击红（handler 读动作还原「异常上抛」旧形态）

- 变异：`scripts/mutation-check.sh --inject-sed 's/countCacheDegraded("get");/countCacheDegraded("get"); throw e;/'`——handleCacheGetError 降级分支改上抛（旧形态「缓存读异常裸穿」；countCacheDegraded("get") 在该文件唯一命中，注入标志在场由工具核验）
- revision：4eb513f + 工作树变异（工具内自动备份/复原）
- 命令：`./mvnw test -Dtest=CacheResilienceTest#handlerSwallowsGetErrorAndCounts`
- 当次输出（03-red-mutation-handler-rethrow.log）：测试确定性失败（RuntimeException 从 handler 裸穿而非吞掉+计数），BUILD FAILURE（EXIT=1）
- 等价变异登记（如实，不强杀）：①handler 其余三动作（put/evict/clear）与 get 同构同形，m2 代表族形态（同一 switch 家族、同一断言逐一覆盖，无免杀空间）；②CacheMutexLoader 各触点降级改"失败后重试一次"形态——对现有单测面（全部触点持续故障）与本次降级口径观测等价（仍回源成功），登记不强杀，其区分度由「重试次数语义」新断言承载，属后续加固空间非本卡缺陷。

## 04 复原回绿

- 还原：mutation-check 第④步 cmp 字节级复原 + 注入标志归零（工具自动）
- 命令：同 03（第⑤步重跑）
- 当次输出（04-green-restored.log）：BUILD SUCCESS（EXIT=0）

## 05 范围外零改动核对（收口 diff，对照 main）

- 命令：`git diff main --stat -- src/main/java/com/exam/common/cache/RedisLockHelper.java src/main/java/com/exam/taking/service/ExamDraftService.java src/main/java/com/exam/auth/service/ src/main/java/com/exam/auth/security/ src/main/resources/schema.sql docker/mysql/ openapi.yaml pom.xml frontend/`
- 当次输出（05-out-of-scope-diff.txt）：空（0 字节，OUT_OF_SCOPE_DIFF_LINES=0）——锁原语 RedisLockHelper、草稿硬依赖面、案 1 先例（AuthService/TokenStoreService/AuthenticationInterceptor/LoginGuardService）+ schema.sql、migrations、openapi.yaml、pom、frontend 全零触碰；WrongQuestionJudgementCache（既有降级）在分支总 diff 12 文件之外（零触碰）。
- 分支总 diff：恰 12 文件（三件套 3 + spec/README.md 在途表 1 + 实施 8），全在 proposal 白名单内。

## 06 全量门禁

- revision：4eb513f（工作树=实施笔 + runbook 文档编辑）
- 命令：仓库根 `./mvnw clean test`（Git Bash；带 clean、未用 -o）
- 当次输出（06-full-gate-summary.log）：`Tests run: 665, Failures: 0, Errors: 0, Skipped: 1`，BUILD SUCCESS，GATE_EXIT=0（Skipped 1 为既有 OpenApiContractTest 基线跳过；665 = 658 基线 + 本案新增 7）

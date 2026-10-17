# harden-spring-cache-resilience

## 一、背景与问题（B.2 横切首扫 H2 残留面 · Redis 依赖裁决 1② 卡，2026-10-17 立案）

**裁决背景（用户拍板，2026-10-17）**：Redis 故障口径统一为「正确性由 DB 幂等保证，Redis 降级保可用性，草稿为已声明的硬依赖面」。拦截器、登录守卫（A1）、交卷锁（D1）、令牌签发续期面（1①）已按该口径闭环。

runbook 矩阵如实登记的残留观察面之二：**Spring Cache 快照与详情缓存（`exam:cache:*` 键族）无 CacheErrorHandler**——`RedisCacheManager` 直连，缓存切面的读（`@Cacheable` get）/写（回填 put）/失效（`@CacheEvict` evict/clear）遇 Redis 异常全部裸穿 500：故障期**拉卷、考试详情、考试写路径（@CacheEvict）失败**。同时互斥回源器 `CacheMutexLoader`（同键族承载组件：空标记 + SETNX 互斥锁）绕过缓存切面直连 Redis，其触点同样零容错——拉卷主链路（`ExamSnapshotService.getCurrent` → `cacheMutexLoader.load`）在缓存冷 + Redis 故障时会先于切面降级在互斥器处失败，只修切面不修互斥器则矩阵行无法如实改口径。

## 二、方案（CacheErrorHandler 接线 + 互斥器逐触点降级，新指标 exam.cache.degraded + CacheDegraded 告警）

- **缓存切面接线**：`CacheConfig` 实现 `CachingConfigurer`（Spring 6.2 供出点 `errorHandler()`），供出 `RedisCacheDegradedHandler`——get 失败**静默回源 DB**（缓存是加速器，DB 是正确性同源）；put 失败**放弃本次回填**（方法返回值照常交给调用方，下次读取重新回源）；evict/clear 失败**延迟生效**；
- **evict 脏读窗口论证（重点，落 handler javadoc 与矩阵）**：失效写不生效 → 旧值存续至 TTL 自然过期——①考试详情（examDetail）走短 TTL（detail-base-ttl 5m + jitter 1m）兜底自愈，脏读窗口 ≤ 一个 TTL 周期；②快照类（examSnapshot/paperSnapshot）发布后**只读不可变**（CacheConfig 类注释），缓存与 DB 天然一致，不存在"DB 改了缓存还是旧值"的脏读语义。替代口径（失效异常裸穿 500 使考试写路径全停摆）严格更糟；
- **互斥回源器同族降级（范围口径注记，同 A1 对 clearFailures/checkCodeRateLimit 的处理先例）**：`CacheMutexLoader` 与 `RedisCacheManager` 同属 runbook 矩阵 `exam:cache:*` 行的承载方（KEY_PREFIX 相同），矩阵行降级口径的真实性要求拉卷主链路（getCurrent→load）故障期可用——其六触点逐点包降级：快速路径/等待轮询缓存读失败视为未命中回源 DB（get）、空标记读写失败视为无标记（get/put）、互斥锁不可用跳过单飞直接回源（lock，与交卷锁降级同哲学：互斥只是防抖器，回源幂等）、回填失败放弃本次（put）、解锁失败交由锁 TTL 过期自愈（unlock）、空标记清除失败按其短 TTL 自然过期（evict）；业务语义异常（loader 抛出的 NOT_FOUND 等）不在降级范围、原样传播（降级口只认不可用不认业务结果）；
- **新降级计数指标** `exam.cache.degraded`（BusinessMetrics.countCacheDegraded，tag `action` ∈ get/put/evict/clear/lock/unlock 代码常量有界）+ 告警规则 **CacheDegraded**（照抄 AuthDegraded 形态：`sum(increase(exam_cache_degraded_total[5m])) > 0 for 1m warning`）+ AlertAssetsTest known 集合与 EXPECTED_ALERTS 同笔守护登记；
- **runbook 矩阵同笔改口径**：「exam:cache:*（Spring Cache：快照/详情）」行由硬依赖（未降级）改降级（逐触点故障行为 + 计数指标 + 告警全量登记），§一 硬依赖族三改二，§三 告警盘点补第四条映射，§五 审计链追加；
- 不动：TTL/抖动/空标记/互斥锁参数、CacheMutexLoader 的锁原语（RedisLockHelper）、WrongQuestionJudgementCache（既有降级）、草稿（硬依赖面）、schema.sql、docker/mysql、openapi.yaml、pom、frontend。

## 三、白名单

- `src/main/java/com/exam/config/CacheConfig.java`（CachingConfigurer 接线 + javadoc）
- `src/main/java/com/exam/config/RedisCacheDegradedHandler.java`（新文件：四动作降级处理 + evict 脏读论证 javadoc）
- `src/main/java/com/exam/common/cache/CacheMutexLoader.java`（同族六触点降级包装）
- `src/main/java/com/exam/monitoring/metrics/BusinessMetrics.java`（exam.cache.degraded 新指标）
- `docker/observability/prometheus/rules/exam-online-alerts.yml`（CacheDegraded 规则）
- `src/test/java/com/exam/observability/AlertAssetsTest.java`（EXPECTED_ALERTS + known 集合守护登记）
- `src/test/java/com/exam/cache/CacheResilienceTest.java`（新文件：handler 四动作 + 互斥器降级行为 + 正常路径零降级回归）
- `src/test/java/com/exam/cache/CacheThreeDefensesTest.java`（构造适配 +1 依赖，行为零改动）
- `.github/ci/gate-baseline.json`、`spec/changes/harden-spring-cache-resilience/**`、`spec/README.md`、`docs/功能待办清单.md`、`docs/redis-dependency-runbook.md`
- **禁触**：`RedisLockHelper.java`（锁原语零改动）、`ExamDraftService.java`（硬依赖面）、案 1 先例（AuthService/TokenStoreService/AuthenticationInterceptor/LoginGuardService）、WrongQuestionJudgementCache、schema.sql、docker/mysql/migrations/、openapi.yaml、pom.xml、frontend/

## 四、验收要点

- 降级单测七断言：handler 四动作各 ERROR+计数不上抛；CacheConfig 经 CachingConfigurer 供出 handler（接线契约）；互斥器 Redis 全故障仍返回 DB 回源值（get/lock/put 计数）；NOT_FOUND 业务异常原样传播；空标记清除降级（evict 计数）；快速路径命中零降级计数回归；持锁后 Redis 故障走等待轮询降级 + 兜底回源
- 既有 CacheThreeDefensesTest 五用例零回归（三防正常路径语义不变）+ AlertAssetsTest 回归
- 变异击红：handler 读动作还原「异常上抛」旧形态 → 降级用例确定性失败 → 复原回绿留证（`scripts/mutation-check.sh` 驱动）；等价变异如实登记不强杀
- 范围外零改动核对（RedisLockHelper/ExamDraftService/案 1 先例 + schema.sql + docker/mysql + openapi.yaml + pom + frontend）
- 门禁 `mvnw.cmd clean test` 全绿（仓库根、带 clean、禁 -o）；backendTests 基线在 658 上只增不减，收口笔同笔上调
- S 级理由：单配置类 + 单处理器新文件 + 同族互斥器包裹；行为变化集中在「异常不再裸穿」，无新增业务语义；论证重心（evict 脏读窗口）以 javadoc + 矩阵落档

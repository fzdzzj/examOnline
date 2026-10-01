# 并发边界测试覆盖盘点映射（audit-concurrency-test-coverage）

> 勘察基座 revision：`205ac04`
> 开工门禁基线：命令 `mvn -o clean test`；输出 `Tests run: 276, Failures: 0, Errors: 0, Skipped: 1`（BUILD SUCCESS）

三处并发边界逐分支的「分支 × 测试名」映射。已覆盖的登记对应测试；未覆盖的本次补齐；判定不需测试的分支附理由。

## 边界一：CacheMutexLoader（缓存三防：败者等待 / 超时兜底 / 空标记）

| 分支 | 覆盖状态 | 对应测试 |
| --- | --- | --- |
| 空标记命中（快速路径读空标记、直接抛 404 不回源 DB） | 已覆盖 | CacheThreeDefensesTest#notFoundResultIsCachedAsShortTtlNullMarker |
| 败者等待轮询回填（未抢到锁的线程轮询读到胜者回填的缓存值） | 已覆盖 | CacheThreeDefensesTest#hotKeyMissRebuildsUnderMutexWithSingleDbLoad |
| 等待超时兜底直源（waitForRefill 等待预算耗尽后直接回源重建） | 本次补齐 | CacheThreeDefensesTest#waiterTimesOutAndFallsBackToDirectSource |
| 空标记命中的等待路径子分支（败者轮询中读到空标记抛 404） | 判定不需单独测（见理由） | 无 |

理由（等待路径空标记子分支）：waitForRefill 轮询读空标记与快速路径读空标记走同一「marker - 404 不回源」语义，已由 notFoundResultIsCachedAsShortTtlNullMarker 覆盖该行为；补测仅多一层轮询噪音、覆盖价值重叠，判定不单独加测。

## 边界二：状态机 CAS 并发竞争败者（更新 0 行）

| 分支 | 覆盖状态 | 对应测试 |
| --- | --- | --- |
| 单笔 casTransition 影响 0 行 - 抛 STATE_CONFLICT（409） | 已覆盖 | ExamStateMachineServiceTest#casConflictWhenConcurrentTransitionWins |
| 批量 casAdvanceQuietly 影响 0 行 - 静默跳过、不抛错、不打断同批 | 已覆盖 | ExamStateMachineServiceTest#autoAdvanceSkipsConflictsAndKeepsBatchGoing |

无新增测试（两条 0 行路径均已覆盖；批量 0 行静默跳过即多实例扫描幂等之有意设计，非缺陷）。

## 边界三：交卷锁 token 过期易主后按 token 解锁、不误删他人锁

| 分支 | 覆盖状态 | 对应测试 |
| --- | --- | --- |
| RedisLockHelper.unlock compare-and-delete：旧 token（易主前的 A）不删他人锁、本人 token 才删 | 本次补齐 | RedisLockHelperTest#unlockByStaleTokenDoesNotDeleteReOwnedLock |
| RedisLockHelper.tryLock 同一 key 单飞（第二持有者抢锁失败） | 本次补齐 | RedisLockHelperTest#tryLockIsExclusivePerKey |
| RedisLockHelper.unlock 对不存在的 key 幂等返回 0 | 本次补齐 | RedisLockHelperTest#unlockReturnsZeroWhenKeyAbsent |
| 交卷锁装配：ExamSubmitService.doSubmit finally 按 token 解锁（与加锁同 token），非盲删 redis.delete | 本次补齐 | ExamSubmitServiceTest#releasesLockByTokenCheckedUnlockNotBlindDelete |

## 补测汇总（本次新增 5 个）

- CacheThreeDefensesTest#waiterTimesOutAndFallsBackToDirectSource（边界一 超时兜底直源）
- RedisLockHelperTest#tryLockIsExclusivePerKey / #unlockByStaleTokenDoesNotDeleteReOwnedLock / #unlockReturnsZeroWhenKeyAbsent（边界三 机制）
- ExamSubmitServiceTest#releasesLockByTokenCheckedUnlockNotBlindDelete（边界三 装配）

并发测试均为确定性可判：超时兜底用「轮数驱动」的极短等待预算（不看真实时钟）；易主用直接改写 Redis 值模拟，无并发线程、无 flaky。
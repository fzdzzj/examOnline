package com.exam.cache;

import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.common.cache.CacheMutexLoader;
import com.exam.common.cache.RedisLockHelper;
import com.exam.config.CacheConfig;
import com.exam.config.CacheProperties;
import com.exam.exam.dto.ExamSnapshotResponse;
import com.exam.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 缓存三防场景测试（add-performance-deepening 阶段 8，spec「缓存穿透防护/缓存击穿防护/缓存雪崩防护」）：
 * <ul>
 *   <li>穿透：查无结果写入短 TTL 空标记，同 key 再次查询命中空缓存而非直击 DB；</li>
 *   <li>击穿：热点 key 未命中时多线程并发请求，仅一个线程回源重建，其余等待回填；</li>
 *   <li>雪崩：同批写入的 key TTL 各自叠加随机抖动，不在同一时刻集体过期。</li>
 * </ul>
 * 直接针对 CacheConfig/CacheMutexLoader 验证（未接注解的独立行为），@Cacheable 接入链路见 CacheWiringIntegrationTest。
 */
class CacheThreeDefensesTest extends IntegrationTestBase {

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private CacheMutexLoader cacheMutexLoader;

    // ==================== 防穿透：空值缓存 ====================

    @Test
    void notFoundResultIsCachedAsShortTtlNullMarker() {
        // 模拟"查询不存在的快照 ID"的回源函数：每次被调用计数 +1，并按业务语义抛 404
        Object missingKey = 9_100_000_001L;
        AtomicInteger dbCalls = new AtomicInteger();
        Supplier<ExamSnapshotResponse> loader = () -> {
            dbCalls.incrementAndGet();
            throw new BusinessException(ResponseCode.NOT_FOUND, "考试尚未发布或快照不存在");
        };

        // 第一次：未命中缓存 → 回源抛 404，同时写入空标记
        BusinessException first = assertLoadThrows(loader, missingKey);
        assertEquals(ResponseCode.NOT_FOUND.getCode(), first.getCode());
        // 第二次：命中空标记 → 同样抛 404，但不再回源（穿透被挡住）
        BusinessException second = assertLoadThrows(loader, missingKey);
        assertEquals("考试尚未发布或快照不存在", second.getMessage());
        assertEquals(1, dbCalls.get(), "空标记存续期内第二次查询不应回源 DB");

        // 空标记确实写入且为短 TTL（<= 配置的 30s），不会长期掩盖数据已存在的事实
        String marker = CacheMutexLoader.emptyMarkerKey(
                CacheConfig.CACHE_EXAM_SNAPSHOT, missingKey);
        assertEquals("考试尚未发布或快照不存在", redis.opsForValue().get(marker));
        Long ttl = redis.getExpire(marker);
        assertNotNull(ttl);
        assertThat(ttl).isGreaterThan(0).isLessThanOrEqualTo(30);
    }

    // ==================== 防击穿：互斥锁重建 ====================

    @Test
    void hotKeyMissRebuildsUnderMutexWithSingleDbLoad() throws Exception {
        Object hotKey = 9_100_000_002L;
        AtomicInteger dbCalls = new AtomicInteger();
        // 模拟慢回源：持锁线程 300ms 后才回填，等待线程应在这段时间内轮询到缓存值而非各自回源
        Supplier<ExamSnapshotResponse> loader = () -> {
            dbCalls.incrementAndGet();
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return sampleExamSnapshot((Long) hotKey);
        };

        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CyclicBarrier barrier = new CyclicBarrier(threads);
            List<Future<ExamSnapshotResponse>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return cacheMutexLoader.load(CacheConfig.CACHE_EXAM_SNAPSHOT, hotKey, loader);
                }));
            }
            ExamSnapshotResponse first = futures.get(0).get(10, TimeUnit.SECONDS);
            for (Future<ExamSnapshotResponse> future : futures) {
                assertEquals(first.getId(), future.get(10, TimeUnit.SECONDS).getId(),
                        "所有线程都应拿到同一次回源的结果");
            }
            assertEquals(1, dbCalls.get(), "互斥锁应保证仅一个线程回源重建（spec「单线程重建」场景）");
        } finally {
            pool.shutdownNow();
        }

        // 回源完成后：缓存已回填、互斥锁已释放
        assertNotNull(redis.opsForValue().get(CacheConfig.KEY_PREFIX + CacheConfig.CACHE_EXAM_SNAPSHOT + ":" + hotKey));
        assertNull(redis.opsForValue().get(CacheMutexLoader.lockKey(
                CacheConfig.CACHE_EXAM_SNAPSHOT, hotKey)));
    }

    // ==================== 防击穿：败者等待超时兜底直源 ====================

    @Test
    void waiterTimesOutAndFallsBackToDirectSource() {
        // 场景：持锁线程迟迟不回填（回源极慢/阻塞），败者等待预算耗尽后必须兜底直接回源，
        // 宁可多打一次 DB 也不无限阻塞业务线程；同时不得误删他人持有的锁。
        Object hotKey = 9_100_000_010L;
        String cacheName = CacheConfig.CACHE_EXAM_SNAPSHOT;
        String lockKey = CacheMutexLoader.lockKey(cacheName, hotKey);

        // 模拟"他人"占锁且从不回填（胜者持有）：直接以外部 token 占住锁
        String heldBy = "stuck-holder-token";
        redis.opsForValue().set(lockKey, heldBy, Duration.ofSeconds(30));

        // 独立构造极短等待预算的 loader 实例：waitForRefill 按 waitMaxRounds 轮循环，
        // 恒在预算轮后超时（轮数驱动，不看真实时钟，确定性可判）
        CacheProperties tinyBudget = new CacheProperties();
        tinyBudget.setWaitIntervalMs(1);
        tinyBudget.setWaitMaxRounds(2);
        CacheMutexLoader loaderInstance = new CacheMutexLoader(redis, cacheManager, tinyBudget,
                new RedisLockHelper(redis));

        AtomicInteger dbCalls = new AtomicInteger();
        Supplier<ExamSnapshotResponse> loader = () -> {
            dbCalls.incrementAndGet();
            return sampleExamSnapshot((Long) hotKey);
        };

        ExamSnapshotResponse result = loaderInstance.load(cacheName, hotKey, loader);

        assertEquals(1, dbCalls.get(), "等待超时后应兜底直接回源一次，而非无限等待");
        assertEquals(hotKey, result.getId());
        assertEquals(heldBy, redis.opsForValue().get(lockKey), "兜底回源不得误删他人持有的锁");
    }
    // ==================== 防雪崩：TTL 随机抖动 ====================

    @Test
    void ttlJitterSpreadsExpirationAcrossKeys() {
        Cache cache = cacheManager.getCache(CacheConfig.CACHE_EXAM_SNAPSHOT);
        assertNotNull(cache);
        ExamSnapshotResponse sample = sampleExamSnapshot(9_100_000_003L);

        // 同一批写入 30 个 key（对应雪崩场景"大量 key 设定相同基础 TTL"）
        Set<Long> ttls = new HashSet<>();
        for (int i = 1; i <= 30; i++) {
            Object key = 9_200_000_000L + i;
            cache.put(key, sample);
            Long ttl = redis.getExpire(CacheConfig.KEY_PREFIX + CacheConfig.CACHE_EXAM_SNAPSHOT + ":" + key);
            assertNotNull(ttl, "缓存 key 应已写入");
            // 每 key 实际 TTL = 基础 2h + random(0, 20m]（application.yml snapshot-base-ttl/snapshot-jitter）
            assertThat(ttl).as("key=%s 的 TTL 应落在 [base, base+jitter] 区间", key)
                    .isBetween(7200L, 8400L);
            ttls.add(ttl);
        }
        // 抖动确实生效：30 个 key 的 TTL 不完全相同（否则退化为固定 TTL，同一时刻集体失效）
        assertThat(ttls.size()).as("同批 key 的 TTL 应存在差异（随机抖动生效）").isGreaterThan(1);
    }

    /** 纯函数级验证：withJitter 输出恒在 [base, base+jitter] 内，且固定种子下必然产生多个不同值 */
    @Test
    void jitterFunctionStaysWithinBounds() {
        Random random = new Random(20260912L);
        Duration base = Duration.ofHours(2);
        Duration jitter = Duration.ofMinutes(20);
        Set<Duration> distinct = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            Duration ttl = CacheConfig.withJitter(base, jitter, random);
            assertThat(ttl).isBetween(base, base.plus(jitter));
            distinct.add(ttl);
        }
        assertThat(distinct.size()).isGreaterThan(10);
    }

    // ==================== 造数 ====================

    private ExamSnapshotResponse sampleExamSnapshot(Long examId) {
        return new ExamSnapshotResponse(examId, examId, 1,
                objectMapper.createObjectNode().put("examId", examId),
                objectMapper.createObjectNode().put("paperId", examId),
                LocalDateTime.now());
    }

    private BusinessException assertLoadThrows(Supplier<ExamSnapshotResponse> loader, Object key) {
        try {
            cacheMutexLoader.load(CacheConfig.CACHE_EXAM_SNAPSHOT, key, loader);
        } catch (BusinessException e) {
            return e;
        }
        throw new AssertionError("预期抛出 BusinessException(NOT_FOUND)，实际未抛出");
    }
}

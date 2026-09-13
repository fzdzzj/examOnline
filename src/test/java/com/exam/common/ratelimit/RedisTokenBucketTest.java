package com.exam.common.ratelimit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Redis 令牌桶单元/集成测试（add-slow-sql-and-rate-limit task4）：连测试 Redis（db 15），
 * 直接对 RedisTokenBucket 跑真 Lua，验证：
 * <ol>
 *   <li>超限拒绝：容量耗尽是第 capacity+1 个请求须被拒（无欠账）；</li>
 *   <li>按时间补令牌：桶空后等待一段时间，按 qps 周期性补充令牌、可再次放行；</li>
 *   <li>并发原子性：并发 N 个请求同时扣令牌，放行数恰好等于桶容量（Lua 单线程串行 → 不超发）；</li>
 *   <li>过期重置：key 长时间无访问被自动 TTL 过期，下次访问重建满桶。</li>
 * </ol>
 * 各用例用独立 key 并 @AfterEach 清理，避免跨用例相互污染。
 */
@SpringBootTest
@ActiveProfiles("test")
class RedisTokenBucketTest {

    @Autowired
    private StringRedisTemplate redis;

    private static final String PREFIX = "exam:ratelimit:ut:";

    /** 装配 redis 注入后才可用：每次调用现场构建默认 TTL 桶（避免字段初始化早于 @Autowired）。 */
    private RedisTokenBucket bucket() {
        return new RedisTokenBucket(redis, 120);
    }

    @AfterEach
    void cleanup() {
        redis.delete(List.of(PREFIX + "reject", PREFIX + "refill", PREFIX + "concurrent", PREFIX + "expire"));
    }

    @Test
    @DisplayName("超限拒绝：容量耗尽后第 capacity+1 个请求被拒（桶无欠账）")
    void rejectsWhenExhausted() {
        String key = PREFIX + "reject";
        int passed = 0;
        for (int i = 0; i < 6; i++) {
            if (bucket().tryAcquire(key, 5, 0)) {
                passed++;
            }
        }
        assertEquals(5, passed, "容量=5：应恰好放行前 5 个、第 6 个被拒（qps=0 不补令牌）");
        assertTrue(!bucket().tryAcquire(key, 5, 0), "桶空后继续请求仍应被拒");
    }

    @Test
    @DisplayName("按时间补令牌：桶空后随时间推移按 qps 补充令牌，可再次放行若干")
    void refillsOverTime() throws InterruptedException {
        String key = PREFIX + "refill";
        for (int i = 0; i < 20; i++) {
            bucket().tryAcquire(key, 20, 5);
        }
        // 已排空（容量 20，qps=5/s）；等待 1.3s → 理论补充约 6.5 个令牌
        Thread.sleep(1300);
        int roll = 0;
        while (bucket().tryAcquire(key, 20, 5)) {
            roll++;
        }
        assertTrue(roll >= 5 && roll <= 9,
                "等待 1.3s 应补约 6.5 令牌，本次放行数应落在 [5,9]，实际=" + roll);
    }

    @Test
    @DisplayName("并发原子性：并发 50 个请求同时扣令牌，放行数恰等于桶容量 5（Lua 原子不超发）")
    void concurrentAtomicity() throws InterruptedException {
        String key = PREFIX + "concurrent";
        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger passed = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    if (bucket().tryAcquire(key, 5, 0)) {
                        passed.incrementAndGet();
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
        // 桶容量 5、qps=0：无论并发多大，Lua 单线程串行扣减，放行数必须恰好 5，绝不超发
        assertEquals(5, passed.get(),
                "并发扣令牌不得超发：放行数应恰好等于桶容量 5，实际=" + passed.get());
    }

    @Test
    @DisplayName("过期重置：key TTL 到期自动删除，下次访问重建满桶可再次放行")
    void resetsAfterExpiry() throws InterruptedException {
        // 用 TTL=1s 的桶验证自动过期：桶满状态随 key 删除而重置
        RedisTokenBucket ttlBucketOne = new RedisTokenBucket(redis, 1);
        String key = PREFIX + "expire";
        for (int i = 0; i < 5; i++) {
            ttlBucketOne.tryAcquire(key, 5, 0);
        }
        assertTrue(!ttlBucketOne.tryAcquire(key, 5, 0), "排空后应先被拒");
        Thread.sleep(1200);
        // key 已被自动过期删除 → 重建满桶 → 本次应放行（桶状态重置）
        assertTrue(ttlBucketOne.tryAcquire(key, 5, 0), "key 过期后应重建满桶并放行");
    }
}
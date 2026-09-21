package com.exam.cache;

import com.exam.common.cache.RedisLockHelper;
import com.exam.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 缓存/交卷共享的 Redis SETNX 锁助手测试（audit-concurrency-test-coverage 盘点补强，边界3「交卷锁 token 解锁」机制层）：
 * <ul>
 *   <li>tryLock：同一 key 全局仅一个持有者（单飞语义）；</li>
 *   <li>unlock 按 token 原子解锁：token 不匹配（TTL 到期易主后被他人持有）时不误删他人锁、返回 0；
 *       持有者本人 token 匹配才删、返回 1。</li>
 * </ul>
 * 纯确定性：易主由直接改写 Redis 值模拟（等价于 TTL 过期后他人 SETNX 成功），不依赖真实时钟，无并发线程。
 */
class RedisLockHelperTest extends IntegrationTestBase {

    @Autowired
    private StringRedisTemplate redis;

    private RedisLockHelper helper;

    @BeforeEach
    void setUp() {
        helper = new RedisLockHelper(redis);
    }

    @Test
    void tryLockIsExclusivePerKey() {
        String key = uniqueLockKey();
        assertTrue(helper.tryLock(key, "owner-first", Duration.ofSeconds(30)), "首个请求应拿到锁");
        assertFalse(helper.tryLock(key, "owner-second", Duration.ofSeconds(30)),
                "同一 key 已被持有，第二请求抢锁失败（单飞）");
    }

    @Test
    void unlockByStaleTokenDoesNotDeleteReOwnedLock() {
        // 场景：A 持锁后工作超过 TTL，锁被 B 重新抢到（易主）。
        String key = uniqueLockKey();
        String tokenA = "owner-A";
        String tokenB = "owner-B";
        assertTrue(helper.tryLock(key, tokenA, Duration.ofSeconds(30)));
        // 模拟 A 的锁 TTL 到期后 B 抢占：直接改写为 B 的 token（等价于易主后的 Redis 状态）
        redis.opsForValue().set(key, tokenB, Duration.ofSeconds(30));

        // A 用旧 token 解锁 → compare-and-delete 校验失败，返回 0 且不删 B 的锁
        assertEquals(0L, helper.unlock(key, tokenA), "旧 token 应解锁失败（返回 0）");
        assertEquals(tokenB, redis.opsForValue().get(key), "不得误删他人（B）持有的锁");

        // B 用本人 token 解锁 → 返回 1，锁被清除
        assertEquals(1L, helper.unlock(key, tokenB), "持有者本人解锁应成功（返回 1）");
        assertNull(redis.opsForValue().get(key), "锁应已被清除");
    }

    @Test
    void unlockReturnsZeroWhenKeyAbsent() {
        assertEquals(0L, helper.unlock(uniqueLockKey(), "nobody"), "锁不存在时解锁应返回 0（幂等）");
    }

    private String uniqueLockKey() {
        return "exam:lock:ut:" + System.nanoTime();
    }
}
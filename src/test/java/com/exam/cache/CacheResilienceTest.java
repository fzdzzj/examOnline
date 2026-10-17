package com.exam.cache;

import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.config.CacheConfig;
import com.exam.config.CacheProperties;
import com.exam.config.RedisCacheDegradedHandler;
import com.exam.common.cache.CacheMutexLoader;
import com.exam.common.cache.RedisLockHelper;
import com.exam.monitoring.metrics.BusinessMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Spring Cache Redis 故障降级单测（harden-spring-cache-resilience）：
 * <ul>
 *   <li>CacheErrorHandler 四动作：ERROR + 降级计数、不向切面上抛（切面静默回源 DB / 放弃回填 /
 *       失效延迟生效）；</li>
 *   <li>CacheMutexLoader 直连触点（快速路径读 / 空标记读 / 互斥锁 / 回填写 / 解锁 / 空标记清除）：
 *       Redis 全故障时 load 仍返回 DB 回源值（互斥与穿透防护暂缺、正确性不变）；NOT_FOUND 业务
 *       语义异常原样传播（降级口只认不可用不认业务结果）。</li>
 * </ul>
 */
class CacheResilienceTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private CacheManager cacheManager;
    private Cache cache;
    private RedisLockHelper lockHelper;
    private BusinessMetrics businessMetrics;
    private CacheMutexLoader loader;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        cacheManager = mock(CacheManager.class);
        cache = mock(Cache.class);
        when(cacheManager.getCache(anyString())).thenReturn(cache);
        lockHelper = mock(RedisLockHelper.class);
        businessMetrics = mock(BusinessMetrics.class);
        CacheProperties props = new CacheProperties();
        props.setWaitIntervalMs(1);
        props.setWaitMaxRounds(2);
        loader = new CacheMutexLoader(redis, cacheManager, props, lockHelper, businessMetrics);
    }

    // ==================== CacheErrorHandler 四动作 ====================

    @Test
    void handlerSwallowsGetErrorAndCounts() {
        RedisCacheDegradedHandler handler = new RedisCacheDegradedHandler(businessMetrics);
        when(cache.getName()).thenReturn("examSnapshot");

        RuntimeException boom = new RuntimeException("redis down");
        handler.handleCacheGetError(boom, cache, "k1");
        handler.handleCachePutError(boom, cache, "k1", "v");
        handler.handleCacheEvictError(boom, cache, "k1");
        handler.handleCacheClearError(boom, cache);

        verify(businessMetrics).countCacheDegraded("get");
        verify(businessMetrics).countCacheDegraded("put");
        verify(businessMetrics).countCacheDegraded("evict");
        verify(businessMetrics).countCacheDegraded("clear");
    }

    @Test
    void cacheConfigWiresCustomErrorHandlerViaCachingConfigurer() {
        // 接线契约：CacheConfig 必须经 CachingConfigurer 供出降级 handler（移除实现或覆写即确定性红）
        assertTrue(org.springframework.cache.annotation.CachingConfigurer.class.isAssignableFrom(CacheConfig.class),
                "CacheConfig 必须实现 CachingConfigurer（CacheErrorHandler 接线载体）");
        CacheConfig config = new CacheConfig(new CacheProperties(), businessMetrics);
        assertTrue(config.errorHandler() instanceof RedisCacheDegradedHandler,
                "errorHandler 必须供出 Redis 故障降级 handler");
    }

    // ==================== CacheMutexLoader：Redis 全故障仍回源 ====================

    private void makeEverythingThrow() {
        RuntimeException boom = new RuntimeException("redis down");
        when(cache.get(any())).thenThrow(boom);
        doThrow(boom).when(cache).put(any(), any());
        when(valueOps.get(anyString())).thenThrow(boom);
        doThrow(boom).when(valueOps).set(anyString(), anyString(), any(Duration.class));
        doThrow(boom).when(redis).delete(anyString());
        when(lockHelper.tryLock(anyString(), anyString(), any(Duration.class))).thenThrow(boom);
    }

    @Test
    void loadReturnsDbValueWhenRedisFullyDown() {
        makeEverythingThrow();
        Supplier<String> dbLoader = () -> "from-db";

        assertEquals("from-db", loader.load(CacheConfig.CACHE_EXAM_SNAPSHOT, 1L, dbLoader),
                "Redis 全故障时必须降级直接回源 DB（互斥/穿透防护暂缺，正确性不变）");

        // get：快速路径 + 空标记读 + （锁降级后）等待轮询逐轮读——按降级口径逐触点计数
        verify(businessMetrics, org.mockito.Mockito.atLeastOnce()).countCacheDegraded("get");
        verify(businessMetrics).countCacheDegraded("lock");
        verify(businessMetrics).countCacheDegraded("put");
    }

    @Test
    void notFoundBusinessExceptionStillPropagatesWhenRedisDown() {
        makeEverythingThrow();
        Supplier<String> dbLoader = () -> {
            throw new BusinessException(ResponseCode.NOT_FOUND, "考试尚未发布或快照不存在");
        };

        BusinessException ex = assertThrows(BusinessException.class,
                () -> loader.load(CacheConfig.CACHE_EXAM_SNAPSHOT, 1L, dbLoader));
        assertEquals(ResponseCode.NOT_FOUND.getCode(), ex.getCode(),
                "业务语义异常（NOT_FOUND）不走降级口，原样传播");
    }

    @Test
    void clearEmptyMarkerDegradesQuietlyWhenRedisDown() {
        doThrow(new RuntimeException("redis down")).when(redis).delete(anyString());

        loader.clearEmptyMarker(CacheConfig.CACHE_EXAM_SNAPSHOT, 1L);

        verify(businessMetrics).countCacheDegraded("evict");
    }

    @Test
    void fastPathCacheHitUnaffectedWhenRedisHealthy() {
        // 回归：正常路径语义零变化——快速路径命中直接返回、不触发任何降级计数
        Cache.ValueWrapper hit = mock(Cache.ValueWrapper.class);
        when(hit.get()).thenReturn("cached-value");
        when(cache.get(any())).thenReturn(hit);

        assertEquals("cached-value", loader.load(CacheConfig.CACHE_EXAM_SNAPSHOT, 2L, () -> "from-db"));
        verify(businessMetrics, org.mockito.Mockito.never()).countCacheDegraded(anyString());
    }

    @Test
    void waitForRefillFallsBackToDbWhenRefillWritesFail() {
        // 持锁成功但 Redis 随后故障：轮询读全失败 → 耗尽预算 → 兜底回源 + 回填失败放弃 → 仍返回 DB 值
        RuntimeException boom = new RuntimeException("redis down");
        when(lockHelper.tryLock(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(cache.get(any())).thenThrow(boom);
        when(valueOps.get(anyString())).thenThrow(boom);
        doThrow(boom).when(valueOps).set(anyString(), anyString(), any(Duration.class));

        assertEquals("from-db", loader.load(CacheConfig.CACHE_EXAM_SNAPSHOT, 3L, () -> "from-db"),
                "持锁后 Redis 故障：等待轮询降级、兜底回源、回填放弃，DB 值照常返回");
        verify(lockHelper).tryLock(eq(CacheMutexLoader.lockKey(CacheConfig.CACHE_EXAM_SNAPSHOT, 3L)),
                anyString(), any(Duration.class));
    }
}

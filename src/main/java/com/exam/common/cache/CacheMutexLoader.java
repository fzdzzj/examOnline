package com.exam.common.cache;

import com.exam.common.BusinessException;
import com.exam.common.RequestIdFilter;
import com.exam.common.ResponseCode;
import com.exam.config.CacheConfig;
import com.exam.config.CacheProperties;
import com.exam.monitoring.metrics.BusinessMetrics;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 缓存互斥回源器（add-performance-deepening 阶段 8「缓存三防」核心工具），
 * 与 {@code @Cacheable} 组合使用：注解切面负责"命中不出方法"，本类负责"未命中时如何安全回源"。
 *
 * <p><b>三防分工</b>：
 * <ul>
 *   <li><b>防穿透（空值缓存）</b>：查询不存在的键时 DB 必然反复被无效请求打穿。
 *       本类把 NOT_FOUND 结果以<b>短 TTL 空标记</b>缓存到 Redis（key 见 {@link #emptyMarkerKey}），
 *       标记存续期内同 key 查询直接复用原 404 语义、不再访问 DB；TTL 取短值（默认 30s），
 *       数据随后真正出现的窗口极小（发布/生成快照处会主动清除标记，见 {@link #clearEmptyMarker}）；</li>
 *   <li><b>防击穿（互斥锁重建）</b>：热点 key 在失效瞬间若放任并发回源，DB 会被同一查询打满
 *       （开考 5000 人同时拉卷即此场景）。本类用 <b>Redis SETNX 互斥锁</b>（不带 Redisson 依赖）
 *       保证同一 key 全局仅一个线程回源重建，其余线程轮询等待回填的缓存值/空标记；
 *       锁带 TTL 防持有者崩溃死锁，等待超时后兜底直接回源（宁可多打一次 DB，不无限阻塞业务线程）；</li>
 *   <li><b>防雪崩（TTL 抖动）</b>：在 CacheConfig 的 TTL 函数中实现（每 key 基础 TTL + 随机抖动），
 *       本类不参与。</li>
 * </ul>
 *
 * <p><b>与 {@code @Cacheable} 的协作时序</b>：切面查缓存未命中 → 进入业务方法 → 本类抢锁回源；
 * 持锁线程回源成功后<b>主动回填缓存</b>再返回（切面随后会再写一次，幂等），
 * 等待线程即可从缓存读到值而无需回源。
 *
 * <p><b>Redis 故障降级（harden-spring-cache-resilience）</b>：本类绕过缓存切面直连 Cache/Redis 的
 * 触点（快速路径读、空标记读写、互斥锁、回填写、解锁）逐点包降级口径——基础设施异常时
 * ERROR 日志 + 降级计数（exam.cache.degraded，tag=action）后静默继续：读取失败视为未命中回源 DB、
 * 空标记读写失败视为无标记、互斥锁不可用跳过单飞直接回源（与交卷锁降级同哲学：互斥只是防抖器，
 * 回源本身幂等）、回填失败放弃本次（下次读取重新回源）、解锁失败交由锁 TTL 过期自愈。
 * 业务语义异常（loader 抛出的 NOT_FOUND 等）不在降级范围、原样传播。
 */
@Slf4j
@Component
public class CacheMutexLoader {

    private final StringRedisTemplate redis;
    private final CacheManager cacheManager;
    private final CacheProperties props;
    private final RedisLockHelper lockHelper;
    private final BusinessMetrics businessMetrics;

    public CacheMutexLoader(StringRedisTemplate redis, CacheManager cacheManager, CacheProperties props,
                            RedisLockHelper lockHelper, BusinessMetrics businessMetrics) {
        this.redis = redis;
        this.cacheManager = cacheManager;
        this.props = props;
        this.lockHelper = lockHelper;
        this.businessMetrics = businessMetrics;
    }

    /**
     * 带互斥锁的回源加载：缓存命中（含其他实例回填）直接返回；
     * 未命中则抢 SETNX 锁回源（胜者回填缓存），败者等待回填，等待超时兜底直源。
     *
     * @param cacheName Spring Cache 名（决定缓存 key 前缀与 TTL 档位，见 CacheConfig）
     * @param key       与 {@code @Cacheable(key=...)} 完全一致的缓存 key
     * @param loader    回源函数：命中即返回业务数据；查无结果抛 BusinessException(NOT_FOUND)（将被空标记缓存）
     */
    public <T> T load(String cacheName, Object key, Supplier<T> loader) {
        Cache cache = cacheManager.getCache(cacheName);

        // 快速路径：其他实例/线程已回填时直接命中（@Cacheable 切面通常已先行检查，此处兜底使本方法自洽可独立复用）
        // 降级：Redis 故障时 cache.get 抛异常 → 视为未命中，回源 DB
        Cache.ValueWrapper wrapper = cacheGetQuietly(cacheName, key, cache);
        if (wrapper != null) {
            return uncheckedValue(wrapper);
        }

        // ① 防穿透：空标记命中 → 复用原 404 语义直接抛出，不回源 DB
        // 降级：标记读取失败视为无标记（穿透防护暂缺，直接回源 DB，正确性不变）
        String markerMessage = getEmptyMarkerQuietly(cacheName, key);
        if (markerMessage != null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, markerMessage);
        }

        // ② 防击穿：SETNX 互斥锁（带 TTL 防持锁线程崩溃后死锁），同一 key 全局仅一个线程回源
        // 降级：锁不可用（Redis 异常）→ 跳过互斥与等待直接回源——互斥只是防抖器，回源幂等，
        // 与交卷锁降级放行同哲学（正确性由 DB 幂等保证，Redis 降级保可用性）
        String lockKey = lockKey(cacheName, key);
        String token = UUID.randomUUID().toString();
        if (tryLockQuietly(cacheName, key, lockKey, token)) {
            try {
                return loadAndFill(cacheName, key, cache, loader);
            } finally {
                // 必须按 token 解锁：TTL 到期后锁可能已易主，无条件 del 会删掉别人的锁
                // 降级：解锁失败交由锁 TTL 过期自愈（防抖半径短暂损失，无正确性影响）
                unlockQuietly(lockKey, token);
            }
        }

        // ③ 未抢到锁：等待持锁线程回填（缓存值或空标记），期间不回源
        return waitForRefill(cacheName, key, cache, loader);
    }

    /** 清除空标记：数据真正生成后调用（如考试发布/试卷快照生成），避免短 TTL 内新数据被旧空标记掩盖 */
    public void clearEmptyMarker(String cacheName, Object key) {
        try {
            redis.delete(emptyMarkerKey(cacheName, key));
        } catch (Exception e) {
            // 降级：标记清除失败 → 旧空标记按其短 TTL（null-ttl-seconds，默认 30s）自然过期，
            // 窗口内新数据查询仍被掩盖为 404——半径受空标记 TTL 自限，恢复后重试即可
            log.error("缓存空标记清除降级：Redis 删除失败，旧标记按短 TTL 自然过期, cache={}, key={}, 异常={}, requestId={}",
                    cacheName, key, e.getMessage(), MDC.get(RequestIdFilter.MDC_KEY), e);
            businessMetrics.countCacheDegraded("evict");
        }
    }

    /** 回源并回填：成功则主动写缓存（供等待线程立刻读到）；NOT_FOUND 则写短 TTL 空标记后原样抛出 */
    private <T> T loadAndFill(String cacheName, Object key, Cache cache, Supplier<T> loader) {
        try {
            T value = loader.get();
            if (cache != null) {
                // 手动回填：不必等 @Cacheable 切面在本方法返回后再写，等待线程的下一轮轮询即可命中
                // 降级：回填失败放弃本次（DB 结果不丢、照常返回调用方），下次读取重新回源
                try {
                    cache.put(key, value);
                } catch (Exception e) {
                    log.error("缓存回填降级：Redis 写入失败，放弃本次回填, cache={}, key={}, 异常={}, requestId={}",
                            cacheName, key, e.getMessage(), MDC.get(RequestIdFilter.MDC_KEY), e);
                    businessMetrics.countCacheDegraded("put");
                }
            }
            return value;
        } catch (BusinessException e) {
            // 防穿透：空结果缓存（短 TTL）。值存原错误信息，空标记命中时抛出与直查 DB 完全一致的 404
            if (props.isNullCacheEnabled() && e.getCode() == ResponseCode.NOT_FOUND.getCode()) {
                try {
                    redis.opsForValue().set(emptyMarkerKey(cacheName, key), e.getMessage(),
                            Duration.ofSeconds(props.getNullTtlSeconds()));
                    log.info("缓存空标记写入: cache={} key={} ttl={}s", cacheName, key, props.getNullTtlSeconds());
                } catch (Exception ex) {
                    // 降级：空标记写失败 → 穿透防护暂缺（同 key 重复查询将直击 DB），正确性不受影响
                    log.error("缓存空标记写入降级：Redis 写入失败, cache={}, key={}, 异常={}, requestId={}",
                            cacheName, key, ex.getMessage(), MDC.get(RequestIdFilter.MDC_KEY), ex);
                    businessMetrics.countCacheDegraded("put");
                }
            }
            throw e;
        }
    }

    /** 败者等待：按间隔轮询缓存回填与空标记；总等待超时后兜底直接回源（锁本身也有 TTL 自动过期兜底） */
    @SuppressWarnings("unchecked")
    private <T> T waitForRefill(String cacheName, Object key, Cache cache, Supplier<T> loader) {
        for (int round = 0; round < props.getWaitMaxRounds(); round++) {
            try {
                Thread.sleep(props.getWaitIntervalMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            // 降级：轮询期 Redis/Cache 读取失败视为"本轮无回填"，轮询耗尽后走兜底直接回源
            String markerMessage = getEmptyMarkerQuietly(cacheName, key);
            if (markerMessage != null) {
                throw new BusinessException(ResponseCode.NOT_FOUND, markerMessage);
            }
            Cache.ValueWrapper wrapper = cacheGetQuietly(cacheName, key, cache);
            if (wrapper != null) {
                return uncheckedValue(wrapper);
            }
        }
        log.warn("缓存互斥等待超时，兜底直接回源: cache={} key={}", cacheName, key);
        return loadAndFill(cacheName, key, cache, loader);
    }

    // ==================== Redis 降级包装（harden-spring-cache-resilience） ====================

    /** 缓存读（快速路径/等待轮询共用）：Redis 故障时 ERROR + 计数后视为未命中（返回 null）。 */
    private Cache.ValueWrapper cacheGetQuietly(String cacheName, Object key, Cache cache) {
        if (cache == null) {
            return null;
        }
        try {
            return cache.get(key);
        } catch (Exception e) {
            log.error("缓存读取降级：Redis 查询失败，视为未命中回源 DB, cache={}, key={}, 异常={}, requestId={}",
                    cacheName, key, e.getMessage(), MDC.get(RequestIdFilter.MDC_KEY), e);
            businessMetrics.countCacheDegraded("get");
            return null;
        }
    }

    /** 空标记读：Redis 故障时视为无标记（穿透防护暂缺，回源 DB）。 */
    private String getEmptyMarkerQuietly(String cacheName, Object key) {
        try {
            return redis.opsForValue().get(emptyMarkerKey(cacheName, key));
        } catch (Exception e) {
            log.error("缓存空标记读取降级：Redis 查询失败，视为无标记回源 DB, cache={}, key={}, 异常={}, requestId={}",
                    cacheName, key, e.getMessage(), MDC.get(RequestIdFilter.MDC_KEY), e);
            businessMetrics.countCacheDegraded("get");
            return null;
        }
    }

    /** 互斥锁获取：Redis 故障时返回 false（调用方跳过互斥与等待直接回源——防抖半径损失，正确性不变）。 */
    private boolean tryLockQuietly(String cacheName, Object key, String lockKey, String token) {
        try {
            return lockHelper.tryLock(lockKey, token, Duration.ofSeconds(props.getLockTtlSeconds()));
        } catch (Exception e) {
            log.error("缓存互斥锁降级：Redis SETNX 失败，跳过互斥直接回源, cache={}, key={}, 异常={}, requestId={}",
                    cacheName, key, e.getMessage(), MDC.get(RequestIdFilter.MDC_KEY), e);
            businessMetrics.countCacheDegraded("lock");
            return false;
        }
    }

    /** 按 token 解锁：Redis 故障时交由锁 TTL 过期自愈（防抖半径短暂损失，无正确性影响）。 */
    private void unlockQuietly(String lockKey, String token) {
        try {
            lockHelper.unlock(lockKey, token);
        } catch (Exception e) {
            log.error("缓存互斥锁解锁降级：Redis 脚本执行失败，交由锁 TTL 过期自愈, lockKey={}, 异常={}, requestId={}",
                    lockKey, e.getMessage(), MDC.get(RequestIdFilter.MDC_KEY), e);
            businessMetrics.countCacheDegraded("unlock");
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T uncheckedValue(Cache.ValueWrapper wrapper) {
        return (T) wrapper.get();
    }

    /** 互斥锁 key：exam:cache:lock:{缓存名}:{key}（单测断言与排障用） */
    public static String lockKey(String cacheName, Object key) {
        return CacheConfig.KEY_PREFIX + "lock:" + cacheName + ":" + key;
    }

    /** 空标记 key：exam:cache:{缓存名}:empty:{key}（与值 key 同前缀不同段，业务 key 均为数字/数字复合串，无碰撞） */
    public static String emptyMarkerKey(String cacheName, Object key) {
        return CacheConfig.KEY_PREFIX + cacheName + ":empty:" + key;
    }
}

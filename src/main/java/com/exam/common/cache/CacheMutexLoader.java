package com.exam.common.cache;

import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.config.CacheConfig;
import com.exam.config.CacheProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
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
 */
@Slf4j
@Component
public class CacheMutexLoader {

    /**
     * 原子解锁脚本：校验锁 token 一致才删除——防止自己的锁已过期、被其他线程持有时误删他人锁。
     */
    private static final RedisScript<Long> UNLOCK_SCRIPT = RedisScript.of(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final CacheManager cacheManager;
    private final CacheProperties props;

    public CacheMutexLoader(StringRedisTemplate redis, CacheManager cacheManager, CacheProperties props) {
        this.redis = redis;
        this.cacheManager = cacheManager;
        this.props = props;
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
        if (cache != null) {
            Cache.ValueWrapper wrapper = cache.get(key);
            if (wrapper != null) {
                return uncheckedValue(wrapper);
            }
        }

        // ① 防穿透：空标记命中 → 复用原 404 语义直接抛出，不回源 DB
        String markerMessage = redis.opsForValue().get(emptyMarkerKey(cacheName, key));
        if (markerMessage != null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, markerMessage);
        }

        // ② 防击穿：SETNX 互斥锁（带 TTL 防持锁线程崩溃后死锁），同一 key 全局仅一个线程回源
        String lockKey = lockKey(cacheName, key);
        String token = UUID.randomUUID().toString();
        Boolean locked = redis.opsForValue()
                .setIfAbsent(lockKey, token, Duration.ofSeconds(props.getLockTtlSeconds()));
        if (Boolean.TRUE.equals(locked)) {
            try {
                return loadAndFill(cacheName, key, cache, loader);
            } finally {
                unlock(lockKey, token);
            }
        }

        // ③ 未抢到锁：等待持锁线程回填（缓存值或空标记），期间不回源
        return waitForRefill(cacheName, key, cache, loader);
    }

    /** 清除空标记：数据真正生成后调用（如考试发布/试卷快照生成），避免短 TTL 内新数据被旧空标记掩盖 */
    public void clearEmptyMarker(String cacheName, Object key) {
        redis.delete(emptyMarkerKey(cacheName, key));
    }

    /** 回源并回填：成功则主动写缓存（供等待线程立刻读到）；NOT_FOUND 则写短 TTL 空标记后原样抛出 */
    private <T> T loadAndFill(String cacheName, Object key, Cache cache, Supplier<T> loader) {
        try {
            T value = loader.get();
            if (cache != null) {
                // 手动回填：不必等 @Cacheable 切面在本方法返回后再写，等待线程的下一轮轮询即可命中
                cache.put(key, value);
            }
            return value;
        } catch (BusinessException e) {
            // 防穿透：空结果缓存（短 TTL）。值存原错误信息，空标记命中时抛出与直查 DB 完全一致的 404
            if (props.isNullCacheEnabled() && e.getCode() == ResponseCode.NOT_FOUND.getCode()) {
                redis.opsForValue().set(emptyMarkerKey(cacheName, key), e.getMessage(),
                        Duration.ofSeconds(props.getNullTtlSeconds()));
                log.info("缓存空标记写入: cache={} key={} ttl={}s", cacheName, key, props.getNullTtlSeconds());
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
            String markerMessage = redis.opsForValue().get(emptyMarkerKey(cacheName, key));
            if (markerMessage != null) {
                throw new BusinessException(ResponseCode.NOT_FOUND, markerMessage);
            }
            if (cache != null) {
                Cache.ValueWrapper wrapper = cache.get(key);
                if (wrapper != null) {
                    return uncheckedValue(wrapper);
                }
            }
        }
        log.warn("缓存互斥等待超时，兜底直接回源: cache={} key={}", cacheName, key);
        return loadAndFill(cacheName, key, cache, loader);
    }

    @SuppressWarnings("unchecked")
    private <T> T uncheckedValue(Cache.ValueWrapper wrapper) {
        return (T) wrapper.get();
    }

    private void unlock(String lockKey, String token) {
        redis.execute(UNLOCK_SCRIPT, List.of(lockKey), token);
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

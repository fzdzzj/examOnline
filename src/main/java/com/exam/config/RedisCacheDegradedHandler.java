package com.exam.config;

import com.exam.common.RequestIdFilter;
import com.exam.monitoring.metrics.BusinessMetrics;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;

/**
 * Spring Cache Redis 故障降级处理器（harden-spring-cache-resilience，runbook 矩阵
 * 「exam:cache:*（Spring Cache：快照/详情）」行由硬依赖改降级口径的落地）：
 * 由 {@link CacheConfig} 经 {@code CachingConfigurer#cacheErrorHandler} 接线到缓存切面，
 * 切面的读（@Cacheable get）/写（@CachePut put）/失效（@CacheEvict evict/clear）遇 Redis 异常
 * 不再裸穿 500，而是 ERROR 日志 + 降级计数（exam.cache.degraded，tag=action）后静默继续：
 * <ul>
 *   <li><b>get 失败 → 静默回源 DB</b>：缓存只是加速器，DB 是正确性同源——读路径正确性零变化，
 *       只是失去加速；</li>
 *   <li><b>put 失败 → 静默放弃本次回填</b>：方法返回值照常交给调用方（DB 读结果不丢），
 *       下次读取重新回源，缓存按 TTL 与互斥回源自愈；</li>
 *   <li><b>evict/clear 失败 → 脏读窗口论证（重点）</b>：失效写不生效，旧值存续至 TTL 自然过期——
 *       ①考试详情（examDetail）走短 TTL（detail-base-ttl 5m + jitter 1m）兜底自愈，脏读窗口
 *       ≤ 一个 TTL 周期；②快照类（examSnapshot/paperSnapshot）发布后<b>只读不可变</b>
 *       （CacheConfig 类注释），缓存与 DB 天然一致，不存在"DB 改了缓存还是旧值"的脏读语义；
 *       替代口径（失效异常裸穿 500 使考试写路径全停摆）严格更糟。</li>
 * </ul>
 * 打点口径与限流 fail-open / 鉴权降级 / 交卷锁降级同源：降级放行（或跳过该步）+ ERROR 日志
 * （含 requestId）+ 降级计数 + 对应告警规则（CacheDegraded），不新增第二套降级哲学。
 * 互斥回源器（{@code CacheMutexLoader}）绕过切面直连 Cache/Redis 的触点在本类之外同口径包裹。
 */
@Slf4j
public class RedisCacheDegradedHandler implements CacheErrorHandler {

    private final BusinessMetrics businessMetrics;

    public RedisCacheDegradedHandler(BusinessMetrics businessMetrics) {
        this.businessMetrics = businessMetrics;
    }

    @Override
    public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
        log.error("Spring Cache 降级：缓存读取失败，静默回源 DB, cache={}, key={}, 异常={}, requestId={}",
                cache.getName(), key, e.getMessage(), MDC.get(RequestIdFilter.MDC_KEY), e);
        businessMetrics.countCacheDegraded("get");
    }

    @Override
    public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
        log.error("Spring Cache 降级：缓存回填失败，本次放弃回填（下次读取重新回源）, cache={}, key={}, 异常={}, requestId={}",
                cache.getName(), key, e.getMessage(), MDC.get(RequestIdFilter.MDC_KEY), e);
        businessMetrics.countCacheDegraded("put");
    }

    @Override
    public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
        // 失效延迟生效：脏读窗口受缓存 TTL 自愈（详情短 TTL；快照只读不可变无脏读语义），见类注释
        log.error("Spring Cache 降级：缓存失效失败，旧值存续至 TTL 自愈, cache={}, key={}, 异常={}, requestId={}",
                cache.getName(), key, e.getMessage(), MDC.get(RequestIdFilter.MDC_KEY), e);
        businessMetrics.countCacheDegraded("evict");
    }

    @Override
    public void handleCacheClearError(RuntimeException e, Cache cache) {
        log.error("Spring Cache 降级：缓存清空失败，旧值存续至 TTL 自愈, cache={}, 异常={}, requestId={}",
                cache.getName(), e.getMessage(), MDC.get(RequestIdFilter.MDC_KEY), e);
        businessMetrics.countCacheDegraded("clear");
    }
}

package com.exam.config;

import com.exam.exam.dto.ExamDetailResponse;
import com.exam.exam.dto.ExamSnapshotResponse;
import com.exam.paper.dto.PaperSnapshotResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 缓存配置（add-performance-deepening 阶段 8「缓存三防」，Spring Cache 抽象 + Redis 底层）：
 *
 * <p><b>为什么快照缓存天然一致</b>：考试快照/试卷快照在发布时一次性生成，之后<b>只读不更新</b>
 * （题目/试卷再修改均不影响已生成的快照副本，答题/判分/回看一律读快照）——缓存里存的是不可变数据，
 * 永远不会出现"DB 改了缓存还是旧值"的问题，因此可以用长 TTL、无需失效逻辑；
 * 只有 Exam 这类有更新路径的数据才用短 TTL + 写路径显式 @CacheEvict。
 *
 * <p><b>三防落点</b>（分工见 CacheMutexLoader）：
 * <ul>
 *   <li>防雪崩 → 本类：每 key TTL = 基础 TTL + 随机抖动（entryTtl(TtlFunction) 按 key 独立计算），
 *       应用冷启动/批量回填后 key 不在同一时刻集体过期，避免过期瞬间流量一起砸向 DB；</li>
 *   <li>防穿透 → CacheMutexLoader：查无结果（NOT_FOUND）缓存短 TTL 空标记，
 *       恶意/异常的不存在 ID 反复查询不再直击 DB；</li>
 *   <li>防击穿 → CacheMutexLoader：热点 key 失效瞬间用 Redis SETNX 互斥锁保证仅一个线程回源重建，
 *       其余线程等待回填（开考 5000 人拉卷场景的 DB 保护伞）。</li>
 * </ul>
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /** 考试快照缓存：只读不可变（发布后不更新），长 TTL + 抖动 */
    public static final String CACHE_EXAM_SNAPSHOT = "examSnapshot";

    /** 试卷快照缓存：只读不可变（锁定后不更新），长 TTL + 抖动 */
    public static final String CACHE_PAPER_SNAPSHOT = "paperSnapshot";

    /** 考试详情缓存：有更新路径，短 TTL + 更新处 @CacheEvict 显式失效 */
    public static final String CACHE_EXAM_DETAIL = "examDetail";

    /** Redis key 前缀：exam:cache:{缓存名}:{key}；空标记/互斥锁 key 见 CacheMutexLoader */
    public static final String KEY_PREFIX = "exam:cache:";

    private final CacheProperties props;

    public CacheConfig(CacheProperties props) {
        this.props = props;
    }

    @Bean
    @ConditionalOnMissingBean(CacheManager.class)
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        // 未显式登记的缓存走默认配置（GenericJackson2JsonRedisSerializer 带多态类型信息，兜底任意类型）；
        // 新增热点缓存时应在此处显式登记（JSON 序列化 + 对应 TTL 档位），保持序列化格式可控
        RedisCacheConfiguration defaultConfig = baseConfig()
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer()))
                .entryTtl(Duration.ofMinutes(30));

        Map<String, RedisCacheConfiguration> configs = new HashMap<>();
        // 快照类：发布/锁定后只读不可变，缓存与 DB 天然一致（见类注释），用长基础 TTL
        configs.put(CACHE_EXAM_SNAPSHOT, typedConfig(objectMapper, ExamSnapshotResponse.class)
                .entryTtl(jitterTtlFunction(props.getSnapshotBaseTtl(), props.getSnapshotJitter())));
        configs.put(CACHE_PAPER_SNAPSHOT, typedConfig(objectMapper, PaperSnapshotResponse.class)
                .entryTtl(jitterTtlFunction(props.getSnapshotBaseTtl(), props.getSnapshotJitter())));
        // 考试详情：有更新路径，短 TTL 兜底（缓存层过期自愈）+ 写路径 @CacheEvict 显式失效
        configs.put(CACHE_EXAM_DETAIL, typedConfig(objectMapper, ExamDetailResponse.class)
                .entryTtl(jitterTtlFunction(props.getDetailBaseTtl(), props.getDetailJitter())));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(configs)
                .build();
    }

    /** 统一 key 前缀 + 禁用 null 值缓存（空值缓存由 CacheMutexLoader 的空标记承载，短 TTL 可控） */
    private RedisCacheConfiguration baseConfig() {
        return RedisCacheConfiguration.defaultCacheConfig()
                .computePrefixWith(name -> KEY_PREFIX + name + ":")
                .disableCachingNullValues();
    }

    /**
     * 按 DTO 类型定制的 JSON 序列化：写入时用静态类型生成纯 JSON（无 @class 多态信息），
     * 读取时反序列化回声明类型——复用 Spring 容器的 ObjectMapper（已含 JavaTimeModule，
     * LocalDateTime/JsonNode 均可正确往返），序列化格式跨缓存统一、可读可排查。
     */
    private RedisCacheConfiguration typedConfig(ObjectMapper objectMapper, Class<?> valueType) {
        return baseConfig().serializeValuesWith(RedisSerializationContext.SerializationPair
                .fromSerializer(new Jackson2JsonRedisSerializer<>(objectMapper, valueType)));
    }

    /** 防雪崩 TTL 函数：每 key 独立计算 base + random(0, jitter)（同一批写入的 key 过期时间分散开） */
    private RedisCacheWriter.TtlFunction jitterTtlFunction(Duration base, Duration jitter) {
        return (key, value) -> withJitter(base, jitter, ThreadLocalRandom.current());
    }

    /** 防雪崩抖动计算（公开供单测）：实际 TTL ∈ [base, base + jitter]，jitter 为 0 时退化为固定 TTL */
    public static Duration withJitter(Duration base, Duration jitter, Random random) {
        if (jitter.isZero() || jitter.isNegative()) {
            return base;
        }
        return base.plusMillis(random.nextLong(jitter.toMillis() + 1));
    }
}

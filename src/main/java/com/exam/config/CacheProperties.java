package com.exam.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 缓存三防配置（前缀 exam.cache，add-performance-deepening 阶段 8）：
 * <ul>
 *   <li>防雪崩：实际 TTL = 基础 TTL + random(0, 抖动)，批量写入的 key 不在同一时刻集体过期；</li>
 *   <li>防穿透：查无结果（NOT_FOUND）时写入短 TTL 空标记，同 key 后续查询不再直击 DB（可开关）；</li>
 *   <li>防击穿：Redis SETNX 互斥锁保证同一热点 key 仅一个线程回源重建
 *       （lock-ttl-seconds 为持有时长上限，防持锁线程崩溃后死锁）。</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "exam.cache")
public class CacheProperties {

    /** 快照类缓存（考试/试卷快照）基础 TTL：快照发布后只读不可变，缓存与 DB 天然一致，可用长 TTL */
    private Duration snapshotBaseTtl = Duration.ofHours(2);

    /** 快照类 TTL 随机抖动上限（防雪崩）：每 key 实际 TTL = base + random(0, jitter) */
    private Duration snapshotJitter = Duration.ofMinutes(20);

    /** 考试详情缓存基础 TTL：有更新路径（update/publish/forceEnd），短 TTL 兜底 + 写路径显式 @CacheEvict */
    private Duration detailBaseTtl = Duration.ofMinutes(5);

    /** 考试详情 TTL 随机抖动上限（防雪崩） */
    private Duration detailJitter = Duration.ofMinutes(1);

    /** 防击穿互斥锁 TTL（秒）：SETNX 持有时长上限，防持锁线程崩溃后死锁，正常在 finally 主动释放 */
    private long lockTtlSeconds = 10;

    /** 未抢到锁的等待间隔（毫秒）：每轮 sleep 后检查缓存回填/空标记 */
    private long waitIntervalMs = 50;

    /** 未抢到锁的最大等待轮数：超时后兜底直接回源（宁可多打一次 DB，不可无限阻塞业务线程） */
    private int waitMaxRounds = 20;

    /** 防穿透空值缓存开关：关闭后 NOT_FOUND 不写空标记（每次都回源） */
    private boolean nullCacheEnabled = true;

    /** 空标记短 TTL（秒）：宁可短一些让穿透概率快速恢复，也不长期掩盖"数据已存在"的事实 */
    private long nullTtlSeconds = 30;
}

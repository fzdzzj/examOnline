package com.exam.anticheat.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 行为事件计数器（spec「切屏次数达到阈值 SHALL 提升严重度」的支撑件）：
 *
 * <p>用 Redis INCR 记录"某考试某学生某事件类型"的累计次数（key:
 * {@code exam:behavior:count:{examId}:{studentId}:{eventType}}），供需要历史上下文的
 * 事件策略（当前仅切屏）做阈值升级判定。计数 TTL 覆盖整场考试（默认 12h，
 * 大于任何单场考试时长），跨考场/跨学生天然隔离。
 *
 * <p>设计取舍：计数能力独立成服务而非内置在采集核心——核心保持"分派→判定→落库"
 * 零事件语义；哪个事件需要计数、按什么窗口计数，都是策略自己的事。
 * Redis 异常时降级为"首次事件"（返回 1）：计数失败只影响升级精度，绝不影响采集主链路。
 */
@Slf4j
@Service
public class BehaviorCounterService {

    /** 计数 key 前缀：exam:behavior:count:{examId}:{studentId}:{eventType} */
    static final String KEY_PREFIX = "exam:behavior:count:";

    private final StringRedisTemplate redisTemplate;

    /** 计数 TTL（小时）：需大于最长考试时长，保证整场考试的计数窗口完整 */
    @Value("${exam.anticheat.counter-ttl-hours:12}")
    private int ttlHours;

    public BehaviorCounterService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 原子自增并返回该学生该事件的累计次数（首次返回 1）。
     * 首次写入时设置 TTL；Redis 异常降级返回 1（按首次事件处理，不抛出）。
     */
    public long increment(Long examId, Long studentId, String eventType) {
        String key = KEY_PREFIX + examId + ":" + studentId + ":" + eventType;
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1) {
                redisTemplate.expire(key, Duration.ofHours(ttlHours));
            }
            return count == null ? 1L : count;
        } catch (Exception e) {
            log.error("行为计数 Redis 异常，按首次事件处理: exam={} student={} type={}",
                    examId, studentId, eventType, e);
            return 1L;
        }
    }
}

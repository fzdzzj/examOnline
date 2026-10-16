package com.exam.anticheat.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

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
 *
 * <p><b>计数原子化（fix-behavior-counter-ttl-atomicity）</b>：increment 的「INCR + 首置 EXPIRE」
 * 经 Lua 单脚本单次 EVAL 完成（先例 LoginGuardService.incrementWithTtl 只读参照，脚本常量本地复制
 * 于本包、不引用 auth 包内部常量）——若拆成两步，INCR 后进程崩溃/连接异常会让键永不过期
 * （键空间无界累积、超 TTL 窗口后同键计数残留影响阈值升级判定口径）。KEYS[1]=计数键，
 * ARGV[1]=TTL 秒，首次自增（count==1）时顺带置过期，返回自增后的计数。
 */
@Slf4j
@Service
public class BehaviorCounterService {

    /** 计数 key 前缀：exam:behavior:count:{examId}:{studentId}:{eventType} */
    static final String KEY_PREFIX = "exam:behavior:count:";

    /**
     * INCR 并首次置 EXPIRE 的原子脚本（先例 LoginGuardService.incrementWithTtl 只读参照，
     * 常量本地复制于 anticheat 包、不引用 auth 包内部常量）：防止「已递增但永不过期」的计数键
     * 两步间隙形态。KEYS[1]=计数键，ARGV[1]=TTL 秒，首次自增（c==1）时顺带置过期，返回自增计数。
     */
    private static final String INCR_WITH_TTL_LUA =
            "local c = redis.call('incr', KEYS[1])\n" +
            "if c == 1 then\n" +
            "  redis.call('expire', KEYS[1], ARGV[1])\n" +
            "end\n" +
            "return c\n";

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> incrWithTtlScript;

    /** 计数 TTL（小时）：需大于最长考试时长，保证整场考试的计数窗口完整 */
    @Value("${exam.anticheat.counter-ttl-hours:12}")
    private int ttlHours;

    public BehaviorCounterService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        DefaultRedisScript<Long> s = new DefaultRedisScript<>();
        s.setResultType(Long.class);
        s.setScriptText(INCR_WITH_TTL_LUA);
        this.incrWithTtlScript = s;
    }

    /**
     * 原子自增并返回该学生该事件的累计次数（首次返回 1）。
     * 首次写入时设置 TTL；Redis 异常降级返回 1（按首次事件处理，不抛出）。
     */
    public long increment(Long examId, Long studentId, String eventType) {
        String key = KEY_PREFIX + examId + ":" + studentId + ":" + eventType;
        try {
            Long count = redisTemplate.execute(incrWithTtlScript,
                    List.of(key), String.valueOf(Duration.ofHours(ttlHours).toSeconds()));
            return count == null ? 1L : count;
        } catch (Exception e) {
            log.error("行为计数 Redis 异常，按首次事件处理: exam={} student={} type={}",
                    examId, studentId, eventType, e);
            return 1L;
        }
    }
}
